package com.company.requests.bootstrap;

import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.DispatchCoordinator;
import com.company.requests.integration.DispatchScheduler;
import com.company.requests.integration.DispatchStore.*;
import com.company.requests.integration.OutboundEvidenceRecorder;
import com.company.requests.integration.OutboundEvidenceRecorder.Destination;
import com.company.requests.integration.SyntheticDelivery;
import com.company.requests.integration.SyntheticDelivery.*;
import com.company.requests.integration.TimedSyntheticDelivery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.company.requests.bootstrap.ProtectedEvidenceFiles.FRAME_BYTES;
import static com.company.requests.bootstrap.ProtectedEvidenceSpoolTest.*;
import static com.company.requests.integration.DispatchCoordinator.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedFakeDeliveryTest {
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 1);
    private static final Map<String, Destination> ALIASES = Map.of(
            "sensitive-raw-alpha", Destination.FAKE_ALPHA, "sensitive-raw-beta", Destination.FAKE_BETA);
    private static final UUID ENDPOINT = UUID.randomUUID();

    @TempDir
    Path directory;

    @Test
    void actualFakeSendsRequireAnArmAndReconcileExactObservedOutcomesWithDurableResults() throws Exception {
        try (var spool = open(directory)) {
            var store = new LocalStore(spool);
            var sends = new AtomicInteger();
            var effects = new AtomicInteger();
            var fake = connector(invocation -> {
                assertTrue(store.armCommitted);
                assertEquals((2 * sends.get() + 1L) * FRAME_BYTES, spool.snapshot().mainBytes());
                assertEquals(FRAME_BYTES, spool.snapshot().reservedBytes());
                int index = sends.getAndIncrement();
                return switch (index) {
                    case 0 -> {
                        effects.incrementAndGet();
                        yield Optional.of(confirmed(invocation));
                    }
                    case 1 -> Optional.of(new Evidence(invocation, ENDPOINT, UUID.randomUUID(), Observation.TRANSIENT,
                            Exclusion.SINGLE_EFFECT, Optional.empty()));
                    case 2 -> Optional.of(new Evidence(invocation, ENDPOINT, UUID.randomUUID(), Observation.ACCEPTED,
                            Exclusion.NONE, Optional.empty()));
                    default -> throw new IllegalStateException("sensitive-vendor-failure");
                };
            });
            assertEquals(CONFIRMED, dispatch(spool, store, fake, claim(1, "sensitive-raw-alpha")));
            assertEquals(RETRYABLE, dispatch(spool, store, fake, claim(2, "sensitive-raw-beta")));
            assertEquals(ACCEPTED_PENDING, dispatch(spool, store, fake, claim(3, "sensitive-raw-alpha")));
            assertEquals(UNKNOWN, dispatch(spool, store, fake, claim(4, "sensitive-raw-beta")));
            assertEquals(DEFERRED, dispatch(spool, store, fake, claim(5, "sensitive-raw-alpha")));
            assertEquals(1, store.deferred);
            assertEquals(4, store.arms);
            assertEquals(4, store.finalizations);
            assertEquals(4, sends.get());
            assertEquals(1, effects.get());
            assertEquals(0, spool.snapshot().outboundGaps());

            var frames = new ArrayList<ProtectedEvidenceSpool.Delivery>();
            assertEquals(8, spool.drain(frames::add, 8));
            var logs = records(frames);
            assertEquals(10, logs.size());
            assertEquals(4, count(logs, "\"phase\":\"START\""));
            assertEquals(4, count(logs, "\"phase\":\"OUTCOME\""));
            assertEquals(2, count(logs, "\"phase\":\"ERROR\""));
            assertEquals(1, count(logs, "\"outcome\":\"DELIVERY_CONFIRMED\""));
            assertEquals(1, count(logs, "\"outcome\":\"DELIVERY_ACCEPTED\""));
            assertEquals(2, count(logs, "\"outcome\":\"DELIVERY_REJECTED\""));
            assertEquals(2, count(logs, "\"outcome\":\"DELIVERY_FAILURE\""));
            assertEquals(4, count(logs, "\"destination\":\"FAKE_ALPHA\""));
            assertEquals(6, count(logs, "\"destination\":\"FAKE_BETA\""));
            assertEquals(0, count(logs, "sensitive-"));
            assertEquals(0, count(logs, "a".repeat(64)));
            assertEquals(0, count(logs, ENDPOINT.toString()));
        }
    }

    @Test
    void aWithheldArmReleasesTheFullReservationAndProducesNoFakeSend() throws Exception {
        try (var spool = open(directory)) {
            var store = new LocalStore(spool);
            var fake = connector(invocation -> fail("Withheld arm reached the fake send."));
            for (var state : List.of(ArmState.DEFERRED, ArmState.SUPPRESSED, ArmState.STALE)) {
                store.armState = state;
                assertEquals(DispatchCoordinator.Result.valueOf(state.name()),
                        dispatch(spool, store, fake, claim(1, "sensitive-raw-alpha")));
                assertEquals(0, spool.snapshot().reservedBytes());
                assertEquals(0, spool.snapshot().mainBytes());
            }
            assertEquals(3, store.arms);
            assertEquals(0, store.finalizations);
            assertEquals(0, spool.snapshot().outboundGaps());
        }
    }

    @Test
    void timeoutIsDurableWhileTheActualFakeSendIsStillHoldingItsSlot() throws Exception {
        try (var spool = open(directory); var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            var resultReady = new CountDownLatch(1);
            var store = new LocalStore(spool);
            var fake = connector(invocation -> {
                entered.countDown();
                for (;;) {
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) {
                            throw new AssertionError("Fixture did not release the outstanding send.");
                        }
                        return Optional.of(confirmed(invocation));
                    } catch (InterruptedException timeout) {
                        cancelled.countDown();
                    }
                }
            });
            long started = System.nanoTime();
            var result = tasks.submit(() -> {
                try (var timed = new TimedSyntheticDelivery(fake)) {
                    var outcome = coordinator(spool, store, timed).dispatch(claim(1, "sensitive-raw-alpha"));
                    resultReady.countDown();
                    return outcome;
                }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                assertTrue(resultReady.await(5, TimeUnit.SECONDS));
                assertTrue(System.nanoTime() - started >= DispatchScheduler.IO_TIMEOUT.toNanos());
                assertFalse(result.isDone(), "The delivery slot must outlive the timed wait.");
                assertEquals(1, store.finalizations);
                assertTrue(store.report.isEmpty());
                var frames = new ArrayList<ProtectedEvidenceSpool.Delivery>();
                assertEquals(2, spool.drain(frames::add, 2));
                assertEquals(2, count(records(frames), "\"outcome\":\"DELIVERY_TIMEOUT\""));
                assertEquals(0, count(records(frames), "\"outcome\":\"DELIVERY_CONFIRMED\""));
            } finally {
                release.countDown();
            }
            assertEquals(UNKNOWN, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, store.finalizations);
        }
    }

    @Test
    void lostOutboundTerminalEvidenceHoldsTheDurableResultUnknownAndStopsRouting() throws Exception {
        try (var spool = open(directory)) {
            var store = new LocalStore(spool);
            var effects = new AtomicInteger();
            var connector = connector(invocation -> {
                effects.incrementAndGet();
                try {
                    Files.createDirectory(directory.resolve("0000000000000000002.frame.tmp"));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return Optional.of(confirmed(invocation));
            });
            assertEquals(UNKNOWN, dispatch(spool, store, connector, claim(1, "sensitive-raw-alpha")));
            assertEquals(1, effects.get());
            assertEquals(1, store.finalizations);
            assertTrue(spool.snapshot().stopRouting());
            assertEquals(1, spool.snapshot().outboundGaps());
            assertEquals(DEFERRED, dispatch(spool, store, connector, claim(2, "sensitive-raw-alpha")));
            assertEquals(1, effects.get());
        }
    }

    private static DispatchCoordinator coordinator(ProtectedEvidenceSpool spool, LocalStore store,
                                                   TimedSyntheticDelivery timed) {
        return new DispatchCoordinator(store, WRITER, timed, new OutboundEvidenceRecorder(recorder(spool)), ALIASES);
    }

    private static DispatchCoordinator.Result dispatch(ProtectedEvidenceSpool spool, LocalStore store,
                                                        Connector connector, Claim claim) {
        try (var timed = new TimedSyntheticDelivery(connector)) {
            return coordinator(spool, store, timed).dispatch(claim);
        }
    }

    private static Claim claim(long index, String destination) {
        return new Claim(new AttemptId(new EffectKey(destination, new OrganizationId("sensitive-tenant"),
                "a".repeat(64)), index), UUID.randomUUID(), WRITER);
    }

    private static Evidence confirmed(Invocation invocation) {
        return new Evidence(invocation, ENDPOINT, UUID.randomUUID(), Observation.CONFIRMED, Exclusion.NONE,
                Optional.of(new Effect(UUID.randomUUID(), invocation.id().number())));
    }

    private static Connector connector(Function<Invocation, Optional<Evidence>> sends) {
        return new Connector() {
            @Override
            public Optional<Evidence> send(Invocation invocation) {
                return sends.apply(invocation);
            }

            @Override
            public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
                throw new AssertionError("A send unexpectedly did a lookup.");
            }
        };
    }

    private static final class LocalStore implements Store {
        private final ProtectedEvidenceSpool spool;
        private ArmState armState = ArmState.ARMED;
        private boolean armCommitted;
        private int arms;
        private int deferred;
        private int finalizations;
        private Optional<Evidence> report;

        private LocalStore(ProtectedEvidenceSpool spool) {
            this.spool = spool;
        }

        @Override
        public Optional<Claim> claim(WriterEpoch writer) {
            throw new AssertionError("The fixture uses already claimed invocations.");
        }

        @Override
        public ArmResult arm(Claim claim) {
            assertEquals(2L * FRAME_BYTES, spool.snapshot().reservedBytes());
            arms++;
            armCommitted = armState == ArmState.ARMED;
            return new ArmResult(armState, armCommitted ? Optional.of(new Invocation(claim.id(),
                    new RequestRef("B".repeat(32)), 4)) : Optional.empty());
        }

        @Override
        public boolean defer(Claim claim) {
            deferred++;
            return true;
        }

        @Override
        public Optional<Disposition> finishDelivery(Claim claim, Optional<Evidence> evidence) {
            assertTrue(armCommitted);
            finalizations++;
            report = evidence;
            return Optional.of(SyntheticDelivery.sent(evidence,
                    evidence.map(Evidence::exclusion).orElse(Exclusion.NONE)));
        }

        @Override
        public boolean finish(Claim claim, Optional<Receipt> receipt) {
            throw new AssertionError("The synthetic endpoint qualifies durable delivery evidence.");
        }

        @Override
        public Optional<Recovery> startRecovery(WriterEpoch writer,
                                                com.company.requests.application.AuthenticatedActor actor,
                                                AttemptId attempt, Action action) {
            throw new AssertionError("The fixture does not exercise recovery.");
        }

        @Override
        public boolean finishRecovery(Recovery recovery, Optional<Evidence> evidence) {
            throw new AssertionError("The fixture does not exercise recovery.");
        }
    }
}
