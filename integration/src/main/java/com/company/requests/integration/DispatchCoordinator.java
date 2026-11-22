package com.company.requests.integration;

import com.company.requests.application.WriterEpoch;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.DispatchStore.Receipt;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/** Drives the durable local dispatch protocol for a configured synthetic destination. */
public final class DispatchCoordinator {
    public enum Result {
        IDLE, DEFERRED, SUPPRESSED, STALE, CONFIRMED, UNKNOWN, ACCEPTED_PENDING, RETRYABLE,
        FAILED_PERMANENT, QUARANTINED
    }

    @FunctionalInterface
    public interface SyntheticRecorder {
        Receipt record(Invocation invocation);
    }

    private final DispatchStore store;
    private final WriterEpoch writer;
    private final BiFunction<Claim, Invocation, Result> delivery;

    public DispatchCoordinator(DispatchStore store, WriterEpoch writer, SyntheticRecorder recorder) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        Objects.requireNonNull(recorder);
        delivery = (claim, invocation) -> record(store, recorder, claim, invocation);
    }

    public DispatchCoordinator(SyntheticDelivery.Store store, WriterEpoch writer, SyntheticDelivery.Connector connector) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        Objects.requireNonNull(connector);
        delivery = (claim, invocation) -> send(store, connector, claim, invocation);
    }

    public Result dispatchNext() {
        return store.claim(writer).map(this::dispatch).orElse(Result.IDLE);
    }

    /** Resume an exact claim issued to this writer. The store still fences stolen or stale claims. */
    public Result dispatch(Claim claim) {
        if (!writer.equals(claim.writer())) {
            return Result.STALE;
        }
        var arm = store.arm(claim);
        return switch (arm.state()) {
            case DEFERRED -> Result.DEFERRED;
            case SUPPRESSED -> Result.SUPPRESSED;
            case STALE -> Result.STALE;
            case ARMED -> invoke(claim, arm.invocation().orElseThrow());
        };
    }

    private Result invoke(Claim claim, Invocation invocation) {
        if (!claim.id().equals(invocation.id())) {
            throw new IllegalStateException("Armed invocation does not match the claim.");
        }
        return delivery.apply(claim, invocation);
    }

    private static Result record(DispatchStore store, SyntheticRecorder recorder, Claim claim, Invocation invocation) {
        Optional<Receipt> receipt;
        try {
            receipt = Optional.ofNullable(recorder.record(invocation)).filter(value -> value.id().equals(claim.id()));
        } catch (RuntimeException ambiguous) {
            receipt = Optional.empty();
        }
        if (!store.finish(claim, receipt)) {
            return Result.STALE;
        }
        return receipt.isPresent() ? Result.CONFIRMED : Result.UNKNOWN;
    }

    private static Result send(SyntheticDelivery.Store store, SyntheticDelivery.Connector connector, Claim claim,
                               Invocation invocation) {
        SyntheticDelivery.SendResult report;
        try {
            var delivery = connector.sendWithThrottle(invocation);
            report = new SyntheticDelivery.SendResult(SyntheticDelivery.exact(invocation, delivery.evidence()),
                    delivery.retryAfter());
        } catch (RuntimeException ambiguous) {
            report = SyntheticDelivery.SendResult.unknown();
        }
        return store.finishDelivery(claim, report)
                .map(disposition -> Result.valueOf(disposition.name())).orElse(Result.STALE);
    }
}
