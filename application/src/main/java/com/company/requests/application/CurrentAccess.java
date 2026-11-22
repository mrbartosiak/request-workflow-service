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
    private static final Set<Field> RESULT_FIELDS = Set.of(
            Field.REF, Field.ACTION, Field.RESULT_REVISION, Field.STATE);
    private static final Set<Field> DELETED_METADATA_FIELDS = Set.of(Field.REF, Field.STATE);

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
        return fields(actor, guard, target).containsAll(RESULT_FIELDS);
    }

    /** Field membership for an internal reader already holding the organization's shared or exclusive guard. */
    public Set<Field> permittedFields(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref) {
        if (effective == null) {
            throw new IllegalStateException("Current field membership requires the effective scope profile.");
        }
        var policy = policy(actor);
        requireProof(policy, guard);
        policy.grant(actor);
        return scopedTarget(actor, guard, ref).map(target -> target.state() == RequestState.DELETED
                ? deletedMetadata(actor, guard, target) : fields(actor, guard, target)).orElseGet(Set::of);
    }

    Admission requireActionScope(AuthenticatedActor actor, PolicyGuard guard) {
        if (effective == null || !actor.organization().equals(guard.organization())) {
            throw new AuthorityUnavailableException();
        }
        return requireProof(policy(actor), guard);
    }

    /** Current scope prerequisite for an internal dispatcher holding the organization's guard. */
    public Admission requireDispatchScope(PolicyGuard guard) {
        var policy = policies.get(guard.organization());
        if (effective == null || policy == null) {
            throw new AuthorityUnavailableException();
        }
        return requireProof(policy, guard);
    }

    SubmittedAction requireSubmittedAction(AuthenticatedActor actor, RequestRef ref, Action action) {
        var guard = shared(actor);
        return requireSubmittedAction(actor, guard, ref, action);
    }

    SubmittedAction requireSubmittedAction(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref, Action action) {
        var admission = requireActionScope(actor, guard);
        var target = scopedTarget(actor, guard, ref).orElseThrow(TargetConcealed::new);
        if (target.state() == RequestState.DELETED) {
            // The tombstone has a separate receipt surface. It cannot admit an action context or a new write.
            if (action != Action.DELETE || !permitsDeleteResult(actor, guard, target)) {
                throw new TargetConcealed();
            }
            return new SubmittedAction(admission, true);
        }
        actionTarget(actor, guard, target, action).orElseThrow(TargetConcealed::new);
        return new SubmittedAction(admission, false);
    }

    record SubmittedAction(Admission admission, boolean lookupOnly) { }

    static final class TargetConcealed extends RuntimeException { }

    /** Current disclosure for the original side of an exact replay or cross-route conflict. */
    boolean permitsReceipt(AuthenticatedActor actor, PolicyGuard guard, OperationReceipt receipt) {
        try {
            if (receipt.action() == Action.CREATE) {
                return effective.requests().find(actor.organization(), receipt.resultRef())
                        .filter(target -> target.ref().equals(receipt.resultRef()))
                        .filter(target -> target.creationOrder().epoch() == receipt.keyGeneration())
                        .filter(target -> permitted(policy(actor), guard, actor, Grant.CREATE, target.type()))
                        .filter(target -> permitsCreateResult(actor, guard, target)).isPresent();
            }
            if (receipt.action() == Action.DELETE) {
                return scopedTarget(actor, guard, receipt.resultRef())
                        .filter(target -> target.state() == RequestState.DELETED)
                        .filter(target -> permitsDeleteResult(actor, guard, target)).isPresent();
            }
            var resultGrant = switch (receipt.action()) {
                case EDIT -> Grant.RESULT_READ_EDIT;
                case VERIFY -> Grant.RESULT_READ_VERIFY;
                case ACCEPT -> Grant.RESULT_READ_ACCEPT;
                case REJECT -> Grant.RESULT_READ_REJECT;
                default -> null;
            };
            return resultGrant != null && actionTarget(actor, guard, receipt.resultRef(), receipt.action())
                    .filter(target -> target.visibility().fields().containsAll(RESULT_FIELDS))
                    .filter(target -> permitted(policy(actor), guard, actor, resultGrant, target.request().type()))
                    .isPresent();
        } catch (AccessDeniedException denied) {
            return false;
        }
    }

    /** The caller has proved the current scope on the guard before asking about a target. */
    Optional<ActionTarget> actionTarget(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref, Action action) {
        return scopedTarget(actor, guard, ref).filter(target -> target.state() != RequestState.DELETED)
                .flatMap(target -> actionTarget(actor, guard, target, action));
    }

    private Optional<Request> scopedTarget(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref) {
        return effective.requests().find(guard.organization(), ref)
                .filter(target -> target.organization().equals(actor.organization()) && target.ref().equals(ref));
    }

    private Optional<ActionTarget> actionTarget(AuthenticatedActor actor, PolicyGuard guard, Request target, Action action) {
        var policy = policy(actor);
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

    private Set<Field> deletedMetadata(AuthenticatedActor actor, PolicyGuard guard, Request target) {
        return deletedScope(actor, guard, target).map(scope -> {
            var fields = EnumSet.copyOf(DELETED_METADATA_FIELDS);
            fields.retainAll(scope.visibility().fields());
            return Set.copyOf(fields);
        }).orElseGet(Set::of);
    }

    private boolean permitsDeleteResult(AuthenticatedActor actor, PolicyGuard guard, Request target) {
        var policy = policy(actor);
        // Replaying the minimum receipt fields never restores access to the original material fact.
        return deletedScope(actor, guard, target)
                .filter(scope -> scope.visibility().fields().containsAll(RESULT_FIELDS))
                .filter(scope -> permitted(policy, guard, actor, Grant.CONTEXT_DELETE, target.type()))
                .filter(scope -> permitted(policy, guard, actor, Grant.EXECUTE_DELETE, target.type()))
                .filter(scope -> permitted(policy, guard, actor, Grant.RESULT_READ_DELETE, target.type())).isPresent();
    }

    private Optional<RowScope> deletedScope(AuthenticatedActor actor, PolicyGuard guard, Request target) {
        var policy = policy(actor);
        try {
            if (permitted(policy, guard, actor, Grant.DELETED_METADATA, target.type())
                    && permitted(policy, guard, actor, Grant.ROW_READ, target.type())) {
                var scope = rowScope(actor, guard, target);
                if (scope.visible()) {
                    return Optional.of(scope);
                }
            }
        } catch (AccessDeniedException unregistered) {
            return Optional.empty();
        }
        return Optional.empty();
    }

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
