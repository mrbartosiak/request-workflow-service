package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Shared, fresh organization disclosure gate through acceptance by a bounded outgoing buffer. */
public final class GuardedRelease {
    private static final Duration MAX_WINDOW = Duration.ofMillis(2400);
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final RequestStore requests;
    private final CurrentAccess access;
    private final long budgetNanos;
    private final ReleaseEvidence.Handoffs handoffEvidence;

    public GuardedRelease(TransactionRunner transactions, PolicyGuardStore guards, RequestStore requests,
                          CurrentAccess access, Duration window) {
        this(transactions, guards, requests, access, window, ignored -> { });
    }

    public GuardedRelease(TransactionRunner transactions, PolicyGuardStore guards, RequestStore requests,
                          CurrentAccess access, Duration window, ReleaseEvidence.Handoffs handoffEvidence) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.guards = Objects.requireNonNull(guards, "guards");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.access = Objects.requireNonNull(access, "access");
        this.handoffEvidence = Objects.requireNonNull(handoffEvidence, "handoff evidence");
        if (window.isNegative() || window.isZero() || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Release window is outside the allowed range.");
        }
        budgetNanos = window.toNanos();
    }

    public enum Outcome { HANDED_OFF, WITHHELD, FRESHNESS_LOST, BUFFER_UNAVAILABLE, BUDGET_EXCEEDED }

    public record Context(PolicyGuard guard, Admission admission) { }

    public record Payload(byte[] bytes, Optional<ReleaseEvidence.Correlation> correlation) { }

    public record Prepared(Payload payload, Predicate<Context> remainsAuthorized) { }

    /** Recheck a current read projection in the guard before handing off its encoded envelope. */
    <T> byte[] handoffRead(AuthenticatedActor actor, Function<Context, T> project, Function<T, byte[]> encode,
                           ReleaseBuffer buffer) {
        Outcome outcome;
        try {
            outcome = handoff(actor.organization(), guard -> access.requireActionScope(actor, guard), () -> true,
                    context -> {
                        var result = project.apply(context);
                        return Optional.of(new Prepared(new Payload(encode.apply(result), Optional.empty()),
                                current -> result.equals(project.apply(current))));
                    }, buffer);
        } catch (TransactionBusyException | TransactionResourceUnavailableException | CommitOutcomeUnknownException failure) {
            throw new AuthorityUnavailableException();
        }
        if (outcome != Outcome.HANDED_OFF) {
            throw new AuthorityUnavailableException();
        }
        return buffer.take().orElseThrow(AuthorityUnavailableException::new);
    }

    /**
     * The trusted loader resolves a verified operation key on the current command connection.
     * B08 supplies trusted-clock freshness and a bounded callback for independent preparation and
     * encoding. Receipt-derived outcomes are checked again before release, including withheld results.
     * The bounded slot accepts the handoff under the guard. Only a successful
     * transaction makes that slot drainable, after which the caller performs network I/O.
     */
    public Outcome handoffCreate(AuthenticatedActor actor, RequestType submittedType,
                                 BooleanSupplier fresh, Supplier<Optional<OperationReceipt>> loadCommitted,
                                 Function<OperationReceipt, byte[]> prepareAndEncode, ReleaseBuffer buffer) {
        return handoffCreate(actor, submittedType, ignored -> { }, fresh, ignored -> loadCommitted.get(),
                (admission, receipt) -> new Payload(prepareAndEncode.apply(receipt), Optional.empty()), buffer);
    }

    Outcome handoffCreate(AuthenticatedActor actor, RequestType submittedType, WriterLineage lineage,
                          BooleanSupplier fresh, Function<Admission, Optional<OperationReceipt>> loadCommitted,
                          BiFunction<Admission, OperationReceipt, Payload> prepareAndEncode, ReleaseBuffer buffer) {
        return handoff(actor.organization(), guard -> {
            var admission = access.requireCreate(actor, submittedType, guard);
            if (!access.usesEffectiveScope()) {
                lineage.requireCurrent(admission);
            }
            return admission;
        }, fresh, context -> loadCommitted.apply(context.admission())
                .filter(receipt -> isScopedCreate(receipt, actor))
                .flatMap(receipt -> requests.find(actor.organization(), receipt.resultRef())
                        .filter(target -> authorized(actor, context.guard(), receipt, target))
                        .map(target -> new Prepared(prepareAndEncode.apply(context.admission(), receipt),
                                current -> authorized(actor, current.guard(), receipt, target)))), buffer);
    }

    /**
     * Authorize the requested operation under the owned guard before loading protected data.
     * The initial projection and final authorization run on the same guarded connection.
     */
    public Outcome handoff(OrganizationId organization, Function<PolicyGuard, Admission> authorize,
                           BooleanSupplier fresh, Function<Context, Optional<Prepared>> prepare, ReleaseBuffer buffer) {
        long started = System.nanoTime();
        BooleanSupplier withinBudget = () -> System.nanoTime() - started < budgetNanos;
        var pending = new AtomicReference<Accepted>();
        try {
            Outcome outcome = transactions.runGuardedRelease(() -> stage(
                    organization, authorize, fresh, prepare, buffer, withinBudget, pending));
            if (outcome == Outcome.HANDED_OFF) {
                var accepted = pending.get();
                buffer.complete(accepted.slot());
                accepted.correlation().ifPresent(correlation -> {
                    try {
                        handoffEvidence.record(correlation);
                    } catch (AuthorityUnavailableException unavailable) {
                        // The slot is already drainable. Unretained evidence leaves handoff unknown.
                    }
                });
            }
            return outcome;
        } finally {
            // On failure, only this call's pending slot can be cancelled.
            Optional.ofNullable(pending.get()).ifPresent(accepted -> buffer.discard(accepted.slot()));
        }
    }

    private Outcome stage(OrganizationId organization, Function<PolicyGuard, Admission> authorize,
                          BooleanSupplier fresh, Function<Context, Optional<Prepared>> prepare, ReleaseBuffer buffer,
                          BooleanSupplier withinBudget, AtomicReference<Accepted> pending) {
        var guard = guards.lockShared(organization).orElseThrow(AuthorityUnavailableException::new);
        var admission = authorize.apply(guard);
        requireScope(guard, admission);
        if (!fresh.getAsBoolean()) {
            return Outcome.FRESHNESS_LOST;
        }
        if (!withinBudget.getAsBoolean()) {
            return Outcome.BUDGET_EXCEEDED;
        }
        var candidate = prepare.apply(new Context(guard, admission));

        boolean authorized = candidate.filter(value -> value.remainsAuthorized().test(new Context(guard, admission)))
                .isPresent();
        if (!admission.equals(authorize.apply(guard))) {
            throw new AuthorityUnavailableException();
        }
        // Preparation and proof use independent connections. A lost local session has lost its guard.
        if (guards.find(organization).filter(guard::equals).isEmpty()) {
            throw new AuthorityUnavailableException();
        }
        if (!fresh.getAsBoolean()) {
            return Outcome.FRESHNESS_LOST;
        }
        if (!authorized) {
            return Outcome.WITHHELD;
        }
        if (!withinBudget.getAsBoolean()) {
            return Outcome.BUDGET_EXCEEDED;
        }
        var payload = candidate.orElseThrow().payload();
        if (payload.correlation().filter(value -> !value.organization().equals(organization)).isPresent()) {
            throw new AuthorityUnavailableException();
        }
        var expired = new AtomicBoolean();
        ReleaseBuffer.Pending handoff = buffer.offer(payload.bytes(), () -> {
            expired.set(!fresh.getAsBoolean());
            return !expired.get() && withinBudget.getAsBoolean();
        });
        if (handoff == null) {
            if (expired.get() || !fresh.getAsBoolean()) {
                return Outcome.FRESHNESS_LOST;
            }
            return withinBudget.getAsBoolean() ? Outcome.BUFFER_UNAVAILABLE : Outcome.BUDGET_EXCEEDED;
        }
        pending.set(new Accepted(handoff, payload.correlation()));
        return Outcome.HANDED_OFF;
    }

    private static void requireScope(PolicyGuard guard, Admission admission) {
        if (!guard.organization().equals(admission.genesis().organization())) {
            throw new AuthorityUnavailableException();
        }
    }

    private boolean authorized(AuthenticatedActor actor, PolicyGuard guard, OperationReceipt receipt, Request target) {
        return target.organization().equals(actor.organization()) && target.ref().equals(receipt.resultRef())
                && target.creationOrder().epoch() == receipt.keyGeneration() && target.state() != RequestState.DELETED
                && access.permitsCreateResult(actor, guard, target);
    }

    boolean permitsReceipt(AuthenticatedActor actor, PolicyGuard guard, OperationReceipt receipt) {
        if (access.usesEffectiveScope()) {
            return access.permitsReceipt(actor, guard, receipt);
        }
        return isScopedCreate(receipt, actor) && requests.find(actor.organization(), receipt.resultRef())
                .filter(target -> authorized(actor, guard, receipt, target)).isPresent();
    }

    private static boolean isScopedCreate(OperationReceipt receipt, AuthenticatedActor actor) {
        return receipt.action() == Action.CREATE && receipt.key().organization().equals(actor.organization())
                && receipt.key().issuer().equals(actor.issuer()) && receipt.key().principal().equals(actor.principal());
    }

    private record Accepted(ReleaseBuffer.Pending slot, Optional<ReleaseEvidence.Correlation> correlation) { }
}
