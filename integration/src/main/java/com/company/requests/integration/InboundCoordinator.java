package com.company.requests.integration;

import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PolicyGuardStore;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.TransactionBusyException;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.TransactionResourceUnavailableException;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.InboundInbox.Prior;
import com.company.requests.integration.InboundInbox.Processing;
import com.company.requests.integration.InboundInbox.Receipt;
import com.company.requests.integration.InboundInbox.Scope;
import com.company.requests.integration.SyntheticInboundSources.Route;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/** The local synthetic inbound acknowledgment boundary. */
public final class InboundCoordinator {
    public enum Outcome { ACKNOWLEDGED, REJECTED, UNAVAILABLE }

    @FunctionalInterface
    public interface Continuity {
        /** Proof covers possible receipts and obligations for the exact source scope and current writer. */
        boolean admitted(PolicyGuard guard, Admission current, Scope scope);
    }

    @FunctionalInterface
    public interface Mappings {
        Optional<Target> verified(AuthenticatedActor actor, PolicyGuard guard, Key key);
    }

    @FunctionalInterface
    public interface TargetAccess {
        OptionalLong currentRevision(AuthenticatedActor actor, PolicyGuard guard, Target target);
    }

    private final SyntheticInboundSources sources;
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final Function<PolicyGuard, Admission> currentScope;
    private final Continuity continuity;
    private final Mappings mappings;
    private final TargetAccess targetAccess;
    private final InboundInbox inbox;

    public InboundCoordinator(SyntheticInboundSources sources, TransactionRunner transactions, PolicyGuardStore guards,
                              Function<PolicyGuard, Admission> currentScope, Continuity continuity, Mappings mappings,
                              TargetAccess targetAccess, InboundInbox inbox) {
        this.sources = Objects.requireNonNull(sources);
        this.transactions = Objects.requireNonNull(transactions);
        this.guards = Objects.requireNonNull(guards);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.continuity = Objects.requireNonNull(continuity);
        this.mappings = Objects.requireNonNull(mappings);
        this.targetAccess = Objects.requireNonNull(targetAccess);
        this.inbox = Objects.requireNonNull(inbox);
    }

    public Outcome receive(String credential, InboundNotice notice, byte[] proof) {
        return sources.verify(credential, notice, proof)
                .map(route -> receiveVerified(route, notice))
                .orElse(Outcome.REJECTED);
    }

    private Outcome receiveVerified(Route route, InboundNotice notice) {
        try {
            return transactions.run(() -> guards.lockExclusive(route.actor().organization())
                    .filter(guard -> guard.organization().equals(route.actor().organization()))
                    .map(guard -> receiveUnderGuard(guard, route, notice))
                    .orElse(Outcome.UNAVAILABLE));
        } catch (AccessDeniedException denied) {
            return Outcome.REJECTED;
        } catch (AuthorityUnavailableException | TransactionBusyException | TransactionResourceUnavailableException
                 | CommitOutcomeUnknownException unavailable) {
            return Outcome.UNAVAILABLE;
        }
    }

    private Outcome receiveUnderGuard(PolicyGuard guard, Route route, InboundNotice notice) {
        var admission = currentScope.apply(guard);
        if (!admission.genesis().organization().equals(guard.organization())
                || !continuity.admitted(guard, admission, route.scope())) {
            return Outcome.UNAVAILABLE;
        }
        var mappingScope = new ExternalMapping.Scope(route.scope().sourceSystem(), route.scope().tenant(),
                route.identifierType());
        var key = new Key(mappingScope, notice.externalId().bytes());
        return mappings.verified(route.actor(), guard, key)
                .filter(target -> target.organization().equals(guard.organization()))
                .map(target -> receiveMapped(guard, route, notice, target))
                .orElse(Outcome.REJECTED);
    }

    private Outcome receiveMapped(PolicyGuard guard, Route route, InboundNotice notice, Target target) {
        var currentRevision = targetAccess.currentRevision(route.actor(), guard, target);
        if (currentRevision.isEmpty()) {
            return Outcome.REJECTED;
        }
        var key = new InboundInbox.Key(route.scope(), notice.sourceIdentity());
        var receipt = new Receipt(key, notice.type(), target, notice.sourceVersion(), notice.targetRevision(),
                SyntheticInboundSources.semanticDigest(route, notice));
        var prior = inbox.classifyAndLock(guard, receipt);
        if (prior == Prior.EXACT) {
            return Outcome.ACKNOWLEDGED;
        }
        if (prior == Prior.CONFLICT) {
            inbox.quarantine(guard, receipt);
            return Outcome.REJECTED;
        }
        if (notice.targetRevision() != currentRevision.orElseThrow()
                || notice.sourceVersion() <= inbox.lastSourceVersion(guard, route.scope(), target)) {
            return Outcome.REJECTED;
        }
        inbox.accept(guard, new Processing(receipt, route.identifierType(), notice.externalId()));
        return Outcome.ACKNOWLEDGED;
    }
}
