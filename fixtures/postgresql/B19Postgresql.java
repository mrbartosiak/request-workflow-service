import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Native detail oracle using the registered MVC routes and independently observed guard waits. */
public final class B19Postgresql {
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor PUBLISHER = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor OPERATOR = B10Postgresql.OPERATOR;
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final RequestRef ABSENT = new RequestRef("ordinary-absent");
    private static final String NAME = "  Ticket 東京  ";
    private static final String BODY = "  Retained body e\u0301 🚦\n ";
    private static final String EDITED = "  Edited body 🚦  ";
    private static final String REASON = "  Test terminal reason  ";
    private static final String WITNESS = "INSERT INTO requests.scope_authority_witness";
    private static final JsonMapper JSON = new JsonMapper();

    private final B17Postgresql base;
    private final AtomicReference<Pause> pause = new AtomicReference<>();
    private final AtomicInteger guardLocks = new AtomicInteger();
    private final AtomicInteger bodyReads = new AtomicInteger();

    private B19Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        base = new B17Postgresql(workflow, proof, projector, resolver, createProof, logins, sources, this::observe);
        var migrations = new JdbcWorkflowMigrations(base.sql.getDataSource());
        migrations.migrateDetailGrants();
        migrations.migrateDetailGrants();
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.requestDetail(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B19Postgresql(workflow, proof, projector, resolver, createProof, logins, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B19 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        try (var mvc = new B08MvcSession(base.settings)) {
            validateBeforeStorage(mvc);
            liveProjectionAndOriginalReceipt(mvc);
            largeEscapedBody(mvc);
            deleteOrder(mvc, true);
            deleteOrder(mvc, false);
            revocationOrder(mvc, true);
            revocationOrder(mvc, false);
            publicationProof(mvc);
        }
    }

    private void validateBeforeStorage(B08MvcSession mvc) throws Exception {
        int guardBefore = guardLocks.get();
        var unauthenticated = mvc.raw(MockMvcRequestBuilders.get("/requests/{ref}", ABSENT.value())
                .queryParam("unexpected", "value"), "");
        problem(unauthenticated, 401, "authentication_required", "Authentication required");
        require(unauthenticated.getHeaders(HttpHeaders.WWW_AUTHENTICATE).equals(List.of("Bearer")),
                "missing authentication challenge");
        var badQuery = mvc.raw(MockMvcRequestBuilders.get("/requests/{ref}", ABSENT.value())
                .queryParam("unexpected", "value"), base.ownerBearer);
        var tooLong = mvc.raw(MockMvcRequestBuilders.get("/requests/{ref}", "r".repeat(129)), base.ownerBearer);
        var hasBody = mvc.raw(MockMvcRequestBuilders.get("/requests/{ref}", ABSENT.value()).content("{}"),
                base.ownerBearer);
        for (var response : List.of(badQuery, tooLong, hasBody)) {
            problem(response, 400, "invalid_input", "Invalid input");
        }
        require(JSON.readTree(tooLong.getContentAsString()).path("invalidFields").get(0).asString().equals("ref")
                        && guardBefore == guardLocks.get(), "invalid request reached guarded storage");
        System.out.println("PASS 401 Bearer challenge before validation; invalid query, reference and body rejected before storage");
    }

    private void liveProjectionAndOriginalReceipt(B08MvcSession mvc) throws Exception {
        var created = create(mvc);
        var ref = created.ref();
        var absent = detail(mvc, ABSENT, base.ownerBearer);
        equivalent404(absent, detail(mvc, ref, base.ownerBearer));
        base.enable(OWNER, TYPE, Grant.ROW_READ);
        view(detail(mvc, ref, base.ownerBearer), Map.of("ref", ref.value()));
        base.enable(OWNER, TYPE, Grant.FIELD_READ_TYPE, Grant.FIELD_READ_NAME, Grant.FIELD_READ_BODY,
                Grant.FIELD_READ_STATE, Grant.FIELD_READ_REVISION, Grant.CONTEXT_EDIT, Grant.EXECUTE_EDIT,
                Grant.RESULT_READ_EDIT, Grant.CONTEXT_DELETE, Grant.EXECUTE_DELETE, Grant.RESULT_READ_DELETE);
        base.enable(PUBLISHER, TYPE, Grant.ROW_READ, Grant.FIELD_READ_NAME, Grant.FIELD_READ_BODY, Grant.FIELD_READ_STATE);
        view(detail(mvc, ref, base.publisherBearer), Map.of("ref", ref.value(), "state", "CREATED"));
        var live = live(ref, BODY, "1");
        view(detail(mvc, ref, base.ownerBearer), live);
        revoke(OWNER, Grant.FIELD_READ_REVISION);
        var masked = new TreeMap<>(live);
        masked.remove("revision");
        view(detail(mvc, ref, base.ownerBearer), masked);
        base.enable(OWNER, TYPE, Grant.FIELD_READ_REVISION);
        revoke(OWNER, Grant.CREATE_RESULT);
        view(detail(mvc, ref, base.ownerBearer), live);
        base.enable(OWNER, TYPE, Grant.CREATE_RESULT);

        // Fixture-only counter setup on the same organization guard exercises JavaScript-unsafe revisions.
        seed(() -> require(base.sql.update("UPDATE requests.request SET revision = ? WHERE ref = ?",
                9_007_199_254_740_992L, ref.value()) == 1, "revision seed"));
        var editKey = B17Postgresql.issue(mvc, base.ownerBearer);
        var observation = B17Postgresql.observation(mvc, ref, "edit", base.ownerBearer);
        var edited = edit(mvc, ref, editKey, observation);
        require(edited.status() == 200 && edited.json().path("resultRevision").asString().equals("9007199254740993"),
                "large Edit result revision was not exact");
        view(detail(mvc, ref, base.ownerBearer), live(ref, EDITED, "9007199254740993"));
        require(created.reply().equals(mvc.invoke(created.call()))
                && created.reply().json().path("resultRevision").asString().equals("1"),
                "Create replay changed the original receipt or Location after Edit");
        for (String revision : List.of("1", "9007199254740993")) {
            var rejected = edit(mvc, ref, B17Postgresql.issue(mvc, base.ownerBearer), revision);
            require(rejected.status() == 400 && rejected.json().path("type").asString().endsWith(":invalid_input"),
                    "descriptive revision was accepted as an observation");
        }
        require(base.sql.queryForObject("SELECT count(*) FROM requests.operation_receipt WHERE result_ref = ?",
                Long.class, ref.value()) == 2, "detail or invalid revision produced a receipt");
        base.baseline.confirm(base.profile.updates().stageVisibility(OPERATOR, ref, false,
                Set.of(Field.REF, Field.ACTION, Field.RESULT_REVISION, Field.BODY)));
        view(detail(mvc, ref, base.ownerBearer), Map.of("ref", ref.value(), "type", TYPE.name(),
                "body", EDITED, "revision", "9007199254740993"));
        seed(() -> require(base.sql.update("""
                UPDATE requests.request_body SET retention_status = 'PURGED', body = NULL WHERE ref = ?
                """, ref.value()) == 1, "retention seed"));
        view(detail(mvc, ref, base.ownerBearer), Map.of("ref", ref.value(), "type", TYPE.name(),
                "revision", "9007199254740993"));
        System.out.println("PASS separate row and field grants, assignment/visibility masks, retained body, and exact current revision after Edit");
        System.out.println("PASS original Create receipt and Location preserved; both descriptive revisions rejected as observations");
    }

    private void deleteOrder(B08MvcSession mvc, boolean readFirst) throws Exception {
        var created = create(mvc);
        var ref = created.ref();
        var key = B17Postgresql.issue(mvc, base.ownerBearer);
        var payload = Map.of("expectedRevision", B17Postgresql.observation(mvc, ref, "delete", base.ownerBearer),
                "reason", REASON);
        var absent = detail(mvc, ABSENT, base.ownerBearer);
        try (var workers = Executors.newFixedThreadPool(2);
             var observer = Objects.requireNonNull(base.sql.getDataSource()).getConnection()) {
            var cut = arm(readFirst);
            try {
                if (readFirst) {
                    var reading = workers.submit(() -> detail(mvc, ref, base.ownerBearer));
                    await(cut.entered());
                    var deleting = workers.submit(() -> action(mvc, ref, "delete", key, payload, base.ownerBearer));
                    nativeWait(observer, "UPDATE");
                    cut.resume().countDown();
                    view(reading.get(4, SECONDS), live(ref, BODY, "1"));
                    unavailable(deleting.get(4, SECONDS));
                } else {
                    var deleting = workers.submit(() -> action(mvc, ref, "delete", key, payload, base.ownerBearer));
                    await(cut.entered());
                    int materialBefore = bodyReads.get();
                    var reading = workers.submit(() -> detail(mvc, ref, base.ownerBearer));
                    nativeWait(observer, "SHARE");
                    cut.resume().countDown();
                    unavailable(deleting.get(4, SECONDS));
                    unavailable(reading.get(4, SECONDS));
                    require(bodyReads.get() == materialBefore, "waiting detail read protected body before scope proof");
                }
            } finally {
                cut.resume().countDown();
            }
        }
        unavailable(detail(mvc, ref, base.ownerBearer));
        base.baseline.confirm(deleteWitness(ref));
        equivalent404(absent, detail(mvc, ref, base.ownerBearer));
        base.enable(OWNER, TYPE, Grant.DELETED_METADATA);
        view(detail(mvc, ref, base.ownerBearer), Map.of("ref", ref.value(), "state", "DELETED"));
        revoke(OWNER, Grant.FIELD_READ_STATE);
        view(detail(mvc, ref, base.ownerBearer), Map.of("ref", ref.value()));
        base.enable(OWNER, TYPE, Grant.FIELD_READ_STATE);
        var replay = action(mvc, ref, "delete", key, payload, base.ownerBearer);
        require(replay.status() == 200 && replay.json().equals(JSON.valueToTree(Map.of("ref", ref.value(),
                "state", "DELETED", "action", "delete", "resultRevision", "2"))), "Delete lost exact minimal reply");
        require(mvc.invoke(created.call()).status() == 404, "pre-delete receipt leaked");
        require(Boolean.TRUE.equals(base.sql.queryForObject("""
                SELECT r.revision = 2 AND r.state = 'DELETED' AND r.last_sequence = 2
                    AND b.retention_status = 'RETAINED' AND b.body = ?
                    AND p.retention_status = 'RETAINED' AND p.reason = ?
                FROM requests.request r
                JOIN requests.request_body b USING (organization, ref)
                JOIN requests.transition_reason p USING (organization, ref)
                WHERE r.ref = ?
                """, Boolean.class, BODY, REASON, ref.value())), "Delete mask lost retained protected facts");
        revoke(OWNER, Grant.DELETED_METADATA);
        System.out.println("PASS " + (readFirst ? "detail before Delete" : "Delete before detail")
                + "; pending proof fails closed and confirmed tombstone contains only independently permitted metadata");
    }

    private void largeEscapedBody(B08MvcSession mvc) throws Exception {
        var body = "\u0001".repeat(16_384);
        var created = B17Postgresql.call(mvc, "/requests", base.ownerBearer, B17Postgresql.issue(mvc, base.ownerBearer),
                Map.of("type", TYPE.name(), "name", NAME, "body", body));
        require(created.status() == 201, "maximum escaped body could not be created");
        var ref = new RequestRef(created.json().path("ref").asString());
        var response = detail(mvc, ref, base.ownerBearer);
        require(response.getStatus() == 200 && response.getContentAsByteArray().length > 98_304
                && JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).equals(JSON.valueToTree(live(ref, body, "1"))),
                "maximum escaped body did not fit the bounded detail buffer");
        System.out.println("PASS maximum 16 KiB escaped body fits guarded buffer and preserves exact text");
    }

    private void revocationOrder(B08MvcSession mvc, boolean readFirst) throws Exception {
        var ref = create(mvc).ref();
        var absent = detail(mvc, ABSENT, base.ownerBearer);
        PendingConfirmation pending;
        try (var workers = Executors.newFixedThreadPool(2);
             var observer = Objects.requireNonNull(base.sql.getDataSource()).getConnection()) {
            var cut = arm(readFirst);
            try {
                if (readFirst) {
                    var reading = workers.submit(() -> detail(mvc, ref, base.ownerBearer));
                    await(cut.entered());
                    var revoking = workers.submit(() -> stageRevocation(OWNER, Grant.ROW_READ));
                    nativeWait(observer, "UPDATE");
                    cut.resume().countDown();
                    view(reading.get(4, SECONDS), live(ref, BODY, "1"));
                    pending = revoking.get(4, SECONDS);
                } else {
                    var revoking = workers.submit(() -> stageRevocation(OWNER, Grant.ROW_READ));
                    await(cut.entered());
                    int materialBefore = bodyReads.get();
                    var reading = workers.submit(() -> detail(mvc, ref, base.ownerBearer));
                    nativeWait(observer, "SHARE");
                    cut.resume().countDown();
                    pending = revoking.get(4, SECONDS);
                    unavailable(reading.get(4, SECONDS));
                    require(bodyReads.get() == materialBefore, "waiting detail read protected body before revocation proof");
                }
            } finally {
                cut.resume().countDown();
            }
        }
        unavailable(detail(mvc, ref, base.ownerBearer));
        base.baseline.confirm(pending);
        equivalent404(absent, detail(mvc, ref, base.ownerBearer));
        base.enable(OWNER, TYPE, Grant.ROW_READ);
        System.out.println("PASS " + (readFirst ? "detail before revocation" : "revocation before detail")
                + "; confirmed grant revocation has the ordinary 404 response");
    }

    private void publicationProof(B08MvcSession mvc) throws Exception {
        base.enable(OWNER, TYPE, Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY);
        base.enable(PUBLISHER, TYPE, Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT,
                Grant.CONTEXT_PUBLISH, Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_REVISION);
        var ref = base.accepted(mvc, TYPE);
        var key = B17Postgresql.issue(mvc, base.publisherBearer);
        var payload = Map.of("expectedRevision", B17Postgresql.observation(mvc, ref, "publish", base.publisherBearer));
        unavailable(action(mvc, ref, "publish", key, payload, base.publisherBearer));
        var publication = base.binding(TYPE, ref);
        require(publication.state() == State.PENDING, "expected durable pending publication");
        unavailable(detail(mvc, ref, base.publisherBearer));
        base.baseline.confirm(base.scopeWitness(ref));
        unavailable(detail(mvc, ref, base.publisherBearer));
        equivalent404(detail(mvc, ABSENT, base.ownerBearer), detail(mvc, ref, base.ownerBearer));
        base.confirm(publication);
        var fields = new TreeMap<>(Map.of("ref", ref.value(), "name", "Exact synthetic name",
                "body", "Exact synthetic body", "state", "PUBLISHED", "revision", "4"));
        view(detail(mvc, ref, base.publisherBearer), fields);
        base.enable(PUBLISHER, TYPE, Grant.FIELD_READ_PUBLICATION_ID);
        fields.put("publicationId", "9007199254740993");
        view(detail(mvc, ref, base.publisherBearer), fields);
        var receipt = action(mvc, ref, "publish", key, payload, base.publisherBearer);
        require(receipt.status() == 200 && receipt.json().path("publicationId").asString().equals("9007199254740993")
                && receipt.json().path("resultRevision").asString().equals("4"), "original publication receipt changed");
        System.out.println("PASS otherwise-visible pending Publish defers with current scope; concealed Publish is ordinary 404");
        System.out.println("PASS confirmed publication ID is independently granted and an exact decimal string");
    }

    private Created create(B08MvcSession mvc) throws Exception {
        var call = B08MvcSession.Call.create(base.ownerBearer, B17Postgresql.issue(mvc, base.ownerBearer),
                JSON.writeValueAsString(Map.of("type", TYPE.name(), "name", NAME, "body", BODY)));
        var reply = mvc.invoke(call);
        require(reply.status() == 201, "Create setup returned " + reply.status());
        return new Created(call, reply, new RequestRef(reply.json().path("ref").asString()));
    }

    private B08MvcSession.Reply edit(B08MvcSession mvc, RequestRef ref, String key, String observation) throws Exception {
        return mvc.invoke(MockMvcRequestBuilders.patch(path(ref) + "/body").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("body", EDITED, "expectedRevision", observation))), base.ownerBearer);
    }

    private static B08MvcSession.Reply action(B08MvcSession mvc, RequestRef ref, String action, String key,
                                             Map<String, String> payload, String bearer) throws Exception {
        return B17Postgresql.call(mvc, path(ref) + "/actions/" + action, bearer, key, payload);
    }

    private PendingConfirmation deleteWitness(RequestRef ref) {
        return base.sql.queryForObject("""
                SELECT s.attempt_id, s.sequence, s.after_version
                FROM requests.scope_authority_witness s
                JOIN requests.request_visibility_fact v ON v.organization = s.organization AND v.applied_version = s.after_version
                WHERE v.ref = ?
                """, (row, index) -> new PendingConfirmation(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3)),
                ref.value());
    }

    private PendingConfirmation stageRevocation(AuthenticatedActor actor, Grant permission) {
        return base.profile.updates().stageRevocation(OPERATOR, new GrantKey(actor, permission, Optional.of(TYPE)));
    }

    private void revoke(AuthenticatedActor actor, Grant permission) {
        base.baseline.confirm(stageRevocation(actor, permission));
    }

    private void seed(Runnable update) {
        var source = base.sql.getDataSource();
        var guards = new JdbcWorkflowRecords(source);
        new JdbcTransactionRunner(source).run(() -> {
            guards.lockExclusive(OWNER.organization()).orElseThrow();
            update.run();
            return null;
        });
    }

    private static MockHttpServletResponse detail(B08MvcSession mvc, RequestRef ref, String bearer) throws Exception {
        return mvc.raw(MockMvcRequestBuilders.get("/requests/{ref}", ref.value()), bearer);
    }

    private static String path(RequestRef ref) {
        return "/requests/" + ref.value();
    }

    private static Map<String, String> live(RequestRef ref, String body, String revision) {
        return Map.of("ref", ref.value(), "type", TYPE.name(), "name", NAME, "body", body,
                "state", "CREATED", "revision", revision);
    }

    private static void view(MockHttpServletResponse response, Map<String, String> expected) throws Exception {
        var body = response.getContentAsString(StandardCharsets.UTF_8);
        require(response.getStatus() == 200 && JSON.readTree(body).equals(JSON.valueToTree(expected)),
                "detail projection differs from expected fields " + expected.keySet() + " at status " + response.getStatus());
        require(headers(response).equals(Map.of("Cache-Control", List.of("no-store"), "Content-Type",
                List.of("application/json;charset=UTF-8"), "Content-Length",
                List.of(Integer.toString(response.getContentAsByteArray().length)))), "detail protocol headers differ");
        System.out.printf("WIRE %d %s %s%n", response.getStatus(), headers(response), body);
    }

    static ObjectNode problem(MockHttpServletResponse response, int status, String code, String title)
            throws Exception {
        require(response.getStatus() == status && response.getContentType() != null
                && MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(response.getContentType()))
                && "no-store".equals(response.getHeader("Cache-Control")) && response.getHeader("Location") == null,
                "expected problem " + status + " " + code + ", got " + response.getStatus() + " " + headers(response));
        var body = (ObjectNode) JSON.readTree(response.getContentAsString());
        var correlation = UUID.fromString(body.path("correlationId").asString());
        require(body.path("instance").asString().equals("urn:uuid:" + correlation)
                        && body.path("status").asInt() == status
                        && body.path("type").asString().equals("urn:requests:problem:" + code)
                        && body.path("title").asString().equals(title)
                        && body.size() == (body.has("invalidFields") && status == 400 ? 6 : 5),
                "unexpected problem schema for " + code);
        body.put("instance", "urn:uuid:<correlation>");
        body.put("correlationId", "<correlation>");
        System.out.printf("WIRE %d %s %s%n", status, headers(response), body);
        return body;
    }

    private static void unavailable(MockHttpServletResponse response) throws Exception {
        problem(response, 503, "authority_unavailable", "Authority unavailable");
    }

    private static void unavailable(B08MvcSession.Reply response) {
        require(response.status() == 503 && response.json().path("type").asString()
                .equals("urn:requests:problem:authority_unavailable") && response.cache().equals("no-store"),
                "expected pending authority response, got " + response.status());
    }

    static void equivalent404(MockHttpServletResponse absent, MockHttpServletResponse concealed) throws Exception {
        require(problem(absent, 404, "not_found", "Not found").equals(problem(concealed, 404, "not_found", "Not found"))
                && headers(absent).equals(headers(concealed)), "concealed request differed from ordinary 404");
    }

    static Map<String, List<String>> headers(MockHttpServletResponse response) {
        var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        response.getHeaderNames().forEach(name -> headers.put(name, response.getHeaders(name)));
        return headers;
    }

    private Pause arm(boolean readFirst) {
        var cut = new Pause(readFirst, new CountDownLatch(1), new CountDownLatch(1));
        require(pause.compareAndSet(null, cut), "another SQL cut remains armed");
        return cut;
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (self, method, args) -> {
                    String query = method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            ? sql.stripLeading() : "";
                    if (query.contains("FROM requests.policy_guard") && query.contains(" FOR ")) {
                        guardLocks.incrementAndGet();
                    }
                    if (query.startsWith("SELECT body FROM requests.request_body")) {
                        bodyReads.incrementAndGet();
                    }
                    var cut = pause.get();
                    if (cut != null && (cut.readFirst() ? method.getName().equals("commit") : query.startsWith(WITNESS))
                            && pause.compareAndSet(cut, null)) {
                        System.out.println("CUT " + (cut.readFirst() ? "detail staged at guarded transaction commit"
                                : "visibility writer at guarded witness insert"));
                        cut.entered().countDown();
                        await(cut.resume());
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }

    private static void nativeWait(Connection observer, String mode) throws Exception {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(250);
        try (var statement = observer.prepareStatement("""
                SELECT pid, pg_blocking_pids(pid)::text FROM pg_stat_activity
                WHERE usename = 'b04_workflow' AND wait_event_type = 'Lock'
                    AND query LIKE ? AND cardinality(pg_blocking_pids(pid)) > 0
                """)) {
            statement.setString(1, "%FROM requests.policy_guard%FOR " + mode + "%");
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        System.out.printf("PASS PostgreSQL blocked FOR %s pid=%d blockers=%s%n",
                                mode, rows.getInt(1), rows.getString(2));
                        return;
                    }
                }
                Thread.sleep(2);
            } while (System.nanoTime() < deadline);
        }
        throw new AssertionError("PostgreSQL did not report a FOR " + mode + " guard wait");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(4, SECONDS), "timed out at the guarded cut");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Pause(boolean readFirst, CountDownLatch entered, CountDownLatch resume) { }

    private record Created(B08MvcSession.Call call, B08MvcSession.Reply reply, RequestRef ref) { }
}
