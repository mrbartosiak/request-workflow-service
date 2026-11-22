import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Owned JVM and direct HTTP client for the opt-in B08 wire oracle. */
final class B08HttpSession implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String READY = "READY http://" + HOST + ":";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private final Process process;
    private final HttpClient client;
    private final int port;

    private B08HttpSession(Process process, int port) {
        this.process = process;
        this.port = port;
        client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    static B08HttpSession start(String runtimeClasspath, int port, Path log, Path scratch,
                                QualifyPostgresql.RuntimeLogin workflow, QualifyPostgresql.RuntimeLogin proof,
                                B08MvcSession.Parameters settings) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(java.toString(), "-cp", runtimeClasspath,
                "com.company.requests.bootstrap.SyntheticHttpMain", Integer.toString(port),
                Files.createDirectory(scratch).toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            var fields = List.of(workflow.password(), proof.password(), settings.writer().writerId().toString(),
                    Long.toString(settings.writer().generation()), encoder.encodeToString(settings.signing()),
                    encoder.encodeToString(settings.intent()), settings.bearers().get(0), settings.bearers().get(1),
                    settings.bearers().get(2), settings.bearers().get(3));
            for (String field : fields) {
                if (field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Invalid line in synthetic HTTP fixture configuration.");
                }
                process.getOutputStream().write((field + "\n").getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().flush();
            int boundPort = readyPort(process, log);
            if (port != 0 && port != boundPort) {
                throw new AssertionError("Restart bound an unexpected HTTP port.");
            }
            verifySingleListener(process.pid(), boundPort);
            System.out.printf("PASS application pid=%d owns only one TCP listener %s:%d%n",
                    process.pid(), HOST, boundPort);
            return new B08HttpSession(process, boundPort);
        } catch (Exception | Error failure) {
            try {
                stop(process);
            } catch (Exception | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    int port() {
        return port;
    }

    long pid() {
        return process.pid();
    }

    B08MvcSession.Reply invoke(B08MvcSession.Call call) throws Exception {
        var uri = URI.create("http://" + HOST + ":" + port + call.route());
        var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Accept", "application/json, application/problem+json");
        if (!call.bearer().isEmpty()) {
            request.header("Authorization", "Bearer " + call.bearer());
        }
        if (!call.key().isEmpty()) {
            request.header("Idempotency-Key", call.key());
        }
        if (call.body().isEmpty()) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(call.body()));
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.version() != HttpClient.Version.HTTP_1_1 || response.sslSession().isPresent()) {
            throw new AssertionError("Synthetic response must use plaintext HTTP/1.1.");
        }
        var headers = response.headers();
        var body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
        return new B08MvcSession.Reply(response.statusCode(), headers.firstValue("Location").orElse(""),
                headers.firstValue("Cache-Control").orElse(""), headers.firstValue("Content-Type").orElse(""), body);
    }

    @Override
    public void close() throws Exception {
        try {
            client.close();
        } finally {
            stop(process);
            requireClosed(port);
            System.out.printf("PASS application pid=%d exited 0 and %s:%d refused connections%n", pid(), HOST, port);
        }
    }

    private static int readyPort(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
                var port = lines.filter(line -> line.startsWith(READY)).findFirst();
                if (port.isPresent()) {
                    return Integer.parseInt(port.orElseThrow().substring(READY.length()));
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Synthetic HTTP application did not become ready.");
    }

    private static void verifySingleListener(long pid, int port) throws Exception {
        var netstat = new ProcessBuilder("netstat.exe", "-ano").redirectErrorStream(true).start();
        try {
            String listing = new String(netstat.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (!netstat.waitFor(5, TimeUnit.SECONDS) || netstat.exitValue() != 0) {
                throw new AssertionError("Socket inventory failed.");
            }
            var listeners = listing.lines().map(String::strip).map(line -> line.split("\\s+"))
                    .filter(fields -> fields.length == 5 && fields[0].equals("TCP")
                            && fields[3].equals("LISTENING") && fields[4].equals(Long.toString(pid)))
                    .map(fields -> fields[1]).toList();
            if (!listeners.equals(List.of(HOST + ":" + port))) {
                throw new AssertionError("Unexpected socket inventory for the synthetic HTTP process: " + listeners);
            }
        } finally {
            if (netstat.isAlive()) {
                netstat.destroyForcibly();
            }
        }
    }

    private static void stop(Process process) throws Exception {
        try {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new AssertionError("Synthetic HTTP application did not stop cleanly.");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Owned HTTP application is still running.");
                }
            }
        }
    }

    private static void requireClosed(int port) throws IOException {
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(HOST, port), 1000);
            throw new AssertionError("HTTP listener survived application exit.");
        } catch (ConnectException expected) {
            // A new socket was actively refused after the owned JVM exited.
        }
    }
}
