package com.company.requests.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** One single-use, nonblocking handoff slot. Capacity includes the entire prepared envelope. */
public final class ReleaseBuffer {
    public static final int MAX_BYTES = 16_384;
    private final int capacity;
    private final AtomicReference<Slot> contents = new AtomicReference<>();

    public ReleaseBuffer(int capacity) {
        this(capacity, MAX_BYTES);
    }

    static ReleaseBuffer actionContext() {
        // A 16 KiB body can require six output bytes per input byte when JSON escapes controls.
        int capacity = 128 * 1024;
        return new ReleaseBuffer(capacity, capacity);
    }

    private ReleaseBuffer(int capacity, int limit) {
        if (capacity < 1 || capacity > limit) {
            throw new IllegalArgumentException("Release capacity is outside the allowed range.");
        }
        this.capacity = capacity;
    }

    boolean canOffer(byte[] payload) {
        return payload.length > 0 && payload.length <= capacity && contents.get() == null;
    }

    Pending offer(byte[] payload, BooleanSupplier withinBudget) {
        if (!canOffer(Objects.requireNonNull(payload, "release payload"))) {
            return null;
        }
        var pending = new Pending(payload.clone());
        return withinBudget.getAsBoolean() && contents.compareAndSet(null, pending) ? pending : null;
    }

    void complete(Pending handoff) {
        if (!contents.compareAndSet(handoff, new Ready(handoff.payload()))) {
            throw new IllegalStateException("The pending release handoff was lost.");
        }
    }

    void discard(Pending handoff) {
        contents.compareAndSet(handoff, Consumed.SLOT);
    }

    /** Drain once after a successful guarded return. Pending or aborted handoffs expose no bytes. */
    public Optional<byte[]> take() {
        if (!(contents.get() instanceof Ready ready) || !contents.compareAndSet(ready, Consumed.SLOT)) {
            return Optional.empty();
        }
        return Optional.of(ready.payload());
    }

    private sealed interface Slot permits Pending, Ready, Consumed { }

    record Pending(byte[] payload) implements Slot { }

    private record Ready(byte[] payload) implements Slot { }

    private enum Consumed implements Slot { SLOT }
}
