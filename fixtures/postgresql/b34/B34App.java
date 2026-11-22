import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate;
import com.company.requests.bootstrap.B32Gate.Cut;
import com.company.requests.bootstrap.B34Probe.Mode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

final class B34App implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String READY = "READY http://" + HOST + ":";
    private static final Duration START_WAIT = Duration.ofSeconds(25);
    private final Path control;
    private final Path log;
    private final Process child;
    final String name;
    final int port;
    final B32Gate gates;
    final B32Gate durability;

    private B34App(Path control, Path log, Process child, String name, int port) {
        this.control = control;
        this.log = log;
        this.child = child;
        this.name = name;
        this.port = port;
        gates = new B32Gate(control);
        durability = new B32Gate(control.resolve("durability"));
    }

    static B34App start(Path run, String name, B33Profile.Child settings, Instant clock) throws Exception {
        Path root = Files.createDirectory(run.resolve(name));
        Path control = Files.createDirectory(root.resolve("control"));
        Files.createDirectory(control.resolve("durability"));
        B32Gate.atomic(control.resolve("trusted-clock"), clock.toString());
        Path log = root.resolve("worker.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                B34Worker.class.getName(), root.toString(), name).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            var input = child.outputWriter(StandardCharsets.UTF_8);
            for (String field : settings.fields()) {
                input.write(field);
                input.newLine();
            }
            input.flush();
            int port = ready(child, log);
            inventory(child, port, root.resolve("socket-inventory.log"));
            System.out.printf("STARTED %s jvm=%d http=%s:%d%n", name, child.pid(), HOST, port);
            return new B34App(control, log, child, name, port);
        } catch (Exception | AssertionError failure) {
            try {
                stop(child);
            } catch (Exception | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void setClock(Instant clock) throws Exception {
        B32Gate.atomic(control.resolve("trusted-clock"), clock.toString());
    }

    long pid() {
        return child.pid();
    }

    B32Database identity(Store store) throws Exception {
        return B32Database.load(control.resolve(store.name().toLowerCase(Locale.ROOT) + ".identity"));
    }

    List<Integer> pool(Store store) throws Exception {
        return Arrays.stream(Files.readString(control.resolve(store.name().toLowerCase(Locale.ROOT) + ".pool"))
                .split(" ")).map(Integer::valueOf).toList();
    }

    void arm(Cut cut, Mode mode) throws Exception {
        durability.arm(cut);
        B32Gate.atomic(control.resolve("durability/mode"), cut.id() + "\t" + mode.name());
    }

    List<Trace> traces() throws Exception {
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.startsWith("B34_JDBC ")).map(Trace::parse).toList();
        }
    }

    List<String> receiptEvents() throws Exception {
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.startsWith("B33_JDBC ")).toList();
        }
    }

    record Trace(String phase, int backend, String xid, Instant time) {
        static Trace parse(String line) {
            String[] fields = line.split(" ");
            return new Trace(fields[1], Integer.parseInt(fields[2]), fields[3], Instant.parse(fields[4]));
        }
    }

    record Call(String method, String path, String bearer, String key, String body) {
        byte[] request(int servicePort) {
            var header = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + HOST + ":" + servicePort
                    + "\r\nConnection: close\r\nAccept: application/json, application/problem+json\r\n");
            var encoded = body.getBytes(UTF_8);
            if (!bearer.isEmpty()) {
                header.append("Authorization: Bearer ").append(bearer).append("\r\n");
            }
            if (!key.isEmpty()) {
                header.append("Idempotency-Key: ").append(key).append("\r\n");
            }
            if (!body.isEmpty()) {
                header.append("Content-Type: application/json\r\nContent-Length: ").append(encoded.length).append("\r\n");
            }
            var bytes = new ByteArrayOutputStream();
            bytes.writeBytes(header.append("\r\n").toString().getBytes(US_ASCII));
            bytes.writeBytes(encoded);
            return bytes.toByteArray();
        }
    }

    B33App.Reply request(Call call) throws Exception {
        return parse(exchange(port, call.request(port)));
    }

    CompletableFuture<B33App.Reply> send(Call call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return request(call);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    /** The real upstream response is the comparison oracle. The downstream client receives zero bytes. */
    CompletableFuture<B33App.Reply> lose(Call call) {
        return CompletableFuture.supplyAsync(() -> {
            try (var listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress(InetAddress.getByName(HOST), 0));
                listener.setSoTimeout(8000);
                byte[] original = call.request(port);
                var client = CompletableFuture.supplyAsync(() -> {
                    try {
                        return exchange(listener.getLocalPort(), original);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                });
                byte[] captured;
                try (var downstream = listener.accept()) {
                    downstream.setSoTimeout(8000);
                    byte[] supplied = downstream.getInputStream().readNBytes(original.length);
                    B33Evidence.require(Arrays.equals(original, supplied), "Response-loss relay changed the command.");
                    captured = exchange(port, supplied);
                }
                B33Evidence.require(client.get(9, TimeUnit.SECONDS).length == 0, "Caller received response bytes.");
                var reply = parse(captured);
                System.out.printf("PASS %s original server HTTP=%d upstream-bytes=%d caller-bytes=0%n",
                        name, reply.status(), captured.length);
                return reply;
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private static byte[] exchange(int port, byte[] request) throws IOException {
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(HOST, port), 1500);
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(request);
            return socket.getInputStream().readAllBytes();
        }
    }

    static B33App.Reply parse(byte[] wire) {
        var stream = new ByteArrayInputStream(wire);
        String[] status = line(stream).split(" ", 3);
        B33Evidence.require(status.length >= 2 && status[0].equals("HTTP/1.1"), "Missing local HTTP/1.1 response.");
        var headers = new TreeMap<String, List<String>>();
        for (String header = line(stream); !header.isEmpty(); header = line(stream)) {
            String[] pair = header.split(":", 2);
            B33Evidence.require(pair.length == 2 && headers.putIfAbsent(pair[0].toLowerCase(Locale.ROOT),
                    List.of(pair[1].strip())) == null, "Malformed or repeated local HTTP header.");
        }
        byte[] body = headers.getOrDefault("transfer-encoding", List.of()).equals(List.of("chunked"))
                ? chunks(stream) : stream.readAllBytes();
        if (headers.containsKey("content-length")) {
            B33Evidence.require(headers.get("content-length").equals(List.of(Integer.toString(body.length))),
                    "Local HTTP response length disagrees with its body.");
        }
        return new B33App.Reply(Integer.parseInt(status[1]), headers, body);
    }

    private static byte[] chunks(ByteArrayInputStream stream) {
        var bytes = new ByteArrayOutputStream();
        int size;
        while ((size = Integer.parseInt(line(stream), 16)) != 0) {
            B33Evidence.require(size > 0 && size <= stream.available(), "Invalid local body chunk size.");
            for (int i = 0; i < size; i++) {
                bytes.write(stream.read());
            }
            B33Evidence.require(line(stream).isEmpty(), "Invalid local body chunk terminator.");
        }
        B33Evidence.require(line(stream).isEmpty() && stream.available() == 0, "Unexpected trailing HTTP data.");
        return bytes.toByteArray();
    }

    private static String line(ByteArrayInputStream stream) {
        var bytes = new ByteArrayOutputStream();
        for (int next; (next = stream.read()) != -1;) {
            if (next == '\r') {
                B33Evidence.require(stream.read() == '\n', "Invalid local HTTP line terminator.");
                return bytes.toString(US_ASCII);
            }
            bytes.write(next);
        }
        throw new AssertionError("Incomplete local HTTP response.");
    }

    @Override
    public void close() throws Exception {
        stop(child);
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(HOST, port), 1000);
            throw new AssertionError("Owned B34 listener survived shutdown.");
        } catch (ConnectException expected) {
            System.out.printf("STOPPED %s jvm=%d http=%s:%d refused connection%n", name, child.pid(), HOST, port);
        }
    }

    private static void stop(Process child) throws Exception {
        try {
            child.getOutputStream().close();
            B33Evidence.require(child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
                    "B34 worker could not exit cleanly.");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                B33Evidence.require(child.waitFor(5, TimeUnit.SECONDS), "B34 child survived forced cleanup.");
            }
        }
    }

    private static int ready(Process child, Path log) throws Exception {
        long deadline = System.nanoTime() + START_WAIT.toNanos();
        while (child.isAlive() && System.nanoTime() < deadline) {
            try (var lines = Files.lines(log)) {
                var marker = lines.filter(line -> line.startsWith(READY)).findFirst();
                if (marker.isPresent()) {
                    return Integer.parseInt(marker.orElseThrow().substring(READY.length()));
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("B34 service failed to become ready. Inspect its worker log.");
    }

    private static void inventory(Process child, int port, Path log) throws Exception {
        var sockets = new ProcessBuilder("netstat.exe", "-ano").redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            sockets.getOutputStream().close();
            B33Evidence.require(sockets.waitFor(5, TimeUnit.SECONDS) && sockets.exitValue() == 0,
                    "B34 listener inventory failed.");
            try (var lines = Files.lines(log, US_ASCII)) {
                var listeners = lines.map(String::strip).map(line -> line.split("\\s+"))
                        .filter(fields -> fields.length == 5 && fields[0].equals("TCP")
                                && fields[3].equals("LISTENING") && fields[4].equals(Long.toString(child.pid())))
                        .map(fields -> fields[1]).toList();
                B33Evidence.require(listeners.equals(List.of(HOST + ":" + port)),
                        "B34 worker must own one IPv4 loopback listener.");
            }
        } finally {
            if (sockets.isAlive()) {
                sockets.destroyForcibly();
                B33Evidence.require(sockets.waitFor(3, TimeUnit.SECONDS), "B34 listener inventory survived.");
            }
            Files.deleteIfExists(log);
        }
    }
}
