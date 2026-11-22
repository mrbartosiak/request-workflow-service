package com.company.requests.bootstrap;

import com.company.requests.application.evidence.RequestEvidenceRecorder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/** Owner-allocated local spool and cuttable sink for the private B42 native fixture. */
public final class B42FixtureEvidence implements AutoCloseable {
    public static final int MAIN_FRAMES = 64;
    public static final int EDGE_FRAMES = 3;
    public static final int DRAIN_LIMIT = 4;
    public static final String CUT = "evidence-sink-cut";
    public static final String SAFE_TRACE = "evidence-safe.ndjson";
    private static final Pattern STATE_FILE = Pattern.compile("evidence-state-[0-9]{19}\\.ready");

    private final Path control;
    private final ProtectedEvidenceSpool spool;
    private final ProtectedLocalEvidenceSink sink;
    private final RequestEvidenceRecorder recorder;
    private final ScheduledExecutorService drain;
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private boolean resumed;
    private int largestDrain;
    private long ingressOutcomes;
    private long sendOutcomes;
    private long generation;
    private String previous = "";

    private enum TelemetryOperation { DRAIN_AND_MEASURE, PUBLISH_STATE }

    public B42FixtureEvidence(Path root, Path control) throws Exception {
        this.control = control;
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path queue = Files.createDirectory(root.resolve("evidence-queue"));
        Path destination = Files.createDirectory(root.resolve("evidence-sink"));
        spool = new ProtectedEvidenceSpool(queue, key, (long) MAIN_FRAMES * ProtectedEvidenceFiles.FRAME_BYTES,
                (long) EDGE_FRAMES * ProtectedEvidenceFiles.FRAME_BYTES);
        try {
            sink = new ProtectedLocalEvidenceSink(destination, key);
        } catch (Exception failed) {
            spool.close();
            throw failed;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        recorder = new RequestEvidenceRecorder(Clock.systemUTC(), spool);
        drain = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("b42-private-drain").factory());
        drain.scheduleWithFixedDelay(this::tick, 0, 35, TimeUnit.MILLISECONDS);
    }

    public ProtectedEvidenceSpool spool() {
        return spool;
    }

    public RequestEvidenceRecorder recorder() {
        return recorder;
    }

    private void tick() {
        var operation = TelemetryOperation.DRAIN_AND_MEASURE;
        try {
            largestDrain = Math.max(largestDrain, spool.drain(this::acknowledge, DRAIN_LIMIT));
            var state = spool.snapshot();
            resumed |= state.resuming();
            String now = String.join(" ", Long.toString(state.mainBytes()), Long.toString(state.edgeBytes()),
                    Long.toString(state.reservedBytes()), Boolean.toString(state.alert()),
                    Boolean.toString(state.restricted()), Boolean.toString(resumed), Boolean.toString(state.stopRouting()),
                    Long.toString(state.ingressGaps()), Long.toString(state.outboundGaps()),
                    Long.toString(state.sinkFailures()), Integer.toString(spool.workerBatchLimit(8)),
                    Integer.toString(largestDrain), Boolean.toString(state.gapStateDurable()),
                    Long.toString(ingressOutcomes), Long.toString(sendOutcomes));
            if (!now.equals(previous)) {
                operation = TelemetryOperation.PUBLISH_STATE;
                publishState(control, ++generation, now);
                previous = now;
            }
        } catch (Exception error) {
            if (failure.compareAndSet(null, error)) {
                System.err.printf("B42 telemetry failure operation=%s exception=%s%n", operation,
                        error.getClass().getSimpleName());
            }
        }
    }

    static Path stateFile(Path control, long generation) {
        return control.resolve(String.format(Locale.ROOT, "evidence-state-%019d.ready", generation));
    }

    static void publishState(Path control, long generation, String snapshot) throws IOException {
        var target = stateFile(control, generation);
        if (Files.exists(target)) {
            throw new IOException("B42 telemetry generation already exists.");
        }
        // The sole writer moves onto a new name. Readers can safely keep any earlier generation open.
        B32Gate.atomic(target, snapshot);
    }

    public static String readState(Path control) throws IOException {
        Path latest;
        try (var entries = Files.list(control)) {
            latest = entries.filter(path -> STATE_FILE.matcher(path.getFileName().toString()).matches())
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IOException("B42 has not published a telemetry generation."));
        }
        return Files.readString(latest);
    }

    private boolean acknowledge(ProtectedEvidenceSpool.Delivery delivery) throws IOException {
        if (Files.exists(control.resolve(CUT)) || !sink.acknowledge(delivery)) {
            return false;
        }
        Files.writeString(control.resolve(SAFE_TRACE), String.join("\n", delivery.records()) + "\n", CREATE, APPEND);
        ingressOutcomes += delivery.records().stream().filter(record ->
                record.contains("\"direction\":\"INGRESS\"") && record.contains("\"phase\":\"OUTCOME\"")).count();
        sendOutcomes += delivery.records().stream().filter(record ->
                record.contains("\"operation\":\"VENDOR_SEND\"") && record.contains("\"phase\":\"OUTCOME\"")).count();
        return true;
    }

    @Override
    public void close() throws Exception {
        try (spool; sink) {
            drain.close();
            if (failure.get() != null) {
                throw new IllegalStateException("B42 fixture evidence telemetry failed.", failure.get());
            }
        }
    }
}
