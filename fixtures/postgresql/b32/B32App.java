import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate;
import com.company.requests.bootstrap.B32Worker;
import java.io.BufferedWriter;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One owned application JVM with endpoints and controls confined to the local fixture. */
final class B32App implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String READY = "READY http://" + HOST + ":";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);
    private static final JsonMapper JSON = new JsonMapper();
    private final Path root;
    private final Process process;
    private final int port;
    private final HttpClient http;
    private boolean closed;
    final B32Gate gates;
    final String name;

    private B32App(Path root, Process process, int port, String name) {
        this.root = root;
        this.process = process;
        this.port = port;
        this.name = name;
        gates = new B32Gate(root.resolve("control"));
        http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(HTTP_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    static B32App start(Path run, String name, String classpath, List<String> settings, Instant clock) throws Exception {
        Path root = Files.createDirectory(run.resolve(name));
        Path control = Files.createDirectory(root.resolve("control"));
        B32Gate.atomic(control.resolve("trusted-clock"), clock.toString());
        Path log = root.resolve("worker.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath, B32Worker.class.getName(),
                root.toString(), name).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int port;
        try {
            BufferedWriter input = process.outputWriter(StandardCharsets.UTF_8);
            for (String field : settings) {
                B32Harness.require(!field.isBlank() && !field.contains("\n") && !field.contains("\r"),
                        "Invalid private fixture configuration.");
                input.write(field);
                input.newLine();
            }
            input.flush();
            port = ready(process, log);
            verifySingleListener(process.pid(), port, root);
        } catch (Exception | AssertionError failure) {
            try {
                stop(process);
            } catch (Exception | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        System.out.printf("STARTED %s jvm=%d http=%s:%d at=%s%n", name, process.pid(), HOST, port, Instant.now());
        return new B32App(root, process, port, name);
    }

    void setClock(Instant clock) throws Exception {
        B32Gate.atomic(root.resolve("control").resolve("trusted-clock"), clock.toString());
    }

    B32Database identity(Store store) throws Exception {
        return B32Database.load(root.resolve("control").resolve(store.name().toLowerCase(java.util.Locale.ROOT) + ".identity"));
    }

    boolean isAlive() {
        return process.isAlive();
    }

    long pid() {
        return process.pid();
    }

    int port() {
        return port;
    }

    CompletableFuture<Reply> post(String route, String bearer, String key, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port + route))
                .timeout(HTTP_TIMEOUT).header("Accept", "application/json, application/problem+json");
        if (!bearer.isEmpty()) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (!key.isEmpty()) {
            request.header("Idempotency-Key", key);
        }
        if (body.isEmpty()) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    B32Harness.require(response.version() == HttpClient.Version.HTTP_1_1 && response.sslSession().isEmpty(),
                            "Expected plaintext HTTP/1.1.");
                    var headers = response.headers();
                    return new Reply(response.statusCode(), headers.firstValue("Location").orElse(""),
                            headers.firstValue("Cache-Control").orElse(""), headers.firstValue("Content-Type").orElse(""),
                            response.body());
                });
    }

    static Reply completed(CompletableFuture<Reply> reply) throws Exception {
        return reply.get(9, TimeUnit.SECONDS);
    }

    record Reply(int status, String location, String cache, String contentType, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        @Override
        public String toString() {
            return "Reply[status=" + status + "]";
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            http.shutdownNow();
            B32Harness.require(http.awaitTermination(Duration.ofSeconds(3)), "HTTP client did not terminate.");
        } finally {
            stop(process);
            try (var socket = new Socket(Proxy.NO_PROXY)) {
                socket.connect(new InetSocketAddress(HOST, port), 1000);
                throw new AssertionError("Owned HTTP port still accepts a new connection.");
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
                var match = lines.filter(line -> line.matches(READY.replace(".", "\\.") + "[0-9]+")).findFirst();
                if (match.isPresent()) {
                    return Integer.parseInt(match.get().substring(READY.length()));
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("B32 application did not become ready.");
    }

    private static void verifySingleListener(long pid, int port, Path root) throws Exception {
        Path inventory = root.resolve("socket-inventory.log");
        var netstat = new ProcessBuilder("netstat.exe", "-ano").redirectErrorStream(true)
                .redirectOutput(inventory.toFile()).start();
        try {
            netstat.getOutputStream().close();
            B32Harness.require(netstat.waitFor(5, TimeUnit.SECONDS) && netstat.exitValue() == 0,
                    "Socket inventory did not finish.");
            try (var lines = Files.lines(inventory, StandardCharsets.US_ASCII)) {
                var listeners = lines.map(String::strip).map(line -> line.split("\\s+"))
                        .filter(fields -> fields.length == 5 && fields[0].equals("TCP")
                                && fields[3].equals("LISTENING") && fields[4].equals(Long.toString(pid)))
                        .map(fields -> fields[1]).toList();
                B32Harness.require(listeners.equals(List.of(HOST + ":" + port)),
                        "B32 application must own exactly its IPv4 loopback listener.");
            }
        } finally {
            if (netstat.isAlive()) {
                netstat.destroyForcibly();
                netstat.waitFor(3, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(inventory);
        }
    }

    private static void stop(Process process) throws Exception {
        try {
            process.getOutputStream().close();
            B32Harness.require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                    "Owned application did not exit cleanly.");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                B32Harness.require(process.waitFor(5, TimeUnit.SECONDS), "Owned application is still running.");
            }
        }
    }
}
