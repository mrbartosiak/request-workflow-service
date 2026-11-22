import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.company.requests.bootstrap.B32Database.scalar;

/** Repeatable read-only state and raw protocol oracles for B33 and successor cases. */
final class B33Evidence {
    private static final List<String> WORKFLOW = List.of("requests.request", "requests.request_body",
            "requests.transition_fact", "requests.request_assignment", "requests.operation_receipt",
            "(SELECT * FROM requests.operation_receipt WHERE result_ref IS NOT NULL) finalized",
            "requests.scope_authority_witness", "requests.scope_grant_override", "requests.request_visibility_fact",
            "requests.transition_reason");
    private static final List<String> PROOF = List.of("request_authority.creation_reservation",
            "request_authority.release_result_prepared", "request_authority.release_handoff_proved",
            "request_authority.release_client_acknowledged", "request_authority.release_prepared",
            "request_authority.scope_journal_attempt", "request_authority.scope_journal_confirmation");
    private static final Set<String> SAFE_HEADERS = Set.of("cache-control", "content-type", "content-length",
            "transfer-encoding", "date", "location", "connection");
    private static final String PROBLEM_BASE = "urn:requests:problem:";

    private B33Evidence() { }

    record Counts(List<Long> workflow, List<Long> proof, long latestCreationOrdinal) {
        Counts plus(Delta delta) {
            return new Counts(add(workflow, delta.workflow), add(proof, delta.proof),
                    latestCreationOrdinal + (delta == Delta.CREATE ? 1 : 0));
        }

        private static List<Long> add(List<Long> counts, int[] changes) {
            return IntStream.range(0, changes.length).mapToObj(i -> counts.get(i) + changes[i]).toList();
        }
    }

    enum Delta {
        CREATE(new int[]{1, 1, 1, 1, 1, 1, 0, 0, 0, 0}, new int[]{1, 1, 1, 0, 0, 0, 0}),
        EDIT(new int[]{0, 0, 0, 0, 1, 1, 0, 0, 0, 0}, new int[]{0, 1, 1, 0, 0, 0, 0}),
        TRANSITION(new int[]{0, 0, 1, 0, 1, 1, 0, 0, 0, 0}, new int[]{0, 1, 1, 0, 0, 0, 0}),
        RELEASE(new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, new int[]{0, 1, 1, 0, 0, 0, 0}),
        CONFIRMED_SCOPE(new int[]{0, 0, 0, 0, 0, 0, 1, 0, 0, 0}, new int[]{0, 0, 0, 0, 0, 1, 1});

        final int[] workflow;
        final int[] proof;

        Delta(int[] workflow, int[] proof) {
            this.workflow = workflow;
            this.proof = proof;
        }
    }

    record BusinessFingerprint(String workflow, String reservations) { }

    static Counts read(Connection work, Connection proof) throws SQLException {
        long ordinal = Long.parseLong(scalar(proof, "SELECT last_ordinal FROM request_authority.creation_counter"));
        return new Counts(counts(work, WORKFLOW), counts(proof, PROOF), ordinal);
    }

    static Counts read(String stage, Connection work, Connection proof) throws SQLException {
        var observed = read(work, proof);
        System.out.printf("COUNTS %s workflow=%s proof=%s creation-ordinal=%d at=%s%n",
                stage, observed.workflow, observed.proof, observed.latestCreationOrdinal, Instant.now());
        return observed;
    }

    private static List<Long> counts(Connection connection, List<String> tables) throws SQLException {
        String values = String.join(", ", tables.stream().map(table -> "(SELECT count(*) FROM " + table + ")").toList());
        return Arrays.stream(scalar(connection, "SELECT concat_ws('/', " + values + ")").split("/"))
                .map(Long::valueOf).toList();
    }

    static void counts(String stage, Counts observed, Counts expected) {
        require(observed.equals(expected), "Unexpected durable counts at " + stage
                + " actual=" + observed + " expected=" + expected);
    }

    static BusinessFingerprint fingerprint(Connection work, Connection proof) throws Exception {
        String workflow = fingerprint(work, WORKFLOW.subList(0, 5));
        String reservations = fingerprint(proof, List.of(PROOF.getFirst(), "request_authority.creation_counter"));
        return new BusinessFingerprint(workflow, reservations);
    }

    private static String fingerprint(Connection connection, List<String> tables) throws Exception {
        var projections = tables.stream().map(table -> "(SELECT COALESCE(jsonb_agg(value ORDER BY value), '[]') "
                + "FROM (SELECT to_jsonb(t) AS value FROM " + table + " t) data)").toList();
        return digest(scalar(connection, "SELECT jsonb_build_array(" + String.join(", ", projections) + ")::text"));
    }

    static void verifyObserver(Connection observer, Store store) throws Exception {
        require(scalar(observer, "SELECT session_user").equals("b04_observer")
                && scalar(observer, "SHOW transaction_read_only").equals("on"), "Expected dedicated read-only observer.");
        String write = store == Store.WORKFLOW ? "UPDATE requests.policy_guard SET policy_version = 1 WHERE false"
                : "DELETE FROM request_authority.release_result_prepared WHERE false";
        try (var statement = observer.createStatement()) {
            denied(statement, write, "25006");
            statement.execute("SET default_transaction_read_only = off");
            try {
                denied(statement, write, "42501");
            } finally {
                statement.execute("SET default_transaction_read_only = on");
            }
        }
        System.out.printf("PASS %s independent observer pid=%s read-only=25006 privileges=42501%n",
                store, scalar(observer, "SELECT pg_backend_pid()"));
    }

    private static void denied(java.sql.Statement statement, String sql, String state) throws SQLException {
        try {
            statement.execute(sql);
            throw new AssertionError("Observer may write.");
        } catch (SQLException failure) {
            require(state.equals(failure.getSQLState()), "Wrong observer SQLSTATE.");
        }
    }

    static void verifyApps(B33App a, B33App b, Connection work, Connection proof) throws Exception {
        require(a.alive() && b.alive() && a.pid() != b.pid() && a.port != b.port, "Expected separate live JVMs/listeners.");
        for (Store store : Store.values()) {
            var connection = store == Store.WORKFLOW ? work : proof;
            var observer = B32Database.read(connection, store);
            var user = store == Store.WORKFLOW ? B33Profile.Role.COMMAND.login : B33Profile.Role.CREATE.login;
            var schema = store == Store.WORKFLOW ? "1,2,3,4,5,6,7,8,9" : "1,2,3,4,5";
            require(Collections.disjoint(a.pool(store), b.pool(store)), "Pool backends shared between workers.");
            for (B33App app : List.of(a, b)) {
                var server = app.identity(store);
                var pool = app.pool(store);
                require(server.endpoint().startsWith("127.0.0.1/" + store.port + "/" + store.database + "/180007/")
                        && server.endpoint().equals(observer.endpoint()) && server.schema().equals(observer.schema())
                        && server.schema().endsWith("/b04_admin/" + schema) && server.migrator().equals(observer.migrator())
                        && server.user().equals(user) && server.application().equals(app.name)
                        && pool.size() == 2 && pool.stream().distinct().count() == 2 && pool.contains(server.pid())
                        && !pool.contains(observer.pid()), "Incorrect server, role, schema or bounded test pool.");
                for (int backend : pool) {
                    require(B32Fixture.liveApp(connection, backend).equals(app.name), "Unexpected live pool backend.");
                }
                System.out.printf("PASS %s %s endpoint=%s schema=%s observer=%d pool=%s migrator=%s%n",
                        app.name, store, server.endpoint(), server.schema(), observer.pid(), pool, server.migrator());
            }
        }
    }

    static void poolsClosed(Connection work, Connection proof) throws Exception {
        for (var observer : List.of(work, proof)) {
            long deadline = System.nanoTime() + java.time.Duration.ofMillis(600).toNanos();
            String remaining;
            do {
                remaining = scalar(observer, """
                        SELECT count(*) FROM pg_stat_activity
                        WHERE application_name IN ('b33-a', 'b33-b') AND datname = current_database()
                        """);
                if (remaining.equals("0")) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            require(remaining.equals("0"), "Worker pool backend survived teardown.");
        }
        System.out.println("PASS all child workflow and independent proof backends closed.");
    }

    static void exactBlocker(String observed, int a) {
        require(observed.equals("{" + a + "}"), "Missing exact PostgreSQL blocker.");
    }

    static void traces(String stage, B33App app, int first, String... expected) throws Exception {
        var all = app.traces();
        var actual = all.subList(first, all.size()).stream().map(trace -> trace.operation() + ":" + trace.sqlstate()).toList();
        require(actual.equals(List.of(expected)), "Wrong receipt claim/lookup sequence at " + stage + " actual=" + actual);
        System.out.printf("PASS %s %s JDBC=%s%n", stage, app.name, actual);
    }

    static void success(B33App.Reply reply, int status, String action, String ref, long revision, String state) {
        require(reply.status() == status, "Expected HTTP " + status + " " + action + ", got " + summary(reply));
        var expected = B33App.JSON.valueToTree(Map.of("ref", ref, "action", action, "resultRevision",
                Long.toString(revision), "state", state));
        require(reply.json().equals(expected) && reply.header("location").equals(status == 201 ? "/requests/" + ref : "")
                && reply.header("content-type").equalsIgnoreCase("application/json;charset=UTF-8"),
                "Mutation envelope or Location differs.");
        assertSafeHeaders(reply);
    }

    static void sameSuccess(String label, B33App.Reply original, B33App.Reply replay) throws Exception {
        require(original.status() == replay.status() && original.stableHeaders().equals(replay.stableHeaders())
                && Arrays.equals(original.body(), replay.body()), "Original HTTP status, safe headers or exact body differ.");
        System.out.printf("PASS %s HTTP=%d safe-headers=%s body-sha256=%s location-sha256=%s%n",
                label, original.status(), original.stableHeaders().keySet(),
                B32Database.hash(original.body()), digest(original.header("location")));
    }

    static void problem(B33App.Reply reply, int status, String code) {
        require(reply.status() == status, "Expected HTTP " + status + "/" + code + ", got " + summary(reply));
        var body = reply.json();
        String correlation = body.path("correlationId").asString();
        String title = switch (code) {
            case "not_found" -> "Not found";
            case "outcome_unknown" -> "Outcome unknown";
            case "key_conflict" -> "Key conflict";
            case "key_expired" -> "Key expired";
            default -> throw new IllegalArgumentException("Unexpected B33 probe problem type.");
        };
        require(body.size() == 5 && body.path("type").asString().equals(PROBLEM_BASE + code)
                && body.path("status").asInt() == status && body.path("instance").asString().equals("urn:uuid:" + correlation)
                && UUID.fromString(correlation).toString().equals(correlation) && body.path("title").asString().equals(title)
                && reply.header("location").isEmpty()
                && reply.header("content-type").matches("(?i)application/problem\\+json(?:;\\s*charset=UTF-8)?"),
                "Unexpected fields, correlation or headers in HTTP problem.");
        assertSafeHeaders(reply);
    }

    private static void assertSafeHeaders(B33App.Reply reply) {
        var length = reply.header("content-length");
        var transfer = reply.header("transfer-encoding");
        var connection = reply.header("connection");
        boolean framed = (length.equals(Integer.toString(reply.body().length))
                && transfer.isEmpty()) || (length.isEmpty() && transfer.equals("chunked"));
        require(SAFE_HEADERS.containsAll(reply.headers().keySet()) && reply.header("cache-control").equals("no-store")
                && Set.of("", "close", "keep-alive").contains(connection) && framed,
                "Unexpected headers or body framing. Header names=" + reply.headers().keySet()
                        + " framing-valid=" + framed);
    }

    static String summary(B33App.Reply reply) {
        String type;
        try {
            var body = reply.json();
            type = body == null ? "" : body.path("type").asString();
        } catch (tools.jackson.core.JacksonException malformed) {
            return Integer.toString(reply.status());
        }
        return reply.status() + (type.matches("urn:requests:problem:[a-z_]+") ? "/" + type.substring(PROBLEM_BASE.length()) : "");
    }

    static void sameDenial(B33App.Reply ordinary, B33App.Reply concealed) throws Exception {
        problem(ordinary, 404, "not_found");
        problem(concealed, 404, "not_found");
        var left = B33App.JSON.createObjectNode();
        var right = B33App.JSON.createObjectNode();
        for (String field : List.of("type", "status", "title")) {
            left.set(field, ordinary.json().get(field));
            right.set(field, concealed.json().get(field));
        }
        require(ordinary.stableHeaders().equals(concealed.stableHeaders()) && left.equals(right)
                && !ordinary.json().path("correlationId").asString().equals(concealed.json().path("correlationId").asString()),
                "Concealed and ordinary HTTP denials differ.");
        System.out.printf("PASS normalized ordinary and concealed HTTP 404 safe-headers=%s body-sha256=%s%n",
                concealed.stableHeaders().keySet(), digest(left.toString()));
    }

    static String digest(String value) throws Exception {
        return B32Database.hash(value.getBytes(StandardCharsets.UTF_8));
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void failure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            var frames = cause.getStackTrace();
            String frame = frames.length == 0 ? "unknown" : frames[0].toString();
            var message = cause instanceof AssertionError && frame.startsWith("B33")
                    ? " " + cause.getMessage() : cause instanceof SQLException sql ? " SQLSTATE=" + sql.getSQLState() : "";
            System.err.println("B33 " + cause.getClass().getSimpleName() + message + " at " + frame);
            Arrays.stream(frames).skip(1).limit(6).forEach(element -> System.err.println("    at " + element));
            for (Throwable suppressed : cause.getSuppressed()) {
                var at = suppressed.getStackTrace();
                System.err.printf("B33 additional failure %s at %s%n",
                        suppressed.getClass().getSimpleName(), at.length == 0 ? "unknown" : at[0]);
            }
        }
    }
}
