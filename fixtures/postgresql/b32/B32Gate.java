package com.company.requests.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/** One-shot rendezvous for an owned fixture process, with a deadline on both sides. */
public final class B32Gate {
    public enum Point { BEFORE_CLAIM, AFTER_CLAIM, BEFORE_COMMIT, BEFORE_RELEASE }

    public record Cut(Point point, String id, int holdMillis) {
        public Cut {
            if (!id.matches("[a-z0-9-]{1,40}") || holdMillis < 1 || holdMillis > 1800) {
                throw new IllegalArgumentException("Invalid B32 cut.");
            }
        }
    }

    public record Event(int backend, Instant at, String outcome) {
        static Event parse(String event) {
            String[] fields = event.split("\t");
            return new Event(Integer.parseInt(fields[0]), Instant.parse(fields[1]), fields[2]);
        }

        String line() {
            return backend + "\t" + at + "\t" + outcome;
        }
    }

    private final Path directory;
    private final ConcurrentHashMap<Cut, Boolean> taken = new ConcurrentHashMap<>();

    public B32Gate(Path directory) {
        this.directory = directory;
    }

    public void arm(Cut cut) throws IOException {
        Files.writeString(file(cut, "armed"), "", StandardOpenOption.CREATE_NEW);
        atomic(directory.resolve(cut.point() + ".current"), cut.id() + "\t" + cut.holdMillis());
    }

    public void release(Cut cut) throws IOException {
        Files.writeString(file(cut, "release"), "", StandardOpenOption.CREATE_NEW);
    }

    public Event await(Cut cut, String phase, Duration wait) throws Exception {
        if (!phase.equals("entered") && !phase.equals("finished")) {
            throw new IllegalArgumentException("Unknown B32 event phase.");
        }
        long deadline = System.nanoTime() + wait.toNanos();
        Path event = file(cut, phase);
        while (System.nanoTime() < deadline) {
            if (Files.exists(event)) {
                return Event.parse(Files.readString(event));
            }
            Thread.sleep(3);
        }
        throw new TimeoutException("B32 gate event missing: " + cut.point() + " " + phase);
    }

    void hit(Point point, int backend) throws SQLException {
        Path active = directory.resolve(point + ".current");
        try {
            if (!Files.exists(active)) {
                return;
            }
            String[] fields = Files.readString(active).split("\t");
            var cut = new Cut(point, fields[0], Integer.parseInt(fields[1]));
            if (taken.putIfAbsent(cut, true) != null) {
                return;
            }
            long deadline = System.nanoTime() + Duration.ofMillis(cut.holdMillis()).toNanos();
            event(cut, "entered", backend, "entered");
            while (System.nanoTime() < deadline) {
                if (Files.exists(file(cut, "release"))) {
                    event(cut, "finished", backend, "released");
                    return;
                }
                Thread.sleep(3);
            }
            event(cut, "finished", backend, "timed-out");
            throw new SQLException("B32 fixture barrier timed out.", "XX000");
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SQLException("B32 fixture barrier failed.", "XX000", failure);
        }
    }

    private void event(Cut cut, String phase, int backend, String outcome) throws IOException {
        atomic(file(cut, phase), new Event(backend, Instant.now(), outcome).line());
    }

    private Path file(Cut cut, String phase) {
        return directory.resolve(cut.point() + "-" + cut.id() + "." + phase);
    }

    public static void atomic(Path file, String value) throws IOException {
        Path writing = Files.createTempFile(file.getParent(), "b32-write-", ".tmp");
        try {
            Files.writeString(writing, value);
            Files.move(writing, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(writing);
        }
    }
}
