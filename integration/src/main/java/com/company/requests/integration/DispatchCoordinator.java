package com.company.requests.integration;

import com.company.requests.application.WriterEpoch;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.DispatchStore.Receipt;
import java.util.Objects;
import java.util.Optional;

/** Drives the durable local dispatch protocol for a trusted synthetic recorder. */
public final class DispatchCoordinator {
    public enum Result { IDLE, DEFERRED, SUPPRESSED, STALE, CONFIRMED, UNKNOWN }

    @FunctionalInterface
    public interface SyntheticRecorder {
        Receipt record(Invocation invocation);
    }

    private final DispatchStore store;
    private final WriterEpoch writer;
    private final SyntheticRecorder recorder;

    public DispatchCoordinator(DispatchStore store, WriterEpoch writer, SyntheticRecorder recorder) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        this.recorder = Objects.requireNonNull(recorder);
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
}
