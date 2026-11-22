import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.DispatchCoordinator.Result;
import com.company.requests.integration.DispatchStore;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** Run unchanged against the exact predecessor and the candidate class paths. */
public final class B25PortFree {
    public static void main(String[] args) throws Exception {
        var scheduler = "com.company.requests.integration.DispatchScheduler";
        Class<?> type;
        try {
            type = Class.forName(scheduler);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("B25 scheduler cannot start four independent workers or enforce batch and time limits.",
                    missing);
        }
        require(type.getField("WORKERS").getInt(null) == 4, "four dispatch workers");
        require(type.getField("BATCH_SIZE").getInt(null) == 8, "eight claims per batch");
        require(type.getField("DESTINATION_LIMIT").getInt(null) == 2, "two shared destination slots");
        require(value(type, "LEASE") == 15, "15-second claim lease");
        require(value(type, "IO_TIMEOUT") == 2, "two-second I/O deadline");
        require(value(type, "MIN_JITTER") == 1 && value(type, "MAX_JITTER") == 60, "one-to-60-second jitter");
        require(type.getField("REPEAT_LIMIT").getInt(null) == 5 && value(type, "REPEAT_WINDOW") == 900,
                "five repeat arms per effect in 15 minutes");
        require(DispatchStore.class.getMethod("claim", WriterEpoch.class) != null, "legacy claim entry point");
        workers(type);
        deadline();
        System.out.println("PASS B25 limits, eight unique claims on four workers, and timeout with retained invocation.");
    }

    private static void workers(Class<?> scheduler) throws Exception {
        List<Claim> attempts = IntStream.range(0, 8).mapToObj(B25PortFree::claim).toList();
        var queued = new AtomicBoolean();
        var active = new AtomicInteger();
        var largest = new AtomicInteger();
        var started = new CountDownLatch(4);
        var completed = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var ids = ConcurrentHashMap.<AttemptId>newKeySet();
        var threads = ConcurrentHashMap.<String>newKeySet();
        Supplier<List<Claim>> claims = () -> queued.compareAndSet(false, true) ? attempts : List.of();
        Function<Claim, Result> perform = claim -> {
            threads.add(Thread.currentThread().getName());
            if (!ids.add(claim.id())) {
                throw new IllegalStateException("Repeated claim in scheduler.");
            }
            largest.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            await(release);
            active.decrementAndGet();
            completed.countDown();
            return Result.CONFIRMED;
        };
        var worker = (AutoCloseable) scheduler.getConstructor(Supplier.class, Function.class)
                .newInstance(claims, perform);
        try (worker) {
            try {
                require(started.await(5, TimeUnit.SECONDS), "four workers did not overlap");
                require(ids.size() == 4 && largest.get() == 4, "extra work ran while four workers were occupied");
            } finally {
                release.countDown();
            }
            require(completed.await(5, TimeUnit.SECONDS), "batch did not drain after worker resumption");
            require(ids.size() == 8 && threads.size() == 4, "batch or worker ceiling changed during resumption");
            require(threads.stream().allMatch(name -> name.startsWith("outbox-worker-")),
                    "scheduler itself executed the delivery");
        }
    }

    private static void deadline() throws Exception {
        var invoked = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        Connector unresponsive = new Connector() {
            @Override
            public Optional<Evidence> send(Invocation invocation) {
                invoked.countDown();
                for (;;) {
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("fixture did not release connector");
                        }
                        returned.countDown();
                        return Optional.empty();
                    } catch (InterruptedException deadline) {
                        cancelled.countDown();
                    }
                }
            }

            @Override
            public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
                throw new AssertionError("send must not resolve remote ambiguity");
            }
        };
        var type = Class.forName("com.company.requests.integration.TimedSyntheticDelivery");
        var bounded = (Connector) type.getConstructor(Connector.class).newInstance(unresponsive);
        var request = new Invocation(claim(1).id(), new RequestRef("b25-fixture"), 4);
        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                var result = worker.submit(() -> {
                    long start = System.nanoTime();
                    require(bounded.send(request).isEmpty(), "deadline returned remote proof");
                    return Duration.ofNanos(System.nanoTime() - start);
                });
                require(invoked.await(5, TimeUnit.SECONDS), "connector did not start");
                var elapsed = result.get(5, TimeUnit.SECONDS);
                require(elapsed.toMillis() >= 1_900 && elapsed.toMillis() <= 3_500,
                        "connector wait was not bounded at two seconds: " + elapsed);
                require(cancelled.await(1, TimeUnit.SECONDS) && returned.getCount() == 1,
                        "timeout did not leave the actual old call outstanding");
                var cleanup = worker.submit(() -> {
                    ((AutoCloseable) bounded).close();
                    return true;
                });
                require(!cleanup.isDone() && returned.getCount() == 1, "invocation slot left before old call exit");
                release.countDown();
                require(cleanup.get(5, TimeUnit.SECONDS) && returned.getCount() == 0, "old call did not drain");
                System.out.println("PASS B25 connector deadline observed in " + elapsed.toMillis() + " ms.");
            } finally {
                release.countDown();
            }
        }
    }

    private static Claim claim(int ordinal) {
        var effect = new EffectKey("fixture-b25", new OrganizationId("b25-fixture"), "%064x".formatted(ordinal));
        return new Claim(new AttemptId(effect, 1), UUID.randomUUID(), new WriterEpoch(UUID.randomUUID(), 1));
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(5, TimeUnit.SECONDS), "worker fixture gate did not release");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new AssertionError("worker fixture interrupted");
        }
    }

    private static long value(Class<?> type, String field) throws Exception {
        return ((java.time.Duration) type.getField(field).get(null)).toSeconds();
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
