import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate.Cut;
import com.company.requests.bootstrap.B32Gate.Event;
import com.company.requests.bootstrap.B32Gate.Point;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.company.requests.bootstrap.B32Database.hash;

/** B32 acceptance scenario. Successor scenarios can reuse the fixture, app and independent observations. */
public final class B32Harness {
    static final Instant CLOCK = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final Duration EVENT_WAIT = Duration.ofSeconds(4);
    private static final String NAME = " Zażółć 東京 ";
    private static final String BODY = "line 1\r\nline 2 🚀";
    private static final String CREATE = "{\"type\":\"HR_TICKET\",\"name\":\" Zażółć 東京 \",\"body\":\"line 1\\r\\nline 2 🚀\"}";

    private B32Harness() { }

    public static void main(String[] args) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            if (args.length == 2 && args[0].equals("scan")) {
                scan(args[1], false);
                return;
            }
            require(args.length == 4 && List.of("run", "stalled", "cleanup").contains(args[0])
                    && args[3].matches("b32-[a-z0-9-]{1,27}"), "Usage: B32Harness run|stalled|cleanup BIN RUNS b32-ID");
            boolean stall = args[0].equals("stalled");
            if (!args[0].equals("cleanup")) {
                scan(System.getProperty("requests.b32.runtime", ""), true);
            }
            // B04 currently exposes its workflow+proof lifecycle for B08. Keep its ownership marker and
            // use a B32-private runs root. The B32 command accepts only its own ticket-prefixed run IDs.
            String inheritedId = "b08-" + args[3];
            Path run = Path.of(args[2]).resolve(inheritedId).toAbsolutePath().normalize();
            QualifyPostgresql.workflowAndProof(new String[]{args[0].equals("cleanup") ? "cleanup" : "run",
                    args[1], args[2], inheritedId}, "b08", (workflow, proof) -> {
                        var fixture = new B32Fixture(workflow, proof);
                        fixture.provision();
                        verify(fixture, run, System.getProperty("java.class.path"), stall);
                    });
        } catch (Exception | AssertionError failure) {
            printFailure(failure);
            System.exit(1);
        }
    }

    private static void verify(B32Fixture fixture, Path run, String classpath, boolean stall) throws Exception {
        fixture.verifyReadOnly(Store.WORKFLOW);
        fixture.verifyReadOnly(Store.PROOF);
        try (Connection work = fixture.observer(Store.WORKFLOW); Connection proof = fixture.observer(Store.PROOF)) {
            B32Database primary = B32Database.read(work, Store.WORKFLOW);
            B32Database independent = B32Database.read(proof, Store.PROOF);
            var second = new ArrayList<>(fixture.settings());
            if (stall) {
                Collections.swap(second, 6, 9); // Misbind the expected owner and no-issue bearer only on B.
            }
            B32App.Reply created;
            String key;
            long firstPid;
            try (var a = B32App.start(run, "b32-a", classpath, fixture.settings(), CLOCK);
                 var b = B32App.start(run, "b32-b", classpath, second, CLOCK)) {
                firstPid = a.pid();
                sameProcesses(a, b);
                require(!primary.endpoint().equals(independent.endpoint()), "Independent proof shares workflow endpoint.");
                verifyIdentities(a, primary, independent);
                verifyIdentities(b, primary, independent);
                reject("nonindependent JVM and listener", () -> sameProcesses(a, a));
                reject("wrong PostgreSQL server", () -> sameIdentity(independent, primary, Store.WORKFLOW));
                work.setSchema("fixture");
                B32Database wrongSchema = B32Database.read(work, Store.WORKFLOW);
                work.setSchema(Store.WORKFLOW.schema);
                reject("wrong PostgreSQL schema", () -> sameIdentity(wrongSchema, primary, Store.WORKFLOW));
                key = verifyActorAndClock(a, fixture, CLOCK);
                if (stall) {
                    reject("actor scope drift", () -> verifyActorAndClock(b, fixture, CLOCK));
                    stalled(a, fixture, work, proof, key);
                }
                b.setClock(CLOCK.plusSeconds(1));
                reject("trusted clock drift", () -> verifyActorAndClock(b, fixture, CLOCK));
                b.setClock(CLOCK);
                verifyActorAndClock(b, fixture, CLOCK);
                System.out.printf("PASS actor and trusted clock parity across A/B time=%s expires=%s%n",
                        CLOCK, CLOCK.plus(OperationKeyIssuer.VALIDITY));
                created = contend(a, b, fixture, work, proof, key);
                B32App.Reply settled = B32App.completed(b.post("/requests", fixture.alias, key, CREATE));
                assertCreated(settled);
                require(created.equals(settled), "B retry changed the exact HTTP response after lock timeout.");
                B32Fixture.snapshot("after-b-replay", work, proof, 1, 1, 2);
                require(b.isAlive(), "Peer B must still be live when A is ready to restart.");
                try (var restarted = restart(a, b, fixture, run, classpath, primary, independent)) {
                    require(firstPid != restarted.pid() && b.pid() != restarted.pid(), "Restart must be a new JVM.");
                    B32App.Reply replay = B32App.completed(restarted.post("/requests", fixture.alias, key, CREATE));
                    assertCreated(replay);
                    require(created.equals(replay), "Restarted JVM changed the exact HTTP response.");
                    B32Fixture.snapshot("after-restart", work, proof, 1, 1, 3);
                    B32Fixture.verifyResult(work, proof, replay.json().get("ref").asString(), NAME, BODY, CLOCK,
                            fixture.writer.generation(), 3);
                    System.out.printf("PASS exact restart HTTP 201 content-type=%s cache=%s Location SHA-256=%s "
                                    + "body SHA-256=%s old JVM=%d new JVM=%d peer JVM=%d%n",
                            replay.contentType(), replay.cache(), digest(replay.location()), digest(replay.body()),
                            firstPid, restarted.pid(), b.pid());
                }
            }
            require(primary.equals(B32Database.read(work, Store.WORKFLOW))
                    && independent.equals(B32Database.read(proof, Store.PROOF)),
                    "Independent observers or their database postmasters changed during restart.");
            verifyLogs(run, fixture, key, created.json().get("ref").asString());
        }
    }

    private static B32App restart(B32App old, B32App peer, B32Fixture fixture, Path run, String classpath,
                                  B32Database primary, B32Database independent) throws Exception {
        old.close();
        require(!old.isAlive() && peer.isAlive(), "Restart must exit only its owned application.");
        var restarted = B32App.start(run, "b32-a-restarted", classpath, fixture.settings(), CLOCK);
        try {
            sameProcesses(restarted, peer);
            verifyIdentities(restarted, primary, independent);
            verifyActorAndClock(restarted, fixture, CLOCK);
            return restarted;
        } catch (Exception | AssertionError failure) {
            try {
                restarted.close();
            } catch (Exception | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static B32App.Reply contend(B32App a, B32App b, B32Fixture fixture, Connection work, Connection proof,
                                       String key) throws Exception {
        var afterClaim = new Cut(Point.AFTER_CLAIM, "a-winner", 1700);
        var beforeClaim = new Cut(Point.BEFORE_CLAIM, "b-waiter", 1700);
        var beforeCommit = new Cut(Point.BEFORE_COMMIT, "a-winner", 1100);
        var beforeRelease = new Cut(Point.BEFORE_RELEASE, "a-winner", 1100);
        a.gates.arm(afterClaim);
        a.gates.arm(beforeCommit);
        a.gates.arm(beforeRelease);
        b.gates.arm(beforeClaim);
        // Let B complete its HTTP/authority setup before starting A's bounded command window.
        var resultB = b.post("/requests", fixture.alias, key, CREATE);
        Event waiting = entered(b, beforeClaim, work, resultB);
        var resultA = a.post("/requests", fixture.owner, key, CREATE);
        Event claimed = entered(a, afterClaim, work, resultA);
        B32Fixture.snapshot("after-claim", work, proof, 0, 0, 0);
        require(claimed.backend() != waiting.backend(), "A/B must have distinct real command backends.");
        require(B32Fixture.blockers(work, waiting.backend()).equals("{}"), "Held B is already blocked in PostgreSQL.");
        String expected = "{" + claimed.backend() + "}";
        reject("unfinished HTTP request alone is false blocking", () -> requireBlocker(work, waiting.backend(), expected));
        require(!resultB.isDone(), "Negative blocking control lost its in-flight request.");
        released(b, beforeClaim, waiting);
        awaitBlocker(work, waiting.backend(), expected, resultB);
        System.out.printf("PASS primary observer pg_blocking_pids(%d)=%s at=%s%n", waiting.backend(), expected, Instant.now());
        B32App.Reply timedOut = B32App.completed(resultB);
        problem(timedOut, 503, "outcome_unknown");
        require(!resultA.isDone(), "A must remain gated through B's lock timeout.");
        B32Fixture.snapshot("after-b-503", work, proof, 0, 0, 0);
        released(a, afterClaim, claimed);
        Event committing = entered(a, beforeCommit, work, resultA);
        require(committing.backend() == claimed.backend(), "Claim and commit must use the exact same JDBC backend.");
        B32Fixture.snapshot("before-commit", work, proof, 0, 1, 0);
        released(a, beforeCommit, committing);
        Event releasing = entered(a, beforeRelease, proof, resultA);
        B32Fixture.snapshot("before-release", work, proof, 1, 1, 0);
        released(a, beforeRelease, releasing);
        B32App.Reply created = B32App.completed(resultA);
        assertCreated(created);
        B32Fixture.snapshot("after-a-201", work, proof, 1, 1, 1);
        B32Fixture.verifyResult(work, proof, created.json().get("ref").asString(), NAME, BODY, CLOCK,
                fixture.writer.generation(), 1);
        return created;
    }

    private static void stalled(B32App app, B32Fixture fixture, Connection work, Connection proof, String key)
            throws Exception {
        var cut = new Cut(Point.AFTER_CLAIM, "stall", 180);
        app.gates.arm(cut);
        var reply = app.post("/requests", fixture.owner, key, CREATE);
        Event entered = entered(app, cut, work, reply);
        Event timedOut = app.gates.await(cut, "finished", EVENT_WAIT);
        require(timedOut.backend() == entered.backend() && timedOut.outcome().equals("timed-out"),
                "Stall control did not observe a bounded timeout.");
        B32App.Reply stalled = B32App.completed(reply);
        problem(stalled, 500, "internal_error");
        B32Fixture.snapshot("after-stalled-cut", work, proof, 0, 0, 0);
        System.out.printf("RED timed-out cut %s backend=%d at=%s HTTP=%d%n",
                cut.point(), timedOut.backend(), timedOut.at(), stalled.status());
        throw new AssertionError("B32 fixture barrier timed out; discarding run.");
    }

    private static Event entered(B32App app, Cut cut, Connection observer, CompletableFuture<B32App.Reply> request)
            throws Exception {
        Event event = app.gates.await(cut, "entered", EVENT_WAIT);
        require(event.outcome().equals("entered") && event.backend() > 0 && !request.isDone()
                && B32Fixture.liveApp(observer, event.backend()).equals(app.name),
                "Expected live app-owned JDBC backend at " + cut.point() + ".");
        System.out.printf("ENTERED %s %s backend=%d at=%s%n", app.name, cut.point(), event.backend(), event.at());
        return event;
    }

    private static void released(B32App app, Cut cut, Event entered) throws Exception {
        app.gates.release(cut);
        Event finished = app.gates.await(cut, "finished", EVENT_WAIT);
        require(finished.outcome().equals("released") && finished.backend() == entered.backend(),
                "B32 fixture barrier timed out or changed backend.");
        System.out.printf("RELEASED %s %s backend=%d at=%s%n", app.name, cut.point(), finished.backend(), finished.at());
    }

    private static void awaitBlocker(Connection observer, int waiter, String expected,
                                     CompletableFuture<B32App.Reply> waiting) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(600).toNanos();
        do {
            if (B32Fixture.blockers(observer, waiter).equals(expected)) {
                require(!waiting.isDone(), "PostgreSQL blocker must overlap the in-flight HTTP request.");
                return;
            }
            Thread.sleep(2);
        } while (!waiting.isDone() && System.nanoTime() < deadline);
        requireBlocker(observer, waiter, expected);
        require(!waiting.isDone(), "Wait ended before confirming the caller.");
    }

    private static void requireBlocker(Connection observer, int waiter, String expected) throws Exception {
        require(B32Fixture.blockers(observer, waiter).equals(expected), "Observed connection has no expected PostgreSQL blocker.");
    }

    private static void sameProcesses(B32App a, B32App b) {
        require(a.isAlive() && b.isAlive() && a.pid() != b.pid() && a.port() != b.port(),
                "Expected independent live JVMs and distinct ephemeral HTTP ports.");
    }

    private static void verifyIdentities(B32App app, B32Database primary, B32Database independent) throws Exception {
        B32Database command = app.identity(Store.WORKFLOW);
        B32Database proof = app.identity(Store.PROOF);
        sameIdentity(command, primary, Store.WORKFLOW);
        sameIdentity(proof, independent, Store.PROOF);
        require(command.user().equals(Store.WORKFLOW.user) && proof.user().equals(Store.PROOF.user)
                && command.application().equals(app.name) && proof.application().equals(app.name)
                && command.pid() != primary.pid() && proof.pid() != independent.pid(),
                "B32 runtime and observer connections are not independent.");
        System.out.printf("PASS %s workflow=%s schema=%s startup backend=%d observer=%d migrator=%s%n", app.name,
                command.endpoint(), command.schema(), command.pid(), primary.pid(), command.migrator());
        System.out.printf("PASS %s proof=%s schema=%s startup backend=%d observer=%d migrator=%s%n", app.name,
                proof.endpoint(), proof.schema(), proof.pid(), independent.pid(), proof.migrator());
    }

    private static void sameIdentity(B32Database app, B32Database observer, Store store) {
        require(app.endpoint().startsWith("127.0.0.1/" + store.port + "/" + store.database + "/180007/")
                && app.endpoint().equals(observer.endpoint()), "Wrong PostgreSQL server or database.");
        String history = store == Store.WORKFLOW ? "1,2,3" : "1,2";
        require(app.schema().startsWith(store.schema + "/") && app.schema().endsWith("/b04_admin/" + history)
                && app.schema().equals(observer.schema()), "Wrong PostgreSQL schema or migration history.");
        require(app.migrator().equals(observer.migrator()), "Wrong packaged migrator.");
    }

    private static String verifyActorAndClock(B32App app, B32Fixture fixture, Instant clock) throws Exception {
        var original = B32App.completed(app.post("/operation-keys", fixture.owner, "", ""));
        var alias = B32App.completed(app.post("/operation-keys", fixture.alias, "", ""));
        var denied = B32App.completed(app.post("/operation-keys", fixture.noIssue, "", ""));
        var absent = B32App.completed(app.post("/operation-keys", "", "", ""));
        require(original.status() == 201 && alias.status() == 201 && denied.status() == 403 && absent.status() == 401,
                "Actor scope parity failed.");
        for (var issued : List.of(original, alias)) {
            var json = issued.json();
            require(issued.cache().equals("no-store") && issued.location().isEmpty() && json.size() == 2
                    && !json.get("key").asString().isBlank(), "Invalid key issuance over HTTP.");
            require(Instant.parse(json.get("expiresAt").asString()).equals(clock.plus(OperationKeyIssuer.VALIDITY)),
                    "Trusted clock parity failed.");
        }
        return original.json().get("key").asString();
    }

    private static void assertCreated(B32App.Reply reply) {
        require(reply.status() == 201 && reply.cache().equals("no-store") && reply.contentType().startsWith("application/json"),
                "Expected HTTP 201 no-store JSON, got " + reply.status() + ".");
        var json = reply.json();
        String ref = json.get("ref").asString();
        require(json.size() == 4 && ref.matches("[A-Za-z0-9_-]{32}") && reply.location().equals("/requests/" + ref)
                && json.get("action").asString().equals("create") && json.get("state").asString().equals("CREATED")
                && json.get("resultRevision").isString() && json.get("resultRevision").asString().equals("1"),
                "Unexpected Create response shape.");
    }

    private static void problem(B32App.Reply reply, int status, String code) {
        require(reply.status() == status && reply.cache().equals("no-store") && reply.location().isEmpty()
                && reply.contentType().startsWith("application/problem+json"),
                "Expected safe HTTP " + status + ", got " + reply.status() + ".");
        var json = reply.json();
        require(json.get("type").asString().equals("urn:requests:problem:" + code)
                && json.get("correlationId") != null && json.get("ref") == null, "Unexpected HTTP problem.");
        System.out.printf("PASS HTTP %d %s no-store, no Location or result reference%n", status, code);
    }

    private static void verifyLogs(Path run, B32Fixture fixture, String key, String ref) throws Exception {
        var sensitive = new ArrayList<>(fixture.settings());
        sensitive.remove(3); // The writer generation is deliberately present in evidence and is public.
        sensitive.add(key);
        sensitive.add(ref);
        for (String node : List.of("b32-a", "b32-b", "b32-a-restarted")) {
            String log = Files.readString(run.resolve(node).resolve("worker.log"));
            require(sensitive.stream().noneMatch(log::contains), "Protected synthetic data appeared in diagnostics.");
        }
        System.out.println("PASS application diagnostics contain no credentials, secret material, key or reference.");
    }

    private static void scan(String releaseClasspath, boolean requireListener) throws Exception {
        require(!releaseClasspath.isBlank(), "Provide the resolved packaged runtime classpath.");
        boolean listener = false;
        String[] jars = releaseClasspath.split(Pattern.quote(File.pathSeparator));
        for (String entry : jars) {
            Path jar = Path.of(entry);
            require(Files.isRegularFile(jar) && entry.endsWith(".jar"), "Release classpath must contain packaged JARs only.");
            try (var content = new JarFile(jar.toFile())) {
                require(content.stream().noneMatch(member -> {
                    String name = member.getName();
                    String filename = name.substring(name.lastIndexOf('/') + 1);
                    return filename.startsWith("B32") || filename.startsWith("B08MvcSession")
                            || filename.startsWith("B08Postgresql") || filename.startsWith("QualifyPostgresql");
                }), "Fixture control found on the release classpath.");
                listener |= content.getJarEntry("com/company/requests/bootstrap/SyntheticHttpMain.class") != null;
            }
        }
        require(!requireListener || listener, "Packaged B08 HTTP listener class missing.");
        System.out.printf("PASS scanned %d packaged JARs for fixture controls; B08 listener=%s%n", jars.length, listener);
    }

    @FunctionalInterface
    private interface Control {
        void run() throws Exception;
    }

    private static void reject(String label, Control check) throws Exception {
        try {
            check.run();
        } catch (AssertionError expected) {
            System.out.printf("RED rejected %s (%s)%n", label, expected.getMessage());
            return;
        }
        throw new AssertionError("Negative control falsely passed: " + label);
    }

    static void require(boolean okay, String message) {
        if (!okay) {
            throw new AssertionError(message);
        }
    }

    private static String digest(String value) throws Exception {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void printFailure(Throwable failure) {
        // Only fixture-owned assertion messages are safe. Native/dependency exceptions may contain SQL or values.
        if (failure.getStackTrace().length > 0
                && (failure instanceof AssertionError && failure.getStackTrace()[0].getClassName().startsWith("B32")
                    || failure instanceof IllegalStateException
                    && failure.getStackTrace()[0].getClassName().equals("QualifyPostgresql"))) {
            System.err.println("B32 failed: " + failure.getMessage());
        } else {
            System.err.printf("B32 failed (%s) at %s%n", failure.getClass().getSimpleName(), failure.getStackTrace()[0]);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                System.err.println("B32 JDBC SQLSTATE=" + sql.getSQLState() + " at " + sql.getStackTrace()[0]);
            }
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            System.err.printf("B32 additional failure (%s) at %s%n",
                    suppressed.getClass().getSimpleName(), suppressed.getStackTrace()[0]);
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().equals("QualifyPostgresql") && !frame.getMethodName().equals("require")) {
                System.err.println("  at " + frame);
            }
        }
    }
}
