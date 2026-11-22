package com.company.requests.application;

import com.company.requests.application.EffectiveScopeStore.Assignment;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Current submitted, result and field access for the configured synthetic scope. */
public final class CurrentAccess {
    private static final Set<Field> CREATE_RESULT_FIELDS = Set.of(
            Field.REF, Field.ACTION, Field.RESULT_REVISION, Field.STATE);

    private final ScopeAuthority authority;
    private final WriterEpoch writer;
    private final Map<OrganizationId, SyntheticScopePolicy> policies;
    private final Effective effective;

    public CurrentAccess(ScopeAuthority authority, WriterEpoch writer, Collection<SyntheticScopePolicy> policies) {
        this(Objects.requireNonNull(authority, "scope authority"), writer, policies, null);
    }

    public static CurrentAccess withEffectiveScope(WriterEpoch writer, Collection<SyntheticScopePolicy> policies,
                                                   TransactionRunner transactions, PolicyGuardStore guards,
                                                   RequestStore requests, EffectiveScopeStore store,
                                                   EffectiveScopeGate gate) {
        return new CurrentAccess(null, writer, policies, new Effective(Objects.requireNonNull(transactions),
                Objects.requireNonNull(guards), Objects.requireNonNull(requests), Objects.requireNonNull(store),
                Objects.requireNonNull(gate)));
    }

    private CurrentAccess(ScopeAuthority authority, WriterEpoch writer, Collection<SyntheticScopePolicy> policies,
                          Effective effective) {
        this.authority = authority;
        this.writer = Objects.requireNonNull(writer, "writer epoch");
        Map<OrganizationId, SyntheticScopePolicy> scopes = new HashMap<>();
        for (SyntheticScopePolicy policy : policies) {
            if (scopes.putIfAbsent(policy.genesis().organization(), policy) != null) {
                throw new IllegalArgumentException("An organization requires one synthetic policy.");
            }
        }
        this.policies = Map.copyOf(scopes);
        this.effective = effective;
    }

    boolean usesEffectiveScope() {
        return effective != null;
    }

    public Admission requireKeyIssue(AuthenticatedActor actor) {
        return preflight(actor, guard -> requireKeyIssue(actor, guard));
    }

    <T> T withKeyIssue(AuthenticatedActor actor, Function<Admission, T> issue) {
        return preflight(actor, guard -> issue.apply(requireKeyIssue(actor, guard)));
    }

    Admission requireKeyIssueOnCommand(AuthenticatedActor actor) {
        return requireKeyIssue(actor, effective == null ? null : shared(actor));
    }

    private Admission requireKeyIssue(AuthenticatedActor actor, PolicyGuard guard) {
        var policy = policy(actor);
        var admission = requireProof(policy, guard);
        if (!permitted(policy, guard, actor, Grant.KEY_ISSUE, null)) {
            throw new AccessDeniedException();
        }
        return admission;
    }

    /** Preflight before receipt arbitration or lookup. */
    public Admission requireCreate(AuthenticatedActor actor, RequestType type) {
        return preflight(actor, guard -> requireCreate(actor, type, policy(actor), guard));
    }

    Admission requireCreateOnCommand(AuthenticatedActor actor, RequestType type) {
        return requireCreate(actor, type, policy(actor), effective == null ? null : shared(actor));
    }

    /** Recheck inside the command or release guard. */
    public Admission requireCreate(AuthenticatedActor actor, RequestType type, PolicyGuard guard) {
        return requireCreate(actor, type, policy(actor), Objects.requireNonNull(guard, "organization guard"));
    }

    private Admission requireCreate(AuthenticatedActor actor, RequestType type, SyntheticScopePolicy policy,
                                   PolicyGuard guard) {
        var admission = requireProof(policy, guard);
        if (actor.kind() != AuthenticatedActor.Kind.HUMAN || !permitted(policy, guard, actor, Grant.CREATE, type)) {
            throw new AccessDeniedException();
        }
        return admission;
    }

    boolean permitsCreateResult(AuthenticatedActor actor, PolicyGuard guard, Request target) {
        if (effective == null) {
            return policy(actor).grant(actor).createResult().contains(target.type());
        }
        return fields(actor, guard, target).containsAll(CREATE_RESULT_FIELDS);
    }

    /** Field membership for an internal reader already holding the organization's shared or exclusive guard. */
    public Set<Field> permittedFields(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref) {
        if (effective == null) {
            throw new IllegalStateException("Current field membership requires the effective scope profile.");
        }
        var policy = policy(actor);
        requireProof(policy, guard);
        policy.grant(actor);
        return effective.requests().find(actor.organization(), ref)
                .map(target -> fields(actor, guard, target)).orElseGet(Set::of);
    }

    Admission requireActionScope(AuthenticatedActor actor, PolicyGuard guard) {
        if (effective == null || !actor.organization().equals(guard.organization())) {
            throw new AuthorityUnavailableException();
        }
        return requireProof(policy(actor), guard);
    }

    /** The caller has proved the current scope on the guard before asking about a target. */
    Optional<ActionTarget> actionTarget(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref, Action action) {
        var policy = policy(actor);
        var found = effective.requests().find(guard.organization(), ref)
                .filter(target -> target.organization().equals(actor.organization()) && target.ref().equals(ref))
                .filter(target -> target.state() != RequestState.DELETED);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var target = found.orElseThrow();
        try {
            if (!permitted(policy, guard, actor, Grant.ROW_READ, target.type())) {
                return Optional.empty();
            }
        } catch (AccessDeniedException unregistered) {
            return Optional.empty();
        }
        var scope = rowScope(actor, guard, target);
        if (!scope.visible()) {
            return Optional.empty();
        }
        Field fact = switch (action) {
            case EDIT, VERIFY, ACCEPT -> Field.BODY;
            case REJECT, DELETE, PUBLISH -> Field.NAME;
            case CREATE -> throw new IllegalArgumentException("Create has no action context.");
        };
        Grant materialGrant = fact == Field.BODY ? Grant.FIELD_READ_BODY : Grant.FIELD_READ_NAME;
        // The B10 synthetic material mask makes both NAME and BODY assignee-only.
        if (!permitted(policy, guard, actor, Grant.context(action), target.type())
                || !permitted(policy, guard, actor, Grant.execute(action), target.type())
                || !permitted(policy, guard, actor, materialGrant, target.type())
                || !scope.visibility().fields().contains(Field.STATE)
                || !scope.visibility().fields().contains(fact)
                || !scope.assigned()
                || (action == Action.ACCEPT && actor.person().filter(
                        person -> !person.equals(target.originatingPersonId())).isEmpty())) {
            throw new AccessDeniedException();
        }
        return Optional.of(new ActionTarget(target, scope.assignment(), scope.visibility(), fact));
    }

    record ActionTarget(Request request, Assignment assignment, Visibility visibility, Field materialField) { }

    private Set<Field> fields(AuthenticatedActor actor, PolicyGuard guard, Request target) {
        if (!guard.organization().equals(actor.organization()) || !target.organization().equals(actor.organization())
                || target.state() == RequestState.DELETED
                || !permitted(policy(actor), guard, actor, Grant.CREATE_RESULT, target.type())) {
            return Set.of();
        }
        var scope = rowScope(actor, guard, target);
        if (!scope.visible()) {
            return Set.of();
        }
        var permitted = EnumSet.noneOf(Field.class);
        permitted.addAll(scope.visibility().fields());
        if (!scope.assigned()) {
            permitted.remove(Field.NAME);
            permitted.remove(Field.BODY);
        }
        return Set.copyOf(permitted);
    }

    private RowScope rowScope(AuthenticatedActor actor, PolicyGuard guard, Request target) {
        var assignment = effective.store().assignment(guard, target.ref()).orElseThrow(AuthorityUnavailableException::new);
        var visibility = effective.store().visibility(guard, target.ref());
        boolean assigned = actor.person().filter(assignment.person()::equals).isPresent();
        return new RowScope(assignment, visibility, assigned);
    }

    private record RowScope(Assignment assignment, Visibility visibility, boolean assigned) {
        boolean visible() {
            return visibility.fields().contains(Field.REF) && (!visibility.assigneeOnly() || assigned);
        }
    }

    private boolean permitted(SyntheticScopePolicy policy, PolicyGuard guard, AuthenticatedActor actor,
                              Grant grant, RequestType type) {
        var key = new GrantKey(actor, grant, Optional.ofNullable(type));
        boolean configured = policy.permits(key);
        return effective == null ? configured
                : effective.store().grantOverride(guard, key).map(EffectiveScopeStore.GrantOverride::allowed)
                .orElse(configured);
    }

    private SyntheticScopePolicy policy(AuthenticatedActor actor) {
        var policy = policies.get(actor.organization());
        if (policy == null) {
            throw new AuthorityUnavailableException();
        }
        return policy;
    }

    private Admission requireProof(SyntheticScopePolicy policy, PolicyGuard guard) {
        var admission = new Admission(policy.genesis(), writer);
        if (effective != null) {
            effective.gate().requireCurrent(admission, Objects.requireNonNull(guard, "organization guard"));
        } else {
            authority.requireCurrent(admission);
            if (guard != null && (!guard.organization().equals(admission.genesis().organization())
                    || guard.policyVersion() != admission.genesis().policyVersion())) {
                throw new AuthorityUnavailableException();
            }
        }
        return admission;
    }

    private <T> T preflight(AuthenticatedActor actor, Function<PolicyGuard, T> work) {
        return effective == null ? work.apply(null) : effective.transactions().run(() -> work.apply(shared(actor)));
    }

    private PolicyGuard shared(AuthenticatedActor actor) {
        return effective.guards().lockShared(actor.organization()).orElseThrow(AuthorityUnavailableException::new);
    }

    private record Effective(TransactionRunner transactions, PolicyGuardStore guards, RequestStore requests,
                             EffectiveScopeStore store, EffectiveScopeGate gate) { }
}
