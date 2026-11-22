package com.company.requests.integration;

import com.company.requests.application.WriterEpoch;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.DispatchStore.Receipt;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    @FunctionalInterface
    private interface Delivery {
        Result send(Claim claim, Invocation invocation, OutboundEvidenceRecorder.Reservation reservation);
    }

    private final Delivery delivery;
    private final OutboundEvidenceRecorder evidence;
    private final Map<String, OutboundEvidenceRecorder.Destination> aliases;

    public DispatchCoordinator(DispatchStore store, WriterEpoch writer, SyntheticRecorder recorder) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        Objects.requireNonNull(recorder);
        delivery = (claim, invocation, reservation) -> record(store, recorder, claim, invocation);
        evidence = null;
        aliases = Map.of();
    }

    public DispatchCoordinator(SyntheticDelivery.Store store, WriterEpoch writer, SyntheticDelivery.Connector connector) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        Objects.requireNonNull(connector);
        delivery = (claim, invocation, reservation) -> send(store, claim, invocation, connector::sendWithThrottle);
        evidence = null;
        aliases = Map.of();
    }

    public DispatchCoordinator(SyntheticDelivery.Store store, WriterEpoch writer, TimedSyntheticDelivery connector,
                               OutboundEvidenceRecorder evidence,
                               Map<String, OutboundEvidenceRecorder.Destination> aliases) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        Objects.requireNonNull(connector);
        this.evidence = Objects.requireNonNull(evidence);
        this.aliases = Map.copyOf(aliases);
        delivery = (claim, invocation, reservation) -> send(store, claim, invocation,
                call -> connector.sendWithThrottle(call, reservation));
    }

    public Result dispatchNext() {
        if (evidence != null && !evidence.available()) {
            return Result.DEFERRED;
        }
        return store.claim(writer).map(this::dispatch).orElse(Result.IDLE);
    }

    /** Resume an exact claim issued to this writer. The store still fences stolen or stale claims. */
    public Result dispatch(Claim claim) {
        if (!writer.equals(claim.writer())) {
            return Result.STALE;
        }
        if (evidence == null) {
            return arm(claim, null);
        }
        var alias = aliases.get(claim.id().effect().destination());
        try (var reservation = alias == null ? null : evidence.reserve(alias).orElse(null)) {
            return reservation == null ? (store.defer(claim) ? Result.DEFERRED : Result.STALE)
                    : arm(claim, reservation);
        }
    }

    private Result arm(Claim claim, OutboundEvidenceRecorder.Reservation reservation) {
        var arm = store.arm(claim);
        return switch (arm.state()) {
            case DEFERRED -> Result.DEFERRED;
            case SUPPRESSED -> Result.SUPPRESSED;
            case STALE -> Result.STALE;
            case ARMED -> invoke(claim, arm.invocation().orElseThrow(), reservation);
        };
    }

    private Result invoke(Claim claim, Invocation invocation, OutboundEvidenceRecorder.Reservation reservation) {
        if (!claim.id().equals(invocation.id())) {
            throw new IllegalStateException("Armed invocation does not match the claim.");
        }
        return delivery.send(claim, invocation, reservation);
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

    private static Result send(SyntheticDelivery.Store store, Claim claim, Invocation invocation,
                               java.util.function.Function<Invocation, SyntheticDelivery.SendResult> connector) {
        SyntheticDelivery.SendResult report;
        try {
            var delivery = connector.apply(invocation);
            report = new SyntheticDelivery.SendResult(SyntheticDelivery.exact(invocation, delivery.evidence()),
                    delivery.retryAfter());
        } catch (RuntimeException ambiguous) {
            report = SyntheticDelivery.SendResult.unknown();
        }
        return store.finishDelivery(claim, report)
                .map(disposition -> Result.valueOf(disposition.name())).orElse(Result.STALE);
    }
}
