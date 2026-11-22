package com.company.requests.bootstrap;

import com.company.requests.adapter.http.RequestEvidenceFilter;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.application.evidence.RequestEvidenceEvent.*;
import static com.company.requests.bootstrap.ProtectedEvidenceFiles.FRAME_BYTES;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedEvidenceSpoolTest {
    static final byte[] TEST_KEY = new byte[32];
    private static final long MAIN = 10L * FRAME_BYTES;
    private static final long EDGE = 2L * FRAME_BYTES;
    private static final JsonMapper JSON = new JsonMapper();

    @TempDir
    Path root;

    @Test
    void keepsIngressOutcomesAndEdgeRejectsWithinTheirSeparateBudgetsAndLatchesTheGap() throws Exception {
        assertEquals(128L * 1024 * 1024, ProtectedEvidenceSpool.REFERENCE_BYTES);
        assertEquals(4L * 1024 * 1024, ProtectedEvidenceSpool.LOCAL_EDGE_BYTES);
        var effects = new AtomicInteger();
        try (var spool = open(root)) {
            var recorder = recorder(spool);
            assertEquals(201, request(recorder, effects).getStatus());
            assertEquals(201, request(recorder, effects).getStatus());
            assertFalse(spool.snapshot().alert());
            assertEquals(0, spool.drain(delivery -> false, 2));
            assertEquals(1, spool.snapshot().sinkFailures());
            assertEquals(201, request(recorder, effects).getStatus());
            assertTrue(spool.snapshot().alert());
            assertFalse(spool.snapshot().restricted());
            assertEquals(201, request(recorder, effects).getStatus());
            assertTrue(spool.snapshot().restricted());
            assertEquals(0, spool.workerBatchLimit(8));
            assertEquals(8L * FRAME_BYTES, spool.snapshot().mainBytes());
            assertEquals(0, spool.snapshot().reservedBytes());

            var restricted = request(recorder, effects);
            assertEquals(503, restricted.getStatus());
            assertEquals("no-store", restricted.getHeader("Cache-Control"));
            var problem = JSON.readTree(restricted.getContentAsString());
            assertEquals("urn:requests:problem:evidence_unavailable", problem.get("type").asText());
            assertDoesNotThrow(() -> UUID.fromString(problem.get("correlationId").asText()));
            recorder.recordUndispatchedIngress(80, 408);
            assertEquals(EDGE, spool.snapshot().edgeBytes());
            assertEquals(503, request(recorder, effects).getStatus());
            assertEquals(4, effects.get());
            assertTrue(spool.snapshot().stopRouting());
            assertEquals(1, spool.snapshot().ingressGaps());
            assertTrue(spool.snapshot().gapStateDurable());

            try (var files = Files.list(root)) {
                var frames = files.filter(path -> path.toString().endsWith(".frame")).toList();
                assertEquals(10, frames.size());
                for (var frame : frames) {
                    assertEquals(FRAME_BYTES, Files.size(frame));
                    var ciphertext = Files.readString(frame, ISO_8859_1);
                    assertFalse(ciphertext.contains("correlation_id"));
                    assertFalse(ciphertext.contains("sensitive-"));
                }
            }
        }
        try (var spool = open(root)) {
            var sink = new ArrayList<ProtectedEvidenceSpool.Delivery>();
            assertEquals(3, spool.drain(sink::add, 3));
            assertEquals(3, spool.drain(sink::add, 3));
            assertEquals(3, spool.drain(sink::add, 3));
            assertEquals(1, spool.drain(sink::add, 3));
            assertEquals(0, spool.drain(sink::add, 3));
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), sequences(sink));
            var records = records(sink);
            assertEquals(14, records.size());
            assertEquals(6, count(records, "\"phase\":\"START\""));
            assertEquals(6, count(records, "\"phase\":\"OUTCOME\""));
            assertEquals(2, count(records, "\"phase\":\"ERROR\""));
            assertEquals(2, count(records, "\"outcome\":\"EVIDENCE_UNAVAILABLE\""));
            assertEquals(2, count(records, "\"outcome\":\"TRANSPORT_TIMEOUT\""));
            assertEquals(0, count(records, "sensitive-"));
            assertTrue(spool.snapshot().stopRouting());
            assertEquals(1, spool.snapshot().ingressGaps());
            assertEquals(0, spool.workerBatchLimit(8));
            assertEquals(503, request(recorder(spool), effects).getStatus());
            assertEquals(4, effects.get());
            assertEquals(11, drainOne(spool).sequence());
        }
    }

    @Test
    void restrictedPostAndHeadKeepTheSafeProblemContractUnderANonLatinLocale() throws Exception {
        var effects = new AtomicInteger();
        var original = Locale.getDefault(Locale.Category.FORMAT);
        try (var spool = open(root)) {
            var recorder = recorder(spool);
            for (int request = 0; request < 4; request++) {
                assertEquals(201, request(recorder, effects).getStatus());
            }
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
            var post = request(recorder, effects);
            assertEquals(503, post.getStatus());
            assertEquals("application/problem+json", post.getContentType());
            assertEquals("no-store", post.getHeader("Cache-Control"));
            var problem = JSON.readTree(post.getContentAsString());
            assertEquals(503, problem.get("status").asInt());
            assertEquals("urn:requests:problem:evidence_unavailable", problem.get("type").asText());
            String correlation = problem.get("correlationId").asText();
            assertEquals("urn:uuid:" + UUID.fromString(correlation), problem.get("instance").asText());

            var head = new MockHttpServletResponse();
            new RequestEvidenceFilter(recorder).doFilter(new MockHttpServletRequest("HEAD", "/requests"), head,
                    (request, response) -> fail("Restricted HEAD dispatched."));
            assertEquals(503, head.getStatus());
            assertEquals("application/problem+json", head.getContentType());
            assertEquals("no-store", head.getHeader("Cache-Control"));
            assertEquals(0, head.getContentAsByteArray().length);
            assertEquals(4, effects.get());
            var frames = new ArrayList<ProtectedEvidenceSpool.Delivery>();
            assertEquals(10, spool.drain(frames::add, 10));
            assertEquals(correlation, frames.get(8).correlationId().toString());
            assertEquals(3, count(frames.get(9).records(), "\"operation\":\"BROWSE_REQUESTS\""));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void acknowledgesInWriteOrderAcrossAnAckCutAndResumesInLimitedWaves() throws Exception {
        Path queue = Files.createDirectory(root.resolve("queue"));
        Path target = Files.createDirectory(root.resolve("target"));
        var deliveries = new ArrayList<ProtectedEvidenceSpool.Delivery>();
        try (var spool = open(queue); var sink = new ProtectedLocalEvidenceSink(target, TEST_KEY)) {
            var recorder = recorder(spool);
            var first = start(recorder);
            var second = start(recorder);
            second.finish(Outcome.COMPLETED, 201);
            assertEquals(0, spool.drain(sink, 1));
            first.finish(Outcome.COMPLETED, 201);
            start(recorder).finish(Outcome.COMPLETED, 201);
            start(recorder).finish(Outcome.COMPLETED, 201);
            assertTrue(spool.snapshot().restricted());
            assertThrows(IOException.class, () -> open(queue));
            assertEquals(0, spool.drain(delivery -> {
                deliveries.add(delivery);
                assertTrue(sink.acknowledge(delivery));
                throw new IOException("sensitive-synthetic-sink-diagnostic");
            }, 2));
            assertEquals(1, spool.snapshot().sinkFailures());
            assertEquals(8L * FRAME_BYTES, spool.snapshot().mainBytes());
        }
        try (var spool = open(queue); var sink = new ProtectedLocalEvidenceSink(target, TEST_KEY)) {
            assertTrue(spool.snapshot().restricted());
            assertEquals(2, spool.drain(delivery -> {
                deliveries.add(delivery);
                return sink.acknowledge(delivery);
            }, 2));
            assertEquals(List.of(1L, 1L, 2L), sequences(deliveries));
            assertEquals(deliveries.get(0), deliveries.get(1));
            assertTrue(spool.snapshot().resuming());
            assertEquals(1, spool.workerBatchLimit(8));
            assertEquals(2, spool.drain(sink, 2));
            assertFalse(spool.snapshot().resuming());
            assertEquals(8, spool.workerBatchLimit(8));
            assertEquals(4, spool.drain(sink, 4));
            assertEquals(8, fileCount(target, ".record"));
        }
        try (var spool = open(queue); var sink = new ProtectedLocalEvidenceSink(target, TEST_KEY)) {
            start(recorder(spool)).finish(Outcome.INVALID_INPUT, 400);
            assertEquals(2, spool.drain(sink, 2));
            assertEquals(10, fileCount(target, ".record"));
            try (var files = Files.list(target)) {
                assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("0000000000000000009-"))
                        .count());
            }
        }
    }

    @Test
    void aBlockedSinkDoesNotHoldAdmissionOrEnableASecondDrainer() throws Exception {
        try (var spool = open(root); var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var recorder = recorder(spool);
            start(recorder).finish(Outcome.COMPLETED, 201);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cut = tasks.submit(() -> spool.drain(delivery -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
                return false;
            }, 1));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var ingress = tasks.submit(() -> request(recorder, new AtomicInteger()).getStatus());
                assertEquals(201, ingress.get(5, TimeUnit.SECONDS));
                assertEquals(0, spool.drain(delivery -> fail("A second drain reached its sink"), 1));
            } finally {
                release.countDown();
            }
            assertEquals(0, cut.get(5, TimeUnit.SECONDS));
            assertEquals(4, spool.drain(delivery -> true, 4));
        }
    }

    @Test
    void interruptedProcessNeverInventsAnOutcomeAndKeepsOneDurableGapAcrossRestarts() throws Exception {
        var java = ProcessHandle.current().info().command().orElseThrow();
        var classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var child = new ProcessBuilder(java, "-cp", classPath, AbandonedEvidenceProbe.class.getName(),
                root.toString()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getErrorStream().readAllBytes(), ISO_8859_1));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
        try (var spool = open(root)) {
            assertTrue(spool.snapshot().stopRouting());
            assertEquals(1, spool.snapshot().ingressGaps());
            assertTrue(spool.snapshot().gapStateDurable());
        }
        try (var spool = open(root)) {
            assertEquals(1, spool.snapshot().ingressGaps());
            var evidence = drainOne(spool);
            assertEquals(ProtectedEvidenceSpool.Part.START, evidence.part());
            assertEquals(1, evidence.records().size());
            assertEquals(0, spool.drain(delivery -> fail("Invented a terminal event"), 1));
        }
        try (var spool = open(root)) {
            assertEquals(1, spool.snapshot().ingressGaps());
            assertTrue(spool.snapshot().stopRouting());
        }
    }

    @Test
    void unauthenticStoragePreventsRestartAndNeverTouchesTheExistingFrame() throws Exception {
        try (var spool = open(root)) {
            start(recorder(spool)).finish(Outcome.COMPLETED, 201);
        }
        var wrongKey = TEST_KEY.clone();
        wrongKey[0] = 1;
        assertThrows(IOException.class, () -> new ProtectedEvidenceSpool(root, wrongKey, MAIN, EDGE));
        var file = root.resolve("0000000000000000001.frame");
        var original = Files.readAllBytes(file);
        byte[] changed = original.clone();
        changed[32] ^= 1;
        Files.write(file, changed);
        assertThrows(IOException.class, () -> open(root));
        assertArrayEquals(changed, Files.readAllBytes(file));
        Files.write(file, original);
        try (var spool = open(root)) {
            assertEquals(2, spool.drain(delivery -> true, 2));
        }
    }

    @Test
    void aFailedStartRecordsOnlyTheIndependentRejectionAndAFailedFinishKeepsTheEffectAndTheGap() throws Exception {
        var effects = new AtomicInteger();
        Path initial = Files.createDirectory(root.resolve("initial"));
        try (var spool = open(initial)) {
            Files.createDirectory(initial.resolve("0000000000000000001.frame.tmp"));
            assertEquals(503, request(recorder(spool), effects).getStatus());
            assertEquals(0, effects.get());
            assertTrue(spool.snapshot().stopRouting());
            assertEquals(0, spool.snapshot().ingressGaps());
            assertEquals(1, spool.snapshot().spoolFailures());
            assertEquals(ProtectedEvidenceSpool.Part.EDGE, drainOne(spool).part());
        }
        Path finishing = Files.createDirectory(root.resolve("finishing"));
        try (var spool = open(finishing)) {
            var filter = new RequestEvidenceFilter(recorder(spool));
            var response = new MockHttpServletResponse();
            filter.doFilter(mockRequest(), response, (request, result) -> {
                effects.incrementAndGet();
                Files.createDirectory(finishing.resolve("0000000000000000002.frame.tmp"));
                response.setStatus(201);
            });
            assertEquals(1, effects.get());
            assertEquals(503, response.getStatus());
            assertEquals("urn:requests:problem:outcome_unknown",
                    JSON.readTree(response.getContentAsString()).get("type").asText());
            assertTrue(spool.snapshot().stopRouting());
            assertEquals(1, spool.snapshot().ingressGaps());
            assertEquals(ProtectedEvidenceSpool.Part.START, drainOne(spool).part());
        }
    }

    private static List<Long> sequences(List<ProtectedEvidenceSpool.Delivery> deliveries) {
        return deliveries.stream().map(ProtectedEvidenceSpool.Delivery::sequence).toList();
    }

    static List<String> records(List<ProtectedEvidenceSpool.Delivery> deliveries) {
        return deliveries.stream().flatMap(delivery -> delivery.records().stream()).toList();
    }

    static long count(List<String> records, String pattern) {
        return records.stream().filter(record -> record.contains(pattern)).count();
    }

    private static long fileCount(Path directory, String suffix) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static ProtectedEvidenceSpool.Delivery drainOne(ProtectedEvidenceSpool spool) {
        var deliveries = new ArrayList<ProtectedEvidenceSpool.Delivery>();
        assertEquals(1, spool.drain(deliveries::add, 1));
        return deliveries.getFirst();
    }

    private static MockHttpServletRequest mockRequest() {
        var request = new MockHttpServletRequest("POST", "/requests");
        request.setQueryString("sensitive-target");
        request.addHeader("Authorization", "sensitive-bearer");
        request.addHeader("Idempotency-Key", "sensitive-operation-key");
        request.setContent("sensitive-body".getBytes(ISO_8859_1));
        return request;
    }

    private static MockHttpServletResponse request(RequestEvidenceRecorder recorder, AtomicInteger effects)
            throws Exception {
        var response = new MockHttpServletResponse();
        new RequestEvidenceFilter(recorder).doFilter(mockRequest(), response, (request, result) -> {
            effects.incrementAndGet();
            response.setStatus(201);
        });
        return response;
    }

    static ProtectedEvidenceSpool open(Path root) throws IOException {
        return new ProtectedEvidenceSpool(root, TEST_KEY, MAIN, EDGE);
    }

    static RequestEvidenceRecorder recorder(ProtectedEvidenceSpool spool) {
        return new RequestEvidenceRecorder(Clock.systemUTC(), spool);
    }

    static RequestEvidenceRecorder.Attempt start(RequestEvidenceRecorder recorder) {
        return recorder.start(Direction.INGRESS, Operation.CREATE_REQUEST, Destination.REQUESTS_API);
    }
}
