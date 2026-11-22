import com.company.requests.bootstrap.B32Gate;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.DispatchStore.AttemptId;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owns one child JVM, its IPv4 loopback listener and its private stdin settings. */
final class B25App implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String READY = "READY http://" + HOST + ":";
    private final Process process;
    private final Path control;
    private final int port;
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();

    private B25App(Process process, Path control, int port) {
        this.process = process;
        this.control = control;
        this.port = port;
    }

    static B25App start(Path run, String name, B25Profile profile) throws Exception {
        var root = Files.createDirectory(run.resolve("b25-" + name));
        var control = Files.createDirectory(root.resolve("control"));
        var log = root.resolve("worker.log");
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                B25Worker.class.getName(), root.toString(), name).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            var input = process.outputWriter(StandardCharsets.UTF_8);
            for (var field : profile.fields(name)) {
                B25Postgresql.require(field.matches("[A-Za-z0-9_-]{1,128}"), "Invalid private process setting.");
                input.write(field);
                input.newLine();
            }
            input.flush();
            int port = ready(process, log);
            verifyListener(process.pid(), port, root);
            System.out.printf("STARTED b25-%s jvm=%d http=%s:%d%n", name, process.pid(), HOST, port);
            return new B25App(process, control, port);
        } catch (Exception | AssertionError failure) {
            try {
                stop(process);
            } catch (Exception | AssertionError closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    void enable() throws Exception {
        B32Gate.atomic(control.resolve("enable"), "enable");
    }

    Snapshot snapshot() {
        try {
            var path = control.resolve("snapshot");
            if (!Files.exists(path)) {
                return new Snapshot(0, 0, 0);
            }
            var fields = Files.readString(path).strip().split(" ");
            return new Snapshot(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Long.parseLong(fields[2]));
        } catch (java.io.IOException read) {
            throw new IllegalStateException("Could not read B25 worker snapshot.", read);
        }
    }

    record Snapshot(int connections, int largestBatch, long failures) { }

    String recover(AttemptId attempt) throws Exception {
        var key = attempt.effect();
        B32Gate.atomic(control.resolve("recover"), key.tenant().value() + " " + key.effectId() + " " + attempt.number());
        var result = control.resolve("recovered");
        B25Pressure.await(() -> Files.exists(result), "B25 child did not complete recovery.");
        return Files.readString(result);
    }

    int context(RequestRef ref, String bearer) throws Exception {
        var safeRef = URLEncoder.encode(ref.value(), StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port
                + "/requests/" + safeRef + "/action-context?action=publish"))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json, application/problem+json")
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        B25Postgresql.require(response.version() == HttpClient.Version.HTTP_1_1 && response.sslSession().isEmpty(),
                "B25 isolation probe did not use owned plaintext HTTP/1.1.");
        return response.statusCode();
    }

    long pid() {
        return process.pid();
    }

    int port() {
        return port;
    }

    @Override
    public void close() throws Exception {
        try {
            client.shutdownNow();
            B25Postgresql.require(client.awaitTermination(Duration.ofSeconds(3)), "HTTP client did not terminate.");
        } finally {
            stop(process);
            try (var socket = new Socket(Proxy.NO_PROXY)) {
                socket.connect(new InetSocketAddress(HOST, port), 1_000);
                throw new AssertionError("Owned HTTP listener remained reachable.");
            } catch (ConnectException expected) {
                System.out.printf("STOPPED b25 jvm=%d http=%s:%d refused connection%n", process.pid(), HOST, port);
            }
        }
    }

    private static int ready(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (var lines = Files.lines(log)) {
                var marker = lines.filter(line -> line.startsWith(READY)).findFirst();
                if (marker.isPresent()) {
                    return Integer.parseInt(marker.orElseThrow().substring(READY.length()));
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("B25 child loopback listener did not become ready.");
    }

    private static void verifyListener(long pid, int port, Path root) throws Exception {
        var inventory = root.resolve("socket-inventory.log");
        var command = new ProcessBuilder("netstat.exe", "-ano").redirectErrorStream(true)
                .redirectOutput(inventory.toFile()).start();
        try {
            command.getOutputStream().close();
            B25Postgresql.require(command.waitFor(5, TimeUnit.SECONDS) && command.exitValue() == 0,
                    "Socket inventory did not finish.");
            try (var lines = Files.lines(inventory, StandardCharsets.US_ASCII)) {
                var listeners = lines.map(String::strip).map(line -> line.split("\\s+"))
                        .filter(fields -> fields.length == 5 && fields[0].equals("TCP")
                                && fields[3].equals("LISTENING") && fields[4].equals(Long.toString(pid)))
                        .map(fields -> fields[1]).toList();
                B25Postgresql.require(listeners.equals(List.of(HOST + ":" + port)),
                        "B25 child must own exactly one IPv4 loopback listener.");
            }
        } finally {
            if (command.isAlive()) {
                command.destroyForcibly();
                command.waitFor(3, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(inventory);
        }
    }

    private static void stop(Process process) throws Exception {
        try {
            process.getOutputStream().close();
            B25Postgresql.require(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0,
                    "B25 child process did not stop cleanly.");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                B25Postgresql.require(process.waitFor(5, TimeUnit.SECONDS), "B25 child process is still running.");
            }
        }
    }
}
