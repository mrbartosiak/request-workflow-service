package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fresh organization-guarded handoff for a trusted committed Create receipt. */
public final class GuardedRelease {
    private static final Duration MAX_WINDOW = Duration.ofMillis(2400);
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final RequestStore requests;
    private final CurrentAccess access;
    private final long budgetNanos;

    public GuardedRelease(TransactionRunner transactions, PolicyGuardStore guards, RequestStore requests,
                          CurrentAccess access, Duration window) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.guards = Objects.requireNonNull(guards, "guards");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.access = Objects.requireNonNull(access, "access");
        if (window.isNegative() || window.isZero() || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Release window is outside the allowed range.");
        }
        budgetNanos = window.toNanos();
    }

    public enum Outcome { HANDED_OFF, WITHHELD, FRESHNESS_LOST, BUFFER_UNAVAILABLE, BUDGET_EXCEEDED }

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
        long started = System.nanoTime();
        BooleanSupplier withinBudget = () -> System.nanoTime() - started < budgetNanos;
        var pending = new AtomicReference<ReleaseBuffer.Pending>();
        try {
            Outcome outcome = transactions.runGuardedRelease(() -> stageCreate(actor, submittedType, fresh, loadCommitted,
                    prepareAndEncode, buffer, withinBudget, pending));
            if (outcome == Outcome.HANDED_OFF) {
                buffer.complete(pending.get());
            }
            return outcome;
        } finally {
            // On failure, only this call's pending slot can be cancelled.
            Optional.ofNullable(pending.get()).ifPresent(buffer::discard);
        }
    }

    private Outcome stageCreate(AuthenticatedActor actor, RequestType submittedType,
                                BooleanSupplier fresh, Supplier<Optional<OperationReceipt>> loadCommitted,
                                Function<OperationReceipt, byte[]> prepareAndEncode, ReleaseBuffer buffer,
                                BooleanSupplier withinBudget, AtomicReference<ReleaseBuffer.Pending> pending) {
        var guard = guards.lockShared(actor.organization()).orElseThrow(AuthorityUnavailableException::new);
        access.requireCreate(actor, submittedType, guard);
        if (!fresh.getAsBoolean()) {
            return Outcome.FRESHNESS_LOST;
        }
        if (!withinBudget.getAsBoolean()) {
            return Outcome.BUDGET_EXCEEDED;
        }
        var receipt = loadCommitted.get().filter(value -> isScopedCreate(value, actor));
        var target = receipt.flatMap(original -> requests.find(actor.organization(), original.resultRef()))
                .filter(request -> request.state() != RequestState.DELETED
                        && access.permitsCreateResult(actor, request.type()));
        Outcome candidate = Outcome.WITHHELD;
        byte[] payload = null;
        if (target.isPresent()) {
            if (withinBudget.getAsBoolean()) {
                payload = prepareAndEncode.apply(receipt.orElseThrow());
                candidate = buffer.canOffer(payload) ? Outcome.HANDED_OFF : Outcome.BUFFER_UNAVAILABLE;
            } else {
                candidate = Outcome.BUDGET_EXCEEDED;
            }
        }

        access.requireCreate(actor, submittedType, guard);
        if (target.isPresent() && !access.permitsCreateResult(actor, target.get().type())) {
            candidate = Outcome.WITHHELD;
        }
        // Preparation and proof use independent connections. A lost local session has lost its guard.
        if (guards.find(actor.organization()).filter(guard::equals).isEmpty()) {
            throw new AuthorityUnavailableException();
        }
        if (!fresh.getAsBoolean()) {
            return Outcome.FRESHNESS_LOST;
        }
        if (candidate != Outcome.HANDED_OFF) {
            return candidate;
        }
        ReleaseBuffer.Pending handoff = buffer.offer(payload, withinBudget);
        if (handoff == null) {
            if (!fresh.getAsBoolean()) {
                return Outcome.FRESHNESS_LOST;
            }
            return withinBudget.getAsBoolean() ? Outcome.BUFFER_UNAVAILABLE : Outcome.BUDGET_EXCEEDED;
        }
        pending.set(handoff);
        return Outcome.HANDED_OFF;
    }

    private static boolean isScopedCreate(OperationReceipt receipt, AuthenticatedActor actor) {
        return receipt.action() == Action.CREATE && receipt.key().organization().equals(actor.organization())
                && receipt.key().issuer().equals(actor.issuer()) && receipt.key().principal().equals(actor.principal());
    }
}
