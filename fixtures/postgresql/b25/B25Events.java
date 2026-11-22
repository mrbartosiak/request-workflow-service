import com.company.requests.adapter.integration.jdbc.JdbcSyntheticConnector;
import com.company.requests.adapter.integration.jdbc.JdbcSyntheticConnector.Send;
import com.company.requests.bootstrap.B32Gate;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.SendResult;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates delayed fake arrivals and their observations across the two owned child JVMs. */
final class B25Events {
    private final Path root;

    B25Events(Path root) {
        this.root = root;
    }

    Connector connector(String process, JdbcSyntheticConnector endpoint) {
        return new Connector() {
            @Override
            public Optional<Evidence> send(Invocation invocation) {
                return sendWithThrottle(invocation).evidence();
            }

            @Override
            public SendResult sendWithThrottle(Invocation invocation) {
                return observedSend(process, endpoint, invocation);
            }

            @Override
            public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
                return endpoint.lookup(invocation, fence);
            }
        };
    }

    private SendResult observedSend(String process, JdbcSyntheticConnector endpoint, Invocation invocation) {
        B25Postgresql.require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "Workflow transaction leaked into connector I/O.");
        var destination = invocation.id().effect().destination();
        var id = UUID.randomUUID();
        long began = System.nanoTime();
        var arrival = new Event(process, destination, id, "begin", 0);
        long ordinal = append(arrival);
        try {
            if (destination.equals(B25Postgresql.SLOW) || destination.equals(B25Postgresql.OUTAGE)) {
                awaitRelease(arrival, began);
            }
            if (destination.equals(B25Postgresql.THROTTLE) && ordinal == 1) {
                return endpoint.throttle(invocation, Duration.ofSeconds(8));
            }
            var scenario = switch (destination) {
                case B25Postgresql.OUTAGE, B25Postgresql.UNSAFE -> Send.LOSE_REPLY_BEFORE_APPLY;
                case B25Postgresql.RETRY -> ordinal <= 6 ? Send.THROTTLE : Send.APPLY;
                default -> Send.APPLY;
            };
            return new SendResult(endpoint.send(invocation, scenario), Optional.empty());
        } finally {
            append(arrival.phase("end", elapsed(began)));
        }
    }

    private void awaitRelease(Event arrival, long began) {
        while (!Files.exists(root.resolve("release"))) {
            B25Postgresql.require(elapsed(began) < 30_000, "Native fake-arrival gate did not release.");
            try {
                Thread.sleep(20);
            } catch (InterruptedException deadline) {
                append(arrival.phase("deadline", elapsed(began)));
            }
        }
    }

    void release() throws IOException {
        B32Gate.atomic(root.resolve("release"), "release");
    }

    List<Event> read() {
        return locked(root, events -> events);
    }

    private long append(Event event) {
        return locked(root, events -> {
            try {
                Files.writeString(root.resolve("events"), event.format() + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("Fixture trace write failed.", failure);
            }
            return 1 + events.stream().filter(previous -> previous.destination().equals(event.destination())
                    && previous.phase().equals("begin")).count();
        });
    }

    private static synchronized <T> T locked(Path root, Function<List<Event>, T> read) {
        try (var channel = FileChannel.open(root.resolve("events.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var file = root.resolve("events");
            var events = Files.exists(file) ? Files.readAllLines(file).stream().map(Event::parse).toList()
                    : List.<Event>of();
            return read.apply(events);
        } catch (IOException failure) {
            throw new IllegalStateException("Fixture trace unavailable.", failure);
        }
    }

    static long elapsed(long began) {
        return Duration.ofNanos(System.nanoTime() - began).toMillis();
    }

    record Event(String process, String destination, UUID call, String phase, long millis) {
        Event phase(String next, long duration) {
            return new Event(process, destination, call, next, duration);
        }

        String format() {
            return String.join(" ", process, destination, call.toString(), phase, Long.toString(millis));
        }

        static Event parse(String line) {
            var fields = line.split(" ");
            return new Event(fields[0], fields[1], UUID.fromString(fields[2]), fields[3], Long.parseLong(fields[4]));
        }
    }

    int highest(String destination) {
        var active = new HashSet<UUID>();
        int highest = 0;
        for (var event : read()) {
            if (!event.destination().equals(destination)) {
                continue;
            }
            if (event.phase().equals("begin")) {
                active.add(event.call());
                highest = Math.max(highest, active.size());
            } else if (event.phase().equals("end")) {
                active.remove(event.call());
            }
        }
        return highest;
    }
}
