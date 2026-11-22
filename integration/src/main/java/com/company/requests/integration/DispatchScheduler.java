package com.company.requests.integration;

import com.company.requests.integration.DispatchCoordinator.Result;
import com.company.requests.integration.DispatchStore.Claim;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/** Polls a bounded claim batch and runs it on a separate fixed delivery pool. */
public final class DispatchScheduler implements AutoCloseable {
    public static final int WORKERS = 4;
    public static final int BATCH_SIZE = 8;
    public static final int DESTINATION_LIMIT = 2;
    public static final Duration LEASE = Duration.ofSeconds(15);
    public static final Duration IO_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration MIN_JITTER = Duration.ofSeconds(1);
    public static final Duration MAX_JITTER = Duration.ofSeconds(60);
    public static final int REPEAT_LIMIT = 5;
    public static final Duration REPEAT_WINDOW = Duration.ofMinutes(15);

    private final Supplier<List<Claim>> claimBatch;
    private final Function<Claim, Result> dispatch;
    private final ArrayDeque<Claim> pending = new ArrayDeque<>(BATCH_SIZE);
    private final Semaphore availableWorkers = new Semaphore(WORKERS);
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("outbox-scheduler").factory());
    private final Map<Result, LongAdder> outcomes = new EnumMap<>(Result.class);
    private final LongAdder failures = new LongAdder();
    private final AtomicInteger largestBatch = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS,
            Thread.ofPlatform().daemon().name("outbox-worker-", 1).factory());
    private boolean loading;

    public DispatchScheduler(Supplier<List<Claim>> claimBatch, Function<Claim, Result> dispatch) {
        this.claimBatch = Objects.requireNonNull(claimBatch);
        this.dispatch = Objects.requireNonNull(dispatch);
        for (var result : Result.values()) {
            outcomes.put(result, new LongAdder());
        }
        clock.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        for (int index = 0; index < WORKERS && availableWorkers.tryAcquire(); index++) {
            try {
                workers.execute(this::work);
            } catch (RejectedExecutionException stopped) {
                availableWorkers.release();
                return;
            }
        }
    }

    private void work() {
        try {
            var claim = next();
            if (claim != null) {
                outcomes.get(dispatch.apply(claim)).increment();
            }
        } catch (RuntimeException failure) {
            failures.increment();
        } finally {
            availableWorkers.release();
        }
    }

    private Claim next() {
        synchronized (pending) {
            if (!pending.isEmpty() || loading) {
                return pending.poll();
            }
            loading = true;
        }
        List<Claim> batch = List.of();
        try {
            batch = List.copyOf(claimBatch.get());
            if (batch.size() > BATCH_SIZE) {
                batch = List.of();
                throw new IllegalStateException("Dispatch store exceeded the claim batch limit.");
            }
            largestBatch.accumulateAndGet(batch.size(), Math::max);
        } finally {
            synchronized (pending) {
                pending.addAll(batch);
                loading = false;
            }
        }
        synchronized (pending) {
            return pending.poll();
        }
    }

    public Snapshot snapshot() {
        var counts = new EnumMap<Result, Long>(Result.class);
        outcomes.forEach((result, count) -> counts.put(result, count.sum()));
        return new Snapshot(Map.copyOf(counts), failures.sum(), WORKERS - availableWorkers.availablePermits(),
                largestBatch.get());
    }

    public record Snapshot(Map<Result, Long> outcomes, long failures, int busyWorkers, int largestBatch) { }

    @Override
    public void close() {
        clock.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                throw new IllegalStateException("Dispatch workers still hold an outstanding invocation.");
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing dispatch workers.");
        }
    }

}
