package com.company.requests.integration;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PolicyGuardStore;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.ExternalMapping.Association;
import com.company.requests.integration.ExternalMapping.Binding;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMapping.Publication;
import com.company.requests.integration.ExternalMapping.Scope;
import com.company.requests.integration.ExternalMapping.Target;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/** The guarded integration boundary for binding, using and recovering external identities. */
public final class ExternalMappings {
    public enum Operation { BIND, USE, RECOVER }

    public enum Outcome { PROJECTED, RECONCILED, QUARANTINED, FENCED }

    public record Authorization(Set<RequestType> targetTypes, boolean publicationRequired) {
        public Authorization {
            targetTypes = Set.copyOf(targetTypes);
        }
    }

    /** Trusted policy binds each external scope to its organization and controls operation and publication applicability. */
    @FunctionalInterface
    public interface ScopePolicy {
        Optional<Authorization> authorize(AuthenticatedActor actor, PolicyGuard guard, Scope scope, Operation operation);
    }

    @FunctionalInterface
    public interface OriginalCreate {
        Optional<CreationOrder> committed(PolicyGuard guard, Target target);
    }

    @FunctionalInterface
    public interface OriginalEffect {
        /** A committed original projection from a certified lineage or equivalent retained proof. */
        boolean committed(Binding binding);
    }

    public interface Registry {
        /** Persists independently before returning, regardless of the surrounding workflow transaction. */
        Reservation reserve(Key key, Association association, WriterEpoch currentWriter);

        /** A quarantined key cannot be used. */
        Optional<Binding> find(Key key);

        record Reservation(State state, Optional<Binding> binding) {
            public enum State { RESERVED, EQUIVALENT, QUARANTINED }

            public Reservation {
                Objects.requireNonNull(state);
                Objects.requireNonNull(binding);
                if ((state != State.QUARANTINED) != binding.isPresent()) {
                    throw new IllegalArgumentException("Invalid mapping reservation outcome.");
                }
            }
        }
    }

    public interface Projection {
        Optional<Binding> read(PolicyGuard guard, Key key);

        /** An effect rebuilt during recovery must never qualify as original evidence. */
        void save(PolicyGuard guard, Binding binding, boolean original);
    }

    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final ScopePolicy policy;
    private final Function<PolicyGuard, Admission> currentScope;
    private final OriginalCreate creates;
    private final BiFunction<PolicyGuard, RequestRef, Optional<PublicationId>> confirmedPublication;
    private final Registry registry;
    private final Projection projection;
    private final OriginalEffect originalEffect;

    public ExternalMappings(TransactionRunner transactions, PolicyGuardStore guards, ScopePolicy policy,
                            Function<PolicyGuard, Admission> currentScope, OriginalCreate creates,
                            BiFunction<PolicyGuard, RequestRef, Optional<PublicationId>> confirmedPublication,
                            Registry registry, Projection projection, OriginalEffect originalEffect) {
        this.transactions = Objects.requireNonNull(transactions);
        this.guards = Objects.requireNonNull(guards);
        this.policy = Objects.requireNonNull(policy);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.creates = Objects.requireNonNull(creates);
        this.confirmedPublication = Objects.requireNonNull(confirmedPublication);
        this.registry = Objects.requireNonNull(registry);
        this.projection = Objects.requireNonNull(projection);
        this.originalEffect = Objects.requireNonNull(originalEffect);
    }

    public Outcome bind(AuthenticatedActor actor, Key key, Target target, Optional<Publication> publication) {
        return transactions.run(() -> guarded(actor, key, Operation.BIND, context ->
                prove(context, target, publication).map(association -> {
                    var reservation = registry.reserve(key, association, context.writer());
                    if (reservation.state() == Registry.Reservation.State.QUARANTINED) {
                        return Outcome.QUARANTINED;
                    }
                    var binding = reservation.binding().orElseThrow();
                    if (!matches(binding, key, association)) {
                        return Outcome.FENCED;
                    }
                    var local = projection.read(context.guard(), key);
                    if (local.isPresent()) {
                        return local.filter(binding::equals).isPresent() ? Outcome.RECONCILED : Outcome.FENCED;
                    }
                    // Only the still-certified original writer can finish an initial projection.
                    if (!binding.writer().equals(context.writer())) {
                        return Outcome.FENCED;
                    }
                    projection.save(context.guard(), binding, true);
                    return reservation.state() == Registry.Reservation.State.RESERVED
                            ? Outcome.PROJECTED : Outcome.RECONCILED;
                })).orElse(Outcome.FENCED));
    }

    public Outcome recover(AuthenticatedActor actor, Key key) {
        return transactions.run(() -> guarded(actor, key, Operation.RECOVER, context ->
                authorizedBinding(context, key).map(binding -> {
                    var local = projection.read(context.guard(), key);
                    if (local.isPresent()) {
                        return local.filter(binding::equals).isPresent() ? Outcome.RECONCILED : Outcome.FENCED;
                    }
                    if (!originalEffect.committed(binding)) {
                        return Outcome.FENCED;
                    }
                    projection.save(context.guard(), binding, false);
                    return Outcome.PROJECTED;
                })).orElse(Outcome.FENCED));
    }

    /** Run local business work under current scope and exact committed projection proof. */
    public <T> Optional<T> withVerifiedMapping(AuthenticatedActor actor, Key key, Function<Association, T> work) {
        Objects.requireNonNull(work);
        return transactions.run(() -> guards.lockShared(actor.organization())
                .flatMap(guard -> verifiedUnderGuard(actor, guard, key))
                .map(association -> Objects.requireNonNull(work.apply(association))));
    }

    /** Verify for an internal caller already holding the current organization's workflow guard. */
    public Optional<Association> verifiedUnderGuard(AuthenticatedActor actor, PolicyGuard guard, Key key) {
        return authorize(actor, guard, key.scope(), Operation.USE)
                .flatMap(context -> authorizedBinding(context, key))
                .filter(binding -> projection.read(guard, key).filter(binding::equals).isPresent())
                .map(Binding::association);
    }

    private Optional<Binding> authorizedBinding(Context context, Key key) {
        return registry.find(key).filter(binding -> {
            var association = binding.association();
            return prove(context, association.target(), association.publication())
                    .filter(proved -> matches(binding, key, proved)).isPresent();
        });
    }

    private static boolean matches(Binding binding, Key key, Association association) {
        return key.equals(binding.key()) && association.equals(binding.association());
    }

    private Optional<Association> prove(Context context, Target target, Optional<Publication> publication) {
        if (!context.guard().organization().equals(target.organization())
                || !context.authorization().targetTypes().contains(target.type())
                || (context.authorization().publicationRequired() && publication.isEmpty())) {
            return Optional.empty();
        }
        return creates.committed(context.guard(), target)
                .filter(ignored -> publication.isEmpty() || publication
                        .filter(value -> PublicationId.INITIAL_NAMESPACE.equals(value.namespace()))
                        .map(Publication::id).flatMap(id -> confirmedPublication.apply(context.guard(), target.ref())
                                .filter(id::equals)).isPresent())
                .map(creation -> new Association(target, creation, publication));
    }

    private <T> Optional<T> guarded(AuthenticatedActor actor, Key key, Operation operation,
                                    Function<Context, Optional<T>> work) {
        return guards.lockExclusive(actor.organization())
                .flatMap(guard -> authorize(actor, guard, key.scope(), operation))
                .flatMap(work);
    }

    private Optional<Context> authorize(AuthenticatedActor actor, PolicyGuard guard, Scope scope, Operation operation) {
        if (!actor.organization().equals(guard.organization())) {
            return Optional.empty();
        }
        return policy.authorize(actor, guard, scope, operation).flatMap(authorization -> {
            var admission = currentScope.apply(guard);
            return admission.genesis().organization().equals(guard.organization())
                    ? Optional.of(new Context(guard, authorization, admission.writer())) : Optional.empty();
        });
    }

    private record Context(PolicyGuard guard, Authorization authorization, WriterEpoch writer) { }
}
