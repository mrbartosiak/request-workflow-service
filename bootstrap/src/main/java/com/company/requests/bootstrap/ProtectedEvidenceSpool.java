package com.company.requests.bootstrap;

import com.company.requests.application.evidence.RequestEvidenceEvent;
import com.company.requests.application.evidence.RequestEvidenceEvent.Direction;
import com.company.requests.application.evidence.RequestEvidenceStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static com.company.requests.bootstrap.ProtectedEvidenceFiles.FRAME_BYTES;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Single-owner, encrypted evidence queue for an explicitly opted-in local synthetic instance. */
public final class ProtectedEvidenceSpool implements RequestEvidenceStorage, AutoCloseable {
    public static final long REFERENCE_BYTES = 128L * 1024 * 1024;
    public static final long LOCAL_EDGE_BYTES = 4L * 1024 * 1024;
    public static final int MAX_DRAIN_FRAMES = 32;
    private static final int ATTEMPT_BYTES = 2 * FRAME_BYTES;
    private static final int LOCAL_INGRESS_IN_FLIGHT = 64;
    private static final int LOCAL_EGRESS_IN_FLIGHT = 4;
    private static final String JOURNAL = "gap.state";
    private static final String TAIL = "tail.state";

    public enum Part { START, FINISH, EDGE }

    /** A sink must durably and idempotently accept this identity before acknowledging it. */
    public record Delivery(long sequence, UUID correlationId, Part part, List<String> records) {
        public Delivery {
            records = List.copyOf(records);
        }
    }

    @FunctionalInterface
    public interface Sink {
        boolean acknowledge(Delivery delivery) throws IOException;
    }

    public record Snapshot(long mainBytes, long reservedBytes, long edgeBytes, boolean alert, boolean restricted,
                           boolean resuming, boolean stopRouting, long ingressGaps, long outboundGaps,
                           boolean gapStateDurable, long sinkFailures, long spoolFailures) { }

    private final Path root;
    private final ProtectedEvidenceFiles files;
    private final FileChannel lockChannel;
    private final FileLock ownership;
    private final long alertAt;
    private final long restrictAt;
    private final long edgeLimit;
    private final NavigableMap<Long, Frame> pending = new TreeMap<>();
    private final HashMap<UUID, Direction> activeStarts = new HashMap<>();
    private final Set<UUID> recordedOpenGaps = new HashSet<>();
    private final EnumMap<Direction, Integer> inFlight = new EnumMap<>(Direction.class);
    private final ReentrantLock drainLock = new ReentrantLock();
    private long next = 1;
    private long mainUsed;
    private long reserved;
    private long edgeUsed;
    private long ingressGaps;
    private long outboundGaps;
    private long sinkFailures;
    private long spoolFailures;
    private boolean restricted;
    private boolean resuming;
    private boolean stopped;
    private boolean gapStateDurable = true;
    private boolean closed;

    public static ProtectedEvidenceSpool open(Path root, byte[] key) throws IOException {
        return new ProtectedEvidenceSpool(root, key, REFERENCE_BYTES, LOCAL_EDGE_BYTES);
    }

    // Scaled budgets exercise the same frame accounting and pressure transitions in offline tests.
    ProtectedEvidenceSpool(Path root, byte[] key, long mainLimit, long edgeLimit) throws IOException {
        if (mainLimit < 10L * FRAME_BYTES || mainLimit > REFERENCE_BYTES || mainLimit % FRAME_BYTES != 0
                || edgeLimit < FRAME_BYTES || edgeLimit > LOCAL_EDGE_BYTES || edgeLimit % FRAME_BYTES != 0) {
            throw new IllegalArgumentException("Invalid local protected evidence capacity.");
        }
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw new IOException("A private local evidence directory must already exist.");
        }
        this.root = root.toRealPath();
        files = new ProtectedEvidenceFiles(this.root, key);
        alertAt = mainLimit * 60 / 100;
        restrictAt = mainLimit * 80 / 100;
        this.edgeLimit = edgeLimit;
        for (var direction : Direction.values()) {
            inFlight.put(direction, 0);
        }
        lockChannel = FileChannel.open(this.root.resolve("owner.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
        try {
            ownership = lockChannel.tryLock();
            if (ownership == null) {
                throw new IOException("The protected evidence directory already has an owner.");
            }
            recover(mainLimit);
        } catch (OverlappingFileLockException failure) {
            lockChannel.close();
            throw new IOException("The protected evidence directory already has an owner.");
        } catch (IOException | RuntimeException | Error failure) {
            try {
                lockChannel.close();
            } catch (IOException release) {
                failure.addSuppressed(release);
            }
            throw failure;
        }
    }

    private void recover(long mainLimit) throws IOException {
        readGapState();
        readTail();
        try (var listing = Files.list(root)) {
            for (var path : listing.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.equals("owner.lock") || name.equals(JOURNAL) || name.equals(TAIL)) {
                    continue;
                }
                if (name.equals(JOURNAL + ".tmp") || name.equals(TAIL + ".tmp")
                        || name.matches("[0-9]{19}\\.frame\\.tmp")) {
                    Files.delete(path);
                    continue;
                }
                if (!name.matches("[0-9]{19}\\.frame")) {
                    throw new IOException("Unrecognized protected evidence directory content.");
                }
                recoverFrame(name);
            }
        }
        if (mainUsed > mainLimit || edgeUsed > edgeLimit) {
            throw new IOException("Stored evidence exceeds the configured local capacity.");
        }
        // A restart with a high backlog requires a successful drain through the alert threshold.
        restricted = mainUsed >= alertAt;
        resuming = mainUsed > 0 && !restricted;
        if (!activeStarts.isEmpty()) {
            activeStarts.forEach((id, direction) -> countGap(direction, id));
            if (persistGapState()) {
                activeStarts.clear();
            }
        }
    }

    private void recoverFrame(String name) throws IOException {
        long sequence = Long.parseLong(name.substring(0, 19));
        var frame = decode(sequence, files.read(name));
        next = Math.max(next, Math.incrementExact(sequence));
        pending.put(sequence, frame);
        if (frame.delivery.part() == Part.EDGE) {
            edgeUsed += FRAME_BYTES;
            return;
        }
        mainUsed += FRAME_BYTES;
        if (frame.delivery.part() == Part.START) {
            activeStarts.put(frame.delivery.correlationId(), frame.direction);
        } else {
            activeStarts.remove(frame.delivery.correlationId());
        }
    }

    private void readGapState() throws IOException {
        if (Files.exists(root.resolve(JOURNAL), NOFOLLOW_LINKS)) {
            var bytes = files.read(JOURNAL);
            if (bytes.length < 2 * Long.BYTES || bytes.length % (2 * Long.BYTES) != 0
                    || bytes.length > (1 + LOCAL_INGRESS_IN_FLIGHT + LOCAL_EGRESS_IN_FLIGHT) * 2 * Long.BYTES) {
                throw new IOException("Invalid protected evidence gap state.");
            }
            var counts = ByteBuffer.wrap(bytes);
            ingressGaps = counts.getLong();
            outboundGaps = counts.getLong();
            while (counts.hasRemaining()) {
                recordedOpenGaps.add(new UUID(counts.getLong(), counts.getLong()));
            }
            stopped = true;
        }
    }

    private void readTail() throws IOException {
        if (Files.exists(root.resolve(TAIL), NOFOLLOW_LINKS)) {
            var bytes = files.read(TAIL);
            if (bytes.length != Long.BYTES || (next = ByteBuffer.wrap(bytes).getLong()) < 1) {
                throw new IOException("Invalid protected evidence tail state.");
            }
        }
    }

    @Override
    public synchronized boolean available(Direction direction) {
        Objects.requireNonNull(direction);
        int concurrency = resuming ? 1
                : direction == Direction.INGRESS ? LOCAL_INGRESS_IN_FLIGHT : LOCAL_EGRESS_IN_FLIGHT;
        return !closed && !stopped && !restricted && projected() + ATTEMPT_BYTES <= restrictAt
                && inFlight.get(direction) < concurrency;
    }

    /** Keeps scheduled claims narrow during the first drain interval after a restriction. */
    public synchronized int workerBatchLimit(int normalLimit) {
        if (!available(Direction.EGRESS)) {
            return 0;
        }
        return resuming ? 1 : normalLimit;
    }

    @Override
    public synchronized Optional<Reservation> reserve(Direction direction) {
        if (!available(direction)) {
            return Optional.empty();
        }
        reserved += ATTEMPT_BYTES;
        inFlight.compute(direction, (ignored, count) -> count + 1);
        if (projected() + ATTEMPT_BYTES > restrictAt) {
            restricted = true;
            resuming = false;
        }
        return Optional.of(new Slot(direction));
    }

    @Override
    public synchronized boolean edge(List<RequestEvidenceEvent> events) {
        if (closed) {
            return false;
        }
        if (edgeUsed + FRAME_BYTES > edgeLimit) {
            gap(Direction.INGRESS, null);
            return false;
        }
        try {
            append(Direction.INGRESS, Part.EDGE, events);
            edgeUsed += FRAME_BYTES;
            return true;
        } catch (IOException failure) {
            spoolFailures++;
            gap(Direction.INGRESS, null);
            return false;
        }
    }

    private void append(Direction direction, Part part, List<RequestEvidenceEvent> events) throws IOException {
        var frame = new Frame(direction, new Delivery(next, events.getFirst().correlationId(), part,
                events.stream().map(RequestEvidenceEvent::json).toList()));
        files.write(name(next), encode(frame));
        pending.put(next, frame);
        next = Math.incrementExact(next);
    }

    /**
     * Drains in write order, one acknowledged frame at a time. An in-flight start remains recoverable
     * until its finish is durable. A sink call owns no admission lock.
     */
    public int drain(Sink sink, int maximum) {
        Objects.requireNonNull(sink);
        if (maximum < 1 || maximum > MAX_DRAIN_FRAMES) {
            throw new IllegalArgumentException("Invalid protected evidence drain budget.");
        }
        if (!drainLock.tryLock()) {
            return 0;
        }
        try {
            int acknowledged = 0;
            while (acknowledged < maximum) {
                var candidate = drainHead();
                if (candidate == null || !acknowledge(sink, candidate.getValue().delivery)
                        || !remove(candidate.getKey(), candidate.getValue())) {
                    break;
                }
                acknowledged++;
            }
            if (acknowledged > 0) {
                afterDrain();
            }
            return acknowledged;
        } finally {
            drainLock.unlock();
        }
    }

    private synchronized Map.Entry<Long, Frame> drainHead() {
        if (!closed && !gapStateDurable && persistGapState()) {
            activeStarts.keySet().removeAll(recordedOpenGaps);
        }
        var head = pending.firstEntry();
        if (closed || head == null || head.getValue().delivery.part() == Part.START
                && activeStarts.containsKey(head.getValue().delivery.correlationId())) {
            return null;
        }
        return head;
    }

    private boolean acknowledge(Sink sink, Delivery delivery) {
        try {
            if (sink.acknowledge(delivery)) {
                return true;
            }
        } catch (IOException | RuntimeException unavailable) {
            // Sink diagnostics can contain connection details. Only the safe count leaves this boundary.
        }
        synchronized (this) {
            sinkFailures++;
        }
        return false;
    }

    private synchronized boolean remove(long sequence, Frame frame) {
        try {
            if (pending.size() == 1) {
                files.write(TAIL, ByteBuffer.allocate(Long.BYTES).putLong(next).array());
            }
            Files.delete(root.resolve(name(sequence)));
        } catch (IOException failure) {
            spoolFailures++;
            return false; // A repeated delivery has the same idempotency identity.
        }
        pending.remove(sequence);
        if (frame.delivery.part() == Part.EDGE) {
            edgeUsed -= FRAME_BYTES;
        } else {
            mainUsed -= FRAME_BYTES;
        }
        return true;
    }

    private synchronized void afterDrain() {
        if (restricted && projected() <= alertAt) {
            restricted = false;
            resuming = projected() > 0;
        } else if (resuming) {
            resuming = false;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mainUsed, reserved, edgeUsed, projected() >= alertAt, restricted, resuming, stopped,
                ingressGaps, outboundGaps, gapStateDurable, sinkFailures, spoolFailures);
    }

    private long projected() {
        return mainUsed + reserved;
    }

    private void gap(Direction direction, UUID start) {
        countGap(direction, start);
        persistGapState();
    }

    private void countGap(Direction direction, UUID start) {
        stopped = true;
        if (start != null && !recordedOpenGaps.add(start)) {
            return;
        }
        if (direction == Direction.INGRESS) {
            ingressGaps++;
        } else {
            outboundGaps++;
        }
    }

    private boolean persistGapState() {
        var state = ByteBuffer.allocate((1 + recordedOpenGaps.size()) * 2 * Long.BYTES)
                .putLong(ingressGaps).putLong(outboundGaps);
        for (UUID id : recordedOpenGaps) {
            state.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        }
        try {
            files.write(JOURNAL, state.array());
            gapStateDurable = true;
        } catch (IOException failure) {
            spoolFailures++;
            gapStateDurable = false;
        }
        return gapStateDurable;
    }

    @Override
    public void close() throws IOException {
        if (!drainLock.tryLock()) {
            throw new IllegalStateException("A protected evidence drain is still active.");
        }
        try {
            synchronized (this) {
                if (!closed) {
                    if (reserved != 0 || inFlight.values().stream().anyMatch(count -> count != 0)) {
                        throw new IllegalStateException("Protected evidence work is still active.");
                    }
                    closed = true;
                    try (lockChannel) {
                        ownership.close();
                    }
                }
            }
        } finally {
            drainLock.unlock();
        }
    }

    private static String name(long sequence) {
        return String.format(Locale.ROOT, "%019d.frame", sequence);
    }

    private static byte[] encode(Frame frame) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new DataOutputStream(bytes)) {
            stream.writeByte(frame.direction.ordinal());
            stream.writeByte(frame.delivery.part().ordinal());
            stream.writeLong(frame.delivery.correlationId().getMostSignificantBits());
            stream.writeLong(frame.delivery.correlationId().getLeastSignificantBits());
            stream.writeByte(frame.delivery.records().size());
            for (var record : frame.delivery.records()) {
                stream.writeUTF(record);
            }
        }
        return bytes.toByteArray();
    }

    private static Frame decode(long sequence, byte[] bytes) throws IOException {
        try (var stream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var direction = Direction.values()[stream.readUnsignedByte()];
            var part = Part.values()[stream.readUnsignedByte()];
            var id = new UUID(stream.readLong(), stream.readLong());
            int count = stream.readUnsignedByte();
            if (count < 1 || count > 3) {
                throw new IOException("Invalid protected evidence record count.");
            }
            var records = new ArrayList<String>(count);
            for (int index = 0; index < count; index++) {
                records.add(stream.readUTF());
            }
            if (stream.read() != -1) {
                throw new IOException("Unexpected protected evidence frame data.");
            }
            return new Frame(direction, new Delivery(sequence, id, part, records));
        } catch (IndexOutOfBoundsException failure) {
            throw new IOException("Invalid protected evidence frame catalogue.");
        }
    }

    private record Frame(Direction direction, Delivery delivery) { }

    private final class Slot implements Reservation {
        private final Direction direction;
        private UUID started;
        private int claimed = ATTEMPT_BYTES;
        private boolean done;

        private Slot(Direction direction) {
            this.direction = direction;
        }

        @Override
        public void start(RequestEvidenceEvent event) {
            synchronized (ProtectedEvidenceSpool.this) {
                if (started != null || done || closed || stopped) {
                    throw new Unavailable();
                }
                store(Part.START, List.of(event));
                started = event.correlationId();
                activeStarts.put(started, direction);
            }
        }

        @Override
        public void finish(List<RequestEvidenceEvent> events) {
            synchronized (ProtectedEvidenceSpool.this) {
                if (started == null || done || closed) {
                    throw new Unavailable();
                }
                store(Part.FINISH, events);
                activeStarts.remove(started);
                done = true;
            }
        }

        private void store(Part part, List<RequestEvidenceEvent> events) {
            try {
                append(direction, part, events);
                claimed -= FRAME_BYTES;
                reserved -= FRAME_BYTES;
                mainUsed += FRAME_BYTES;
            } catch (IOException failure) {
                spoolFailures++;
                if (part == Part.FINISH) {
                    stopStartedAttempt();
                } else {
                    // No work has started. The edge lane can still retain an ingress rejection.
                    stopped = true;
                    persistGapState();
                }
                throw new Unavailable();
            }
        }

        private void stopStartedAttempt() {
            gap(direction, started);
            if (gapStateDurable) {
                activeStarts.remove(started);
            }
            done = true;
        }

        @Override
        public void close() {
            synchronized (ProtectedEvidenceSpool.this) {
                if (claimed < 0) {
                    return;
                }
                if (started != null && !done) {
                    stopStartedAttempt();
                }
                inFlight.compute(direction, (ignored, count) -> count - 1);
                reserved -= claimed;
                claimed = -1;
                done = true;
                if (restricted && mainUsed == 0 && projected() <= alertAt) {
                    restricted = false;
                }
            }
        }
    }
}
