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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/** A listening synthetic-http oracle for ingress paths that finish before database access. */
public final class B41HttpWire {
    private static final String HOST = "127.0.0.1";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String PREFIX = "request_evidence ";
    private static final String PAYLOAD = "wire-payload-sensitive-canary";
    private static final String URI_CANARY = "wire-uri-sensitive-canary";
    private static final String KEY = "wire-key-sensitive-canary";
    private static final String BAD_BEARER = "wire-unknown-bearer-sensitive-canary";
    private static final String WORKFLOW = "wire-workflow-password-sensitive-canary";
    private static final String PROOF = "wire-proof-password-sensitive-canary";
    private static final String STARTUP = "wire-invalid-startup-field-sensitive-canary";
    private static final JsonMapper JSON = new JsonMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: B41HttpWire <application runtime classpath> <empty run directory>");
        }
        Path run = Path.of(args[1]).toRealPath();
        Path output = run.resolve("application.log");
        var settings = B08MvcSession.Parameters.generate();
        var workflow = new QualifyPostgresql.RuntimeLogin(55480, "workflow_db", "b04_workflow", WORKFLOW);
        var proof = new QualifyPostgresql.RuntimeLogin(55481, "proof_db", "b04_proof", PROOF);
        var expected = new ArrayList<Expected>();
        try (var app = B08HttpSession.start(args[0], 0, output, run.resolve("http"), workflow, proof, settings);
             var client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(TIMEOUT)
                     .version(HttpClient.Version.HTTP_1_1).build()) {
            String base = "http://" + HOST + ":" + app.port();
            expected.add(problem(client, base, "POST", "/requests", BAD_BEARER, "{\"name\":\"" + PAYLOAD + "\"}",
                    401, "CREATE_REQUEST", "AUTHENTICATION_REQUIRED"));
            expected.add(problem(client, base, "POST", "/requests", settings.bearers().getFirst(),
                    "{\"type\":\"" + PAYLOAD + "\"}", 400, "CREATE_REQUEST", "INVALID_INPUT"));
            expected.add(problem(client, base, "POST", "/operation-keys?" + URI_CANARY, settings.bearers().getFirst(),
                    "", 400, "ISSUE_OPERATION_KEY", "INVALID_INPUT"));
            expected.add(problem(client, base, "GET", "/requests/" + URI_CANARY + "?query=" + URI_CANARY,
                    "", "", 404, "UNMATCHED_HTTP", "ROUTE_NOT_FOUND"));
            expected.add(problem(client, base, "GET", "/requests", "", "",
                    405, "CREATE_REQUEST", "METHOD_NOT_ALLOWED"));
            malformedRequest(app.port());
        }

        String logs = Files.readString(output, StandardCharsets.UTF_8);
        var secretEncoder = Base64.getUrlEncoder().withoutPadding();
        var secrets = new ArrayList<>(settings.bearers());
        secrets.addAll(List.of(PAYLOAD, URI_CANARY, KEY, BAD_BEARER, WORKFLOW, PROOF,
                secretEncoder.encodeToString(settings.signing()), secretEncoder.encodeToString(settings.intent()),
                settings.writer().writerId().toString()));
        long leaks = secrets.stream().filter(logs::contains).count();
        var events = logs.lines().filter(line -> line.startsWith(PREFIX))
                .map(line -> JSON.readTree(line.substring(PREFIX.length()))).toList();
        System.out.printf("Observed requests=%d events=%d expectedEvents=%d sentinelMatches=%d%n",
                expected.size() + 1, events.size(), (expected.size() + 1) * 3, leaks);
        require(leaks == 0 && events.size() == (expected.size() + 1) * 3,
                "Application log count or sentinel exclusion did not reconcile.");
        var byCorrelation = events.stream().collect(Collectors.groupingBy(event -> event.get("correlation_id").asString()));
        require(byCorrelation.size() == expected.size() + 1, "Wrong number of generated correlations.");
        var matched = new HashSet<String>();
        for (var request : expected) {
            require(matched.add(request.correlation()), "Two HTTP problems shared a correlation.");
            verifyEvidence(request, byCorrelation.get(request.correlation()));
        }
        String parser = byCorrelation.keySet().stream().filter(id -> !matched.contains(id)).findFirst().orElseThrow();
        verifyEvidence(new Expected(parser, 400, "UNMATCHED_HTTP", "TRANSPORT_REJECTED"), byCorrelation.get(parser));
        System.out.println("PASS six wire rejects yielded six starts, six errors and six outcomes with no raw sentinel matches");
        System.out.println("PASS five configured HTTP problems correlate to private evidence and preserve status and headers");
        checkStartupFailure(args[0], run);
    }

    private static void checkStartupFailure(String runtime, Path run) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path scratch = Files.createDirectory(run.resolve("invalid-startup"));
        Path output = run.resolve("invalid-startup.log");
        var process = new ProcessBuilder(java.toString(), "-cp", runtime,
                "com.company.requests.bootstrap.SyntheticHttpMain", "0", scratch.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try (var settings = process.getOutputStream()) {
                settings.write(String.join("\n", WORKFLOW, PROOF, STARTUP, "").getBytes(StandardCharsets.UTF_8));
            }
            require(process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 1,
                    "Invalid private configuration did not fail promptly.");
            String diagnostic = Files.readString(output, StandardCharsets.UTF_8).strip();
            require(diagnostic.equals("Synthetic HTTP application failed (IllegalArgumentException)."),
                    "Startup failure emitted something other than the fixed diagnostic.");
            System.out.println("PASS invalid private startup input produced a fixed diagnostic and no readiness");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                require(process.waitFor(5, TimeUnit.SECONDS), "Owned invalid-startup JVM survived cleanup.");
            }
        }
    }

    private static Expected problem(HttpClient client, String base, String method, String path, String bearer,
                                    String body, int status, String operation, String outcome) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                .header("Accept", "application/json, application/problem+json");
        if (!bearer.isEmpty()) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (!body.isEmpty()) {
            request.header("Content-Type", "application/json").header("Idempotency-Key", KEY);
        }
        var payload = body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var response = client.send(request.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
        var headers = response.headers();
        require(response.statusCode() == status && headers.firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json") && headers.firstValue("Cache-Control").orElse("").equals("no-store")
                && headers.firstValue("Location").isEmpty(), "Unexpected problem response status or headers.");
        if (status == 401) {
            require(headers.firstValue("WWW-Authenticate").orElse("").equals("Bearer"), "Missing Bearer challenge.");
        }
        if (status == 405) {
            require(headers.firstValue("Allow").orElse("").contains("POST"), "Missing supported method.");
        }
        var problem = JSON.readTree(response.body());
        String correlation = problem.get("correlationId").asString();
        require(UUID.fromString(correlation).toString().equals(correlation)
                && problem.get("instance").asString().equals("urn:uuid:" + correlation), "Invalid problem correlation.");
        return new Expected(correlation, status, operation, outcome);
    }

    private static void malformedRequest(int port) throws Exception {
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(HOST, port), (int) TIMEOUT.toMillis());
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            byte[] request = ("GET /[" + URI_CANARY + "] HTTP/1.1\r\nHost: " + HOST
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            require(response.startsWith("HTTP/1.1 400 "), "Malformed request did not fail at the connector.");
        }
    }

    private static void verifyEvidence(Expected spec, List<tools.jackson.databind.JsonNode> events) {
        require(events != null && events.size() == 3, "Missing start, error or outcome for a problem.");
        require(events.stream().map(event -> event.get("phase").asString()).toList()
                .equals(List.of("START", "ERROR", "OUTCOME")), "Unexpected event sequence.");
        for (var event : events) {
            require(event.get("direction").asString().equals("INGRESS")
                    && event.get("operation").asString().equals(spec.operation())
                    && event.get("destination").asString().equals("REQUESTS_API")
                    && event.get("elapsed_ns").asLong() >= 0 && event.get("at").asString().endsWith("Z"),
                    "Invalid safe envelope.");
            Instant.parse(event.get("at").asString());
        }
        require(events.subList(1, 3).stream().allMatch(event -> event.get("status").asInt() == spec.status()
                && event.get("outcome").asString().equals(spec.outcome())
                && !event.get("description").asString().isBlank()), "Unexpected catalogue fields.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Expected(String correlation, int status, String operation, String outcome) { }
}
