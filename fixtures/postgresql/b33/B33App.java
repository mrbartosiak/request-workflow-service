import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.net.http.HttpRequest.BodyPublishers;

/** Owned child process and raw loopback client for the B33 effective profile. */
final class B33App implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String READY = "READY http://" + HOST + ":";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
    static final JsonMapper JSON = new JsonMapper();
    private final Path controls;
    private final Path log;
    private final Process process;
    final String name;
    final int port;
    final B32Gate gates;
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(HTTP_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build();

    private B33App(Path controls, Path log, Process process, String name, int port) {
        this.controls = controls;
        this.log = log;
        this.process = process;
        this.name = name;
        this.port = port;
        gates = new B32Gate(controls);
    }

    static B33App start(Path run, String name, B33Profile.Child child, Instant clock) throws Exception {
        Path root = Files.createDirectory(run.resolve(name));
        Path controls = Files.createDirectory(root.resolve("control"));
        B32Gate.atomic(controls.resolve("trusted-clock"), clock.toString());
        Path log = root.resolve("worker.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                B33Worker.class.getName(), root.toString(), name).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            var input = process.outputWriter(StandardCharsets.UTF_8);
            for (String value : child.fields()) {
                input.write(value);
                input.newLine();
            }
            input.flush();
            int port = ready(process, log);
            verifyListener(process, port, root.resolve("socket-inventory.log"));
            System.out.printf("STARTED %s jvm=%d http=%s:%d at=%s%n", name, process.pid(), HOST, port, Instant.now());
            return new B33App(controls, log, process, name, port);
        } catch (Exception | AssertionError failure) {
            try {
                stop(process);
            } catch (Exception | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    long pid() {
        return process.pid();
    }

    boolean alive() {
        return process.isAlive();
    }

    void setClock(Instant clock) throws Exception {
        B32Gate.atomic(controls.resolve("trusted-clock"), clock.toString());
    }

    B32Database identity(Store store) throws Exception {
        return B32Database.load(controls.resolve(file(store, "identity")));
    }

    List<Integer> pool(Store store) throws Exception {
        return Arrays.stream(Files.readString(controls.resolve(file(store, "pool"))).split(" "))
                .map(Integer::valueOf).toList();
    }

    private static String file(Store store, String suffix) {
        return store.name().toLowerCase(Locale.ROOT) + "." + suffix;
    }

    List<Trace> traces() throws Exception {
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.startsWith("B33_JDBC ")).map(Trace::parse).toList();
        }
    }

    record Trace(String operation, int backend, Instant at, String sqlstate) {
        static Trace parse(String line) {
            String[] fields = line.split(" ");
            return new Trace(fields[1], Integer.parseInt(fields[2]), Instant.parse(fields[3]), fields[5]);
        }
    }

    CompletableFuture<Reply> send(String method, String path, String bearer, String key, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port + path))
                .timeout(HTTP_TIMEOUT).header("Accept", "application/json, application/problem+json");
        if (!bearer.isEmpty()) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (!key.isEmpty()) {
            request.header("Idempotency-Key", key);
        }
        if (body.isEmpty()) {
            request.method(method, BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        }
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    B33Evidence.require(response.version() == HttpClient.Version.HTTP_1_1
                            && response.sslSession().isEmpty(), "Expected plaintext HTTP/1.1 on loopback.");
                    return new Reply(response.statusCode(), response.headers().map(), response.body());
                });
    }

    static Reply completed(CompletableFuture<Reply> reply) throws Exception {
        return reply.get(9, TimeUnit.SECONDS);
    }

    record Reply(int status, Map<String, List<String>> headers, byte[] body) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        String header(String name) {
            return String.join(", ", headers.getOrDefault(name, List.of()));
        }

        Map<String, List<String>> stableHeaders() {
            var copy = new TreeMap<>(headers);
            copy.remove("date");
            return copy;
        }
    }

    @Override
    public void close() throws Exception {
        try {
            client.shutdownNow();
            B33Evidence.require(client.awaitTermination(Duration.ofSeconds(3)), "B33 HTTP client did not terminate.");
        } finally {
            stop(process);
            try (var socket = new Socket(Proxy.NO_PROXY)) {
                socket.connect(new InetSocketAddress(HOST, port), 1000);
                throw new AssertionError("B33 HTTP listener survived shutdown.");
            } catch (ConnectException expected) {
                System.out.printf("STOPPED %s jvm=%d http=%s:%d refused connection at=%s%n",
                        name, process.pid(), HOST, port, Instant.now());
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
            Thread.sleep(20);
        }
        throw new AssertionError("B33 application did not become ready. Inspect its worker log.");
    }

    private static void verifyListener(Process worker, int port, Path log) throws Exception {
        var sockets = new ProcessBuilder("netstat.exe", "-ano").redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            sockets.getOutputStream().close();
            B33Evidence.require(sockets.waitFor(5, TimeUnit.SECONDS) && sockets.exitValue() == 0,
                    "B33 listener inventory did not finish.");
            try (var lines = Files.lines(log, StandardCharsets.US_ASCII)) {
                var listeners = lines.map(String::strip).map(line -> line.split("\\s+"))
                        .filter(fields -> fields.length == 5 && fields[0].equals("TCP")
                                && fields[3].equals("LISTENING") && fields[4].equals(Long.toString(worker.pid())))
                        .map(fields -> fields[1]).toList();
                B33Evidence.require(listeners.equals(List.of(HOST + ":" + port)),
                        "B33 worker must own just its IPv4 loopback listener.");
            }
        } finally {
            if (sockets.isAlive()) {
                sockets.destroyForcibly();
                B33Evidence.require(sockets.waitFor(3, TimeUnit.SECONDS), "B33 listener inventory is still running.");
            }
            Files.deleteIfExists(log);
        }
    }

    private static void stop(Process process) throws Exception {
        try {
            process.getOutputStream().close();
            B33Evidence.require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                    "B33 worker could not exit cleanly.");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                B33Evidence.require(process.waitFor(5, TimeUnit.SECONDS), "B33 worker is still running.");
            }
        }
    }
}
