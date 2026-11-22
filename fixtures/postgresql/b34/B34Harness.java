import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.domain.RequestRef;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static com.company.requests.bootstrap.B32Database.scalar;
import static java.nio.charset.StandardCharsets.US_ASCII;

public final class B34Harness {
    private B34Harness() { }

    public static void main(String[] args) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            if (args.length == 2 && args[0].equals("controls")) {
                controls(args[1]);
            } else {
                run(args);
            }
        } catch (Exception | AssertionError problem) {
            B33Evidence.failure(problem);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        B33Evidence.require(args.length == 5 && List.of("run", "cleanup").contains(args[0])
                && args[3].matches("b34-[a-z0-9-]{1,23}"), "Usage: B34Harness run|cleanup BIN RUNS b34-ID RUNTIME");
        if (args[0].equals("run")) {
            scan(args[4]);
        }
        String id = "b17-" + args[3];
        var root = Path.of(args[2]).resolve(id).toAbsolutePath().normalize();
        QualifyPostgresql.publishCommands(new String[]{args[0], args[1], args[2], id},
                (workflow, proof, projector, resolver, createProof, release, restore) -> {
                    B34State state;
                    List<RequestRef> seeds;
                    try (var sources = new B10FixtureSources()) {
                        var base = new B17Postgresql(workflow, proof, projector, resolver, createProof, release, sources,
                                java.util.function.UnaryOperator.identity());
                        base.enable(B34State.OWNER, B34Worker.TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY,
                                Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY);
                        base.enable(B34State.PUBLISHER, B34Worker.TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY,
                                Grant.FIELD_READ_NAME, Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT,
                                Grant.CONTEXT_PUBLISH, Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH,
                                Grant.FIELD_READ_PUBLICATION_ID);
                        try (var mvc = new B08MvcSession(base.settings)) {
                            seeds = List.of(base.accepted(mvc, B34Worker.TYPE), base.accepted(mvc, B34Worker.TYPE),
                                    base.accepted(mvc, B34Worker.TYPE));
                        }
                        state = new B34State(workflow, proof, base);
                    }
                    exercise(root, state, seeds);
                });
        System.out.println("PASS B34 " + args[0] + " and owned PostgreSQL teardown.");
    }

    private static void exercise(Path root, B34State state, List<RequestRef> seeds) throws Exception {
        var sensitive = new HashSet<>(state.child.fields());
        sensitive.removeAll(List.of(state.child.writer().writerId().toString(),
                Long.toString(state.child.writer().generation())));
        sensitive.addAll(List.of(B34Cases.NAME, B34Cases.BODY, state.base.ownerBearer, state.base.publisherBearer,
                state.scopeOperatorSecret()));
        try (var work = state.observe(Store.WORKFLOW); var proof = state.observe(Store.PROOF)) {
            B33Evidence.verifyObserver(work, Store.WORKFLOW);
            B33Evidence.verifyObserver(proof, Store.PROOF);
            for (var observer : List.of(work, proof)) {
                B33Evidence.require(scalar(observer, """
                        SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()
                          AND backend_type = 'client backend' AND pid <> pg_backend_pid()
                        """).equals("0"), "Provisioning left database sessions before worker startup.");
            }
            System.out.println("PASS provisioning sessions closed before starting bounded service pools.");
            var scenarios = new B34Cases(state, work, proof, sensitive);
            try (var b = B34App.start(root, "b34-b", state.child, B34State.CLOCK)) {
                List<B34Cases.Lost> lost;
                long originalPid;
                try (var a = B34App.start(root, "b34-a", state.child, B34State.CLOCK)) {
                    originalPid = a.pid();
                    verifyApps(a, b, work, proof);
                    scenarios.warm(a, b);
                    scenarios.p3(a, b, scenarios.create(a), "p3-create");
                    scenarios.p3(a, b, scenarios.publish(a, seeds.get(0)), "p3-publish");
                    scenarios.p5Before(a, b, scenarios.create(a));
                    scenarios.p5After(a, b, scenarios.create(a), "p5-after-create");
                    scenarios.p5After(a, b, scenarios.publish(a, seeds.get(1)), "p5-after-publish");
                    lost = List.of(scenarios.p4(a, scenarios.create(a), "p4-create"),
                            scenarios.p4(a, scenarios.publish(a, seeds.get(2)), "p4-publish"));
                }
                noSessions(work, proof, "b34-a");
                try (var restarted = B34App.start(root, "b34-restarted", state.child, B34State.CLOCK)) {
                    B33Evidence.require(restarted.pid() != originalPid, "Service restart reused original process identity.");
                    verifyApps(restarted, b, work, proof);
                    scenarios.retryLost(lost, b, restarted);
                    scenarios.expire(lost, b, restarted);
                }
                noSessions(work, proof, "b34-restarted");
            }
            noSessions(work, proof, "b34-b");
        }
        audit(root, sensitive);
    }

    private static void verifyApps(B34App a, B34App b, Connection work, Connection proof) throws Exception {
        B33Evidence.require(a.pid() != b.pid() && a.port != b.port, "Expected separate service JVMs and listeners.");
        for (Store store : Store.values()) {
            var read = store == Store.WORKFLOW ? work : proof;
            var observer = B32Database.read(read, store);
            var expected = store == Store.WORKFLOW ? B33Profile.Role.COMMAND : B33Profile.Role.CREATE;
            B33Evidence.require(Collections.disjoint(a.pool(store), b.pool(store)), "Workers share a pool backend.");
            for (var app : List.of(a, b)) {
                var identity = app.identity(store);
                var pool = app.pool(store);
                B33Evidence.require(identity.endpoint().startsWith("127.0.0.1/" + store.port + "/" + store.database + "/180007/")
                        && identity.endpoint().equals(observer.endpoint()) && identity.user().equals(expected.login)
                        && identity.schema().equals(observer.schema()) && identity.schema().contains("/b04_admin/")
                        && identity.migrator().equals(observer.migrator()) && pool.size() == 2
                        && !pool.contains(observer.pid()) && pool.contains(identity.pid()), "Wrong role or store identity.");
                for (int backend : pool) {
                    B33Evidence.require(B32Fixture.liveApp(read, backend).equals(app.name), "Wrong live pool owner.");
                }
                System.out.printf("PASS %s %s endpoint=%s schema=%s observer=%d backends=%s migrator=%s%n",
                        app.name, store, identity.endpoint(), identity.schema(), observer.pid(), pool, identity.migrator());
            }
        }
    }

    private static void noSessions(Connection work, Connection proof, String name) throws Exception {
        for (var observer : List.of(work, proof)) {
            long deadline = System.nanoTime() + Duration.ofMillis(700).toNanos();
            String remaining;
            do {
                remaining = scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE application_name=?", name);
                if (remaining.equals("0")) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            B33Evidence.require(remaining.equals("0"), "Child backend survived shutdown.");
        }
        System.out.println("PASS " + name + " workflow and proof sessions closed.");
    }

    private static void audit(Path root, Set<String> sensitive) throws Exception {
        try (var paths = Files.walk(root, 2)) {
            var logs = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".log")).toList();
            B33Evidence.require(!logs.isEmpty(), "Expected owned evidence logs.");
            for (var log : logs) {
                String content = Files.readString(log);
                B33Evidence.require(sensitive.stream().noneMatch(content::contains), "Private input reached an evidence log.");
            }
            System.out.printf("PASS redaction audit scanned %d owned logs%n", logs.size());
        }
    }

    private static void scan(String runtime) throws Exception {
        B33Harness.main(new String[]{"scan", runtime});
        for (String item : runtime.split(File.pathSeparator)) {
            try (var jar = new JarFile(item)) {
                B33Evidence.require(jar.stream().noneMatch(entry ->
                        Path.of(entry.getName()).getFileName().toString().startsWith("B34")),
                        "B34 control leaked into packaged runtime.");
            }
        }
        System.out.println("PASS packaged synthetic runtime excludes B34 controls.");
    }

    private static void controls(String runtime) throws Exception {
        scan(runtime);
        byte[] complete = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n").getBytes(US_ASCII);
        var reply = B34App.parse(complete);
        B33Evidence.require(reply.status() == 200 && reply.json().isObject(), "Wire fixture did not decode known body.");
        try {
            B34App.parse(Arrays.copyOf(complete, complete.length - 5));
            throw new IllegalStateException("Truncated wire evidence passed.");
        } catch (AssertionError expected) {
            B33Evidence.require(expected.getMessage().equals("Incomplete local HTTP response."),
                    "Wrong truncated wire control failure.");
            System.out.println("RED rejected truncated HTTP response evidence.");
        }
        var before = new B34State.Snapshot(null, "business", "receipt", "proof", 0, 0, 0, 0, 0, 0);
        var changed = new B34State.Snapshot(null, "changed", "receipt", "proof", 0, 0, 0, 0, 0, 0);
        try {
            B34State.stableBusiness(before, changed);
            throw new IllegalStateException("Unexpected effect passed.");
        } catch (AssertionError expected) {
            B33Evidence.require(expected.getMessage().equals("An additional business effect or changed durable row was observed."),
                    "Wrong changed effects control failure.");
            System.out.println("RED rejected changed durable effects.");
        }
        var counts = new B33Evidence.Counts(List.of(), List.of(), 1);
        var live = new B34State.Snapshot(counts, "business", "live", "proof", 1, 1, 1, 1, 7, 0);
        var deleted = new B34State.Snapshot(counts, "business", "deleted", "proof", 1, 1, 1, 1, 7, 0);
        var retired = new B34State.Snapshot(counts, "business", "deleted", "proof", 1, 1, 1, 1, 7, 1);
        B34State.cleanedReceipt(live, deleted, false);
        B34State.cleanedReceipt(live, retired, true);
        try {
            B34State.cleanedReceipt(live, deleted, true);
            throw new IllegalStateException("Missing Publish retirement passed.");
        } catch (AssertionError expected) {
            B33Evidence.require(expected.getMessage().equals(
                    "Receipt cleanup did not add the expected publication retirement evidence."),
                    "Wrong missing retirement control failure.");
            System.out.println("RED rejected Publish deletion without its retirement evidence.");
        }
    }
}
