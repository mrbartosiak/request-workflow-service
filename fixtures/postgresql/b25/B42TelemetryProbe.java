package com.company.requests.bootstrap;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.READ;

/** Port-free regression for immutable, complete, monotonically published fixture telemetry. */
public final class B42TelemetryProbe {
    private static final int PUBLISHES = 64;
    private static final int READERS = 4;

    private B42TelemetryProbe() { }

    public static void main(String[] args) throws Exception {
        var control = Files.createDirectory(Path.of(args[0]));
        B42FixtureEvidence.publishState(control, 1, snapshot(1));
        Files.writeString(control.resolve("evidence-state-0000000000000000999.tmp"), "partial");
        var first = B42FixtureEvidence.stateFile(control, 1);
        try (var held = FileChannel.open(first, READ); var tasks = Executors.newFixedThreadPool(READERS)) {
            var ready = new CountDownLatch(READERS);
            var done = new AtomicBoolean();
            var readers = new ArrayList<Future<Integer>>();
            for (int reader = 0; reader < READERS; reader++) {
                readers.add(tasks.submit(() -> read(control, ready, done)));
            }
            try {
                if (!ready.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("B42 telemetry readers did not start.");
                }
                for (int generation = 2; generation <= PUBLISHES; generation++) {
                    B42FixtureEvidence.publishState(control, generation, snapshot(generation));
                }
            } finally {
                done.set(true);
            }
            for (var reader : readers) {
                if (reader.get(5, TimeUnit.SECONDS) < 1) {
                    throw new AssertionError("B42 telemetry reader did not observe any complete snapshot.");
                }
            }
            if (held.size() != snapshot(1).length() || !Files.readString(first).equals(snapshot(1))
                    || !B42FixtureEvidence.readState(control).equals(snapshot(PUBLISHES))) {
                throw new AssertionError("B42 did not keep the held generation and expose the latest complete one.");
            }
        }
        System.out.printf("PASS B42 telemetry %d immutable generations, %d readers, held prior snapshot and ignored temp.%n",
                PUBLISHES, READERS);
    }

    private static int read(Path control, CountDownLatch ready, AtomicBoolean done) throws Exception {
        int previous = 0;
        int observed = 0;
        do {
            String state = B42FixtureEvidence.readState(control);
            int generation = Integer.parseInt(state.split(" ", 2)[0]);
            if (generation < previous || !state.equals(snapshot(generation))) {
                throw new AssertionError("B42 telemetry was partial or went backwards.");
            }
            previous = generation;
            if (observed == 0) {
                ready.countDown();
            }
            observed++;
        } while (!done.get());
        return observed;
    }

    private static String snapshot(int generation) {
        return generation + " complete";
    }
}
