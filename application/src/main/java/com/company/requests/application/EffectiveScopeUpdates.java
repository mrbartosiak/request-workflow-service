package com.company.requests.application;

import com.company.requests.application.EffectiveScopeChange.Move;
import com.company.requests.application.EffectiveScopeChange.SetGrant;
import com.company.requests.application.EffectiveScopeChange.SetVisibility;
import com.company.requests.application.EffectiveScopeStore.Assignment;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeStore.GrantOverride;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Internal, explicitly authorized mutation entry points for the mutable synthetic scope. */
public final class EffectiveScopeUpdates {
    public enum Permission { ENABLE_GRANT, REVOKE_GRANT, MOVE_ASSIGNMENT, SET_VISIBILITY }

    private final Admission admission;
    private final SyntheticScopePolicy policy;
    private final ScopeAuthorityMutation mutation;
    private final EffectiveScopeGate gate;
    private final EffectiveScopeStore store;
    private final RequestStore requests;

    public EffectiveScopeUpdates(SyntheticScopePolicy policy, WriterEpoch writer,
                                 ScopeAuthorityMutation mutation, EffectiveScopeGate gate,
                                 EffectiveScopeStore store, RequestStore requests) {
        this.policy = Objects.requireNonNull(policy);
        admission = new Admission(policy.genesis(), writer);

        this.mutation = Objects.requireNonNull(mutation);
        this.gate = Objects.requireNonNull(gate);
        this.store = Objects.requireNonNull(store);
        this.requests = Objects.requireNonNull(requests);
    }

    /** A local effective commit. Only audited resolution can confirm this attempt for public use. */
    public record PendingConfirmation(UUID attemptId, long sequence, long effectiveVersion) { }

    public PendingConfirmation stageRevocation(AuthenticatedActor operator, GrantKey key) {
        requireOperator(operator, Permission.REVOKE_GRANT);
        return stageGrant(operator, key, Permission.REVOKE_GRANT, false);
    }

    public PendingConfirmation stageEnablement(AuthenticatedActor operator, GrantKey key) {
        requireOperator(operator, Permission.ENABLE_GRANT);
        return stageGrant(operator, key, Permission.ENABLE_GRANT, true);
    }

    private PendingConfirmation stageGrant(AuthenticatedActor operator, GrantKey key, Permission permission,
                                           boolean allowed) {
        boolean configured = policy.permits(key);
        return stage(operator, permission, guard -> {
            var prior = store.grantOverride(guard, key);
            if (!allowed && !configured && prior.isEmpty()) {
                throw new AccessDeniedException();
            }
            boolean current = prior.map(GrantOverride::allowed).orElse(configured);
            return new SetGrant(key, prior, current, allowed);
        });
    }

    public PendingConfirmation stageAssignment(AuthenticatedActor operator, RequestRef ref, AuthenticatedActor assignee) {
        requireOperator(operator, Permission.MOVE_ASSIGNMENT);
        var person = policy.grant(assignee).actor().person().orElseThrow(AccessDeniedException::new);
        return stage(operator, Permission.MOVE_ASSIGNMENT, guard -> {
            requireTarget(guard, ref);
            var old = store.assignment(guard, ref).orElseThrow(AuthorityUnavailableException::new);
            return new Move(ref, old, new Assignment(person, Math.incrementExact(old.version())));
        });
    }

    public PendingConfirmation stageVisibility(AuthenticatedActor operator, RequestRef ref, boolean assigneeOnly,
                                               Set<Field> fields) {
        requireOperator(operator, Permission.SET_VISIBILITY);
        var exactFields = Set.copyOf(fields);
        return stage(operator, Permission.SET_VISIBILITY, guard -> {
            requireTarget(guard, ref);
            var old = store.visibility(guard, ref);
            return new SetVisibility(ref, old, new Visibility(Math.incrementExact(old.version()), assigneeOnly, exactFields));
        });
    }

    private PendingConfirmation stage(AuthenticatedActor operator, Permission permission,
                                      Function<PolicyGuard, EffectiveScopeChange.Edit> edit) {
        var staged = mutation.run(admission, guard -> {
            gate.requireCurrent(admission, guard);
            requireOperator(operator, permission);
            var change = new EffectiveScopeChange(guard, operator, edit.apply(guard));
            return new ScopeAuthorityMutation.Plan<>(change.delta(), entry -> {
                store.apply(guard, entry, change);
                return new PendingConfirmation(entry.attempt().id(), entry.sequence().orElseThrow(),
                        entry.attempt().afterVersion());
            });
        });
        return staged.result();
    }

    private void requireOperator(AuthenticatedActor actor, Permission permission) {
        if (!policy.permitsMutation(actor, permission)) {
            throw new AccessDeniedException();
        }
    }

    private void requireTarget(PolicyGuard guard, RequestRef ref) {
        requests.find(guard.organization(), ref).filter(target -> target.state() != RequestState.DELETED)
                .orElseThrow(AccessDeniedException::new);
    }
}
