import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.util.concurrent.TimeUnit.SECONDS;

/** Private PostgreSQL and MVC evidence for the guarded history route. */
public final class B20Postgresql {
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor APPROVER = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor OPERATOR = B10Postgresql.OPERATOR;
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final RequestRef ABSENT = new RequestRef("ordinary-history-absent");
    private static final String BODY = "Retained request body 🚦";
    private static final String REASON = "Retained terminal reason 東京";
    private static final JsonMapper JSON = new JsonMapper();

    private static final Step CREATE = new Step("create", null, "CREATED");
    private static final Step VERIFY = new Step("verify", "CREATED", "VERIFIED");
    private static final Step ACCEPT = new Step("accept", "VERIFIED", "ACCEPTED");
    private static final Step DELETE = new Step("delete", "CREATED", "DELETED");
    private static final Step REJECT_VERIFIED = new Step("reject", "VERIFIED", "REJECTED");
    private static final Step REJECT_ACCEPTED = new Step("reject", "ACCEPTED", "REJECTED");
    private static final Step PUBLISH = new Step("publish", "ACCEPTED", "PUBLISHED");

    private final B17Postgresql base;
    private final JdbcTemplate proofOwner;
    private final AtomicInteger historyReads = new AtomicInteger();
    private final AtomicInteger guardedReads = new AtomicInteger();
    private final AtomicReference<Cut> beforeReadCommit = new AtomicReference<>();

    private B20Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        base = new B17Postgresql(workflow, proof, projector, resolver, createProof, logins, sources, this::observe);
        proofOwner = new JdbcTemplate(B08MvcSession.dataSource(proof.migration()));
        var migrations = new JdbcWorkflowMigrations(base.sql.getDataSource());
        migrations.migrateDetailGrants();
        base.enable(OWNER, TYPE, Grant.ROW_READ);
        long grants = base.sql.queryForObject("SELECT count(*) FROM requests.scope_grant_override", Long.class);
        migrations.migrateHistoryGrants();
        migrations.migrateHistoryGrants();
        require(grants == base.sql.queryForObject("SELECT count(*) FROM requests.scope_grant_override", Long.class),
                "V012 altered existing grants");
        System.out.println("PASS V012 upgrades populated V011 and can be reapplied");
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.requestHistory(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                B20Postgresql fixture;
                List<Terminal> terminal;
                try (var sources = new B10FixtureSources()) {
                    fixture = new B20Postgresql(workflow, proof, projector, resolver, createProof, logins, sources);
                    terminal = fixture.exercise();
                }
                proof.restart().run();
                fixture.afterRestart(workflow, proof, projector, resolver, createProof, logins, terminal);
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B20 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private List<Terminal> exercise() throws Exception {
        try (var mvc = new B08MvcSession(base.settings)) {
            validateBeforeStorage(mvc);
            distinctGrant(mvc);
            base.enable(OWNER, TYPE, Grant.FIELD_READ_NAME, Grant.FIELD_READ_BODY, Grant.CONTEXT_EDIT,
                    Grant.EXECUTE_EDIT, Grant.RESULT_READ_EDIT, Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY,
                    Grant.RESULT_READ_VERIFY, Grant.CONTEXT_DELETE, Grant.EXECUTE_DELETE, Grant.RESULT_READ_DELETE,
                    Grant.CONTEXT_REJECT, Grant.EXECUTE_REJECT, Grant.RESULT_READ_REJECT);
            base.enable(APPROVER, TYPE, Grant.ROW_READ, Grant.HISTORY_READ, Grant.FIELD_READ_NAME, Grant.FIELD_READ_BODY,
                    Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT,
                    Grant.CONTEXT_REJECT, Grant.EXECUTE_REJECT, Grant.RESULT_READ_REJECT,
                    Grant.CONTEXT_PUBLISH, Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH);
            rollback(mvc);
            inconsistentFacts(mvc);
            revocationOrder(mvc, true);
            revocationOrder(mvc, false);
            var deleted = deleted(mvc);
            var rejectedFromVerified = rejectedFromVerified(mvc);
            var accepted = base.accepted(mvc, TYPE);
            var rejectedFromAccepted = reject(mvc, accepted, base.publisherBearer,
                    List.of(CREATE, VERIFY, ACCEPT, REJECT_ACCEPTED), "4");
            var published = published(mvc);
            return List.of(deleted, rejectedFromVerified, rejectedFromAccepted, published);
        }
    }

    private void validateBeforeStorage(B08MvcSession mvc) throws Exception {
        int start = guardedReads.get();
        var unauthenticated = mvc.raw(history(ABSENT).queryParam("unexpected", "value"), "");
        problem(unauthenticated, 401, "authentication_required", "Authentication required");
        require(unauthenticated.getHeaders("WWW-Authenticate").equals(List.of("Bearer")), "missing challenge");
        for (var invalid : List.of(history(ABSENT).queryParam("unexpected", "value"), history(ABSENT).content("{}"),
                history(new RequestRef("r".repeat(129))))) {
            problem(mvc.raw(invalid, base.ownerBearer), 400, "invalid_input", "Invalid input");
        }
        require(guardedReads.get() == start, "invalid request reached guarded storage");
        System.out.println("PASS identity precedes validation, and invalid requests reach no guard or facts");
    }

    private void distinctGrant(B08MvcSession mvc) throws Exception {
        var ref = create(mvc);
        var before = counts();
        int reads = historyReads.get();
        var absent = get(mvc, ABSENT, base.ownerBearer);
        B19Postgresql.equivalent404(absent, get(mvc, ref, base.ownerBearer));
        base.enable(OWNER, TYPE, Grant.HISTORY_READ);
        revoke(OWNER, Grant.ROW_READ);
        B19Postgresql.equivalent404(absent, get(mvc, ref, base.ownerBearer));
        require(reads == historyReads.get(), "absent or unauthorized target read protected facts");
        base.enable(OWNER, TYPE, Grant.ROW_READ);
        view(get(mvc, ref, base.ownerBearer), ref, List.of(CREATE));
        require(before.equals(counts()), "grant checks changed request, receipt or fact counts");
        System.out.println("PASS ROW_READ and typed HISTORY_READ are both required; history needs no detail field grant");
    }

    private void rollback(B08MvcSession mvc) throws Exception {
        var ref = create(mvc);
        String original = view(get(mvc, ref, base.ownerBearer), ref, List.of(CREATE));
        var before = counts();
        var input = action(mvc, ref, "delete", base.ownerBearer, REASON);
        var invalid = new B08MvcSession.Call(input.route(), input.bearer(), input.key(), "{}");
        require(mvc.invoke(invalid).status() == 400 && before.equals(counts()), "invalid Delete changed local rows");
        base.sql.execute("REVOKE INSERT ON requests.transition_reason FROM b04_workflow");
        try {
            var failed = mvc.invoke(input);
            require(failed.status() == 500 && failed.json().path("type").asString().endsWith(":internal_error"),
                    "terminal reason fault returned unexpected status " + failed.status());
        } finally {
            base.sql.execute("GRANT INSERT ON requests.transition_reason TO b04_workflow");
        }
        unavailable(get(mvc, ref, base.ownerBearer));
        var pending = proofOwner.queryForObject("""
                SELECT attempt_id FROM request_authority.scope_journal_attempt WHERE state = 'PENDING'
                """, UUID.class);
        base.baseline.abort(pending);
        require(before.equals(counts()) && state(ref).equals("CREATED:1:1")
                        && original.equals(view(get(mvc, ref, base.ownerBearer), ref, List.of(CREATE))),
                "rolled-back Delete changed original facts or response");
        System.out.println("PASS invalid and rolled-back Delete preserve exact prior history and counts " + before);
    }

    private void inconsistentFacts(B08MvcSession mvc) throws Exception {
        var ref = create(mvc);
        var before = counts();
        maintenance(() -> require(base.sql.update("""
                INSERT INTO requests.transition_fact (organization, ref, sequence, revision, action, from_state,
                    to_state, recorded_epoch_second, recorded_nano, actor_ref)
                VALUES (?, ?, 2, 2, 'VERIFY', 'CREATED', 'VERIFIED', 0, 0, 'fixture')
                """, OWNER.organization().value(), ref.value()) == 1, "insert inconsistent fixture tail"));
        try {
            unavailable(get(mvc, ref, base.ownerBearer));
            require(counts().equals(before.advance(0, 0, 1, 0)), "history rewrote inconsistent data");
        } finally {
            maintenance(() -> require(base.sql.update("""
                    DELETE FROM requests.transition_fact WHERE ref = ? AND sequence = 2
                    """, ref.value()) == 1, "remove inconsistent fixture tail"));
        }
        require(before.equals(counts()), "removing the fixture tail did not restore counts");
        view(get(mvc, ref, base.ownerBearer), ref, List.of(CREATE));
        System.out.println("PASS extra well-formed fact with inconsistent snapshot fails closed");
    }

    private void revocationOrder(B08MvcSession mvc, boolean readFirst) throws Exception {
        var ref = create(mvc);
        var before = counts();
        var absent = get(mvc, ABSENT, base.ownerBearer);
        var cut = new Cut(new CountDownLatch(1), new CountDownLatch(1));
        PendingConfirmation pending;
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                if (readFirst) {
                    require(beforeReadCommit.compareAndSet(null, cut), "guarded history cut already armed");
                    var reader = workers.submit(() -> get(mvc, ref, base.ownerBearer));
                    await(cut.entered());
                    var writer = workers.submit(() -> stageRevocation(OWNER, Grant.HISTORY_READ));
                    base.baseline.requireWaiting("FOR UPDATE");
                    cut.resume().countDown();
                    view(reader.get(4, SECONDS), ref, List.of(CREATE));
                    pending = writer.get(4, SECONDS);
                } else {
                    base.baseline.pauseBeforeScopeWitness(cut.entered(), cut.resume());
                    var writer = workers.submit(() -> stageRevocation(OWNER, Grant.HISTORY_READ));
                    await(cut.entered());
                    int facts = historyReads.get();
                    var reader = workers.submit(() -> get(mvc, ref, base.ownerBearer));
                    base.baseline.requireWaiting("FOR SHARE");
                    cut.resume().countDown();
                    pending = writer.get(4, SECONDS);
                    unavailable(reader.get(4, SECONDS));
                    require(facts == historyReads.get(), "waiting read selected protected facts before scope proof");
                }
            } finally {
                cut.resume().countDown();
            }
        }
        unavailable(get(mvc, ref, base.ownerBearer));
        base.baseline.confirm(pending);
        B19Postgresql.equivalent404(absent, get(mvc, ref, base.ownerBearer));
        base.enable(OWNER, TYPE, Grant.HISTORY_READ);
        require(before.equals(counts()), "history and revocation changed workflow counts");
        System.out.println("PASS " + (readFirst ? "history handoff before revocation" : "revocation before history")
                + "; pending proof defers and confirmed revocation conceals");
    }

    private Terminal deleted(B08MvcSession mvc) throws Exception {
        var ref = create(mvc);
        var before = counts();
        var input = action(mvc, ref, "delete", base.ownerBearer, REASON);
        unavailable(mvc.invoke(input));
        unavailable(get(mvc, ref, base.ownerBearer));
        base.baseline.confirm(base.sql.queryForObject("""
                SELECT s.attempt_id, s.sequence, s.after_version FROM requests.scope_authority_witness s
                JOIN requests.request_visibility_fact v ON v.organization = s.organization
                    AND v.applied_version = s.after_version WHERE v.ref = ?
                """, (row, i) -> new PendingConfirmation(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3)),
                ref.value()));
        B19Postgresql.equivalent404(get(mvc, ABSENT, base.ownerBearer), get(mvc, ref, base.ownerBearer));
        base.enable(OWNER, TYPE, Grant.DELETED_METADATA);
        var terminal = terminal(mvc, ref, input, List.of(CREATE, DELETE), "2", before.advance(0, 1, 1, 1));
        maintenance(() -> {
            require(base.sql.update("""
                    UPDATE requests.request_body SET retention_status = 'PURGED', body = NULL WHERE ref = ?
                    """, ref.value()) == 1, "fixture body purge");
            require(base.sql.update("""
                    UPDATE requests.transition_reason SET retention_status = 'PURGED', reason = NULL WHERE ref = ?
                    """, ref.value()) == 1, "fixture reason purge");
        });
        verifyTerminal(mvc, terminal);
        System.out.println("PASS Delete needs deleted metadata and retained history survives body and reason purge");
        return terminal;
    }

    private Terminal rejectedFromVerified(B08MvcSession mvc) throws Exception {
        var ref = create(mvc);
        var initial = view(get(mvc, ref, base.ownerBearer), ref, List.of(CREATE));
        var beforeEdit = counts();
        String key = B17Postgresql.issue(mvc, base.ownerBearer);
        String observation = B17Postgresql.observation(mvc, ref, "edit", base.ownerBearer);
        var edit = edit(mvc, ref, key, observation);
        result(edit, "edit", "CREATED", "2");
        require(edit.equals(edit(mvc, ref, key, observation))
                        && initial.equals(view(get(mvc, ref, base.ownerBearer), ref, List.of(CREATE)))
                        && counts().equals(beforeEdit.advance(0, 1, 0, 0)) && state(ref).equals("CREATED:2:1"),
                "Edit or its exact replay appended history");
        System.out.println("PASS Edit increments revision and receipt only; history bytes survive Edit and replay");
        var verified = mvc.invoke(action(mvc, ref, "verify", base.ownerBearer, null));
        result(verified, "verify", "VERIFIED", "3");
        return reject(mvc, ref, base.ownerBearer, List.of(CREATE, VERIFY, REJECT_VERIFIED), "4");
    }

    private Terminal reject(B08MvcSession mvc, RequestRef ref, String bearer, List<Step> steps, String revision)
            throws Exception {
        var before = counts();
        var input = action(mvc, ref, "reject", bearer, REASON);
        var first = mvc.invoke(input);
        var terminal = terminal(mvc, ref, input, steps, revision, before.advance(0, 1, 1, 1));
        require(first.equals(terminal.receipt()), "Reject replay differed from original");
        return terminal;
    }

    private Terminal published(B08MvcSession mvc) throws Exception {
        var ref = base.accepted(mvc, TYPE);
        var before = counts();
        var input = action(mvc, ref, "publish", base.publisherBearer, null);
        unavailable(mvc.invoke(input));
        var binding = base.binding(TYPE, ref);
        require(binding.state() == State.PENDING, "expected persisted pending publication");
        unavailable(get(mvc, ref, base.publisherBearer));
        base.baseline.confirm(base.scopeWitness(ref));
        int reads = historyReads.get();
        unavailable(get(mvc, ref, base.publisherBearer));
        B19Postgresql.equivalent404(get(mvc, ABSENT, base.ownerBearer), get(mvc, ref, base.ownerBearer));
        require(reads == historyReads.get(), "unconfirmed or concealed publication looked up history facts");
        base.confirm(binding);
        view(get(mvc, ref, base.publisherBearer), ref, List.of(CREATE, VERIFY, ACCEPT, PUBLISH));
        base.enable(APPROVER, TYPE, Grant.FIELD_READ_PUBLICATION_ID);
        var terminal = terminal(mvc, ref, input, List.of(CREATE, VERIFY, ACCEPT, PUBLISH), "4",
                before.advance(0, 1, 1, 0));
        System.out.println("PASS Publish history defers at both pending proofs and omits publication ID after confirmation");
        return terminal;
    }

    private Terminal terminal(B08MvcSession mvc, RequestRef ref, B08MvcSession.Call input, List<Step> steps,
                              String revision, Counts expected) throws Exception {
        var last = steps.getLast();
        var response = mvc.invoke(input);
        result(response, last.action(), last.to(), revision);
        var body = view(get(mvc, ref, input.bearer()), ref, steps);
        require(state(ref).equals(last.to() + ":" + revision + ":" + steps.size()) && expected.equals(counts()),
                "terminal state or row counts differ for " + last);
        var terminal = new Terminal(ref, input, response, steps, body);
        verifyTerminal(mvc, terminal);
        require(expected.equals(counts()), "terminal replay changed counts");
        System.out.println("PASS " + last.action() + " from " + last.from() + " exact replay and counts " + expected);
        return terminal;
    }

    private void verifyTerminal(B08MvcSession mvc, Terminal terminal) throws Exception {
        require(terminal.receipt().equals(mvc.invoke(terminal.input())), "terminal receipt changed on exact retry");
        require(terminal.history().equals(view(get(mvc, terminal.ref(), terminal.input().bearer()),
                terminal.ref(), terminal.steps())), "terminal history bytes changed");
    }

    private void afterRestart(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                              QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                              QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                              List<Terminal> terminal) throws Exception {
        var settings = reopen(workflow, proof, projector, resolver, createProof, logins);
        var before = counts();
        try (var mvc = new B08MvcSession(settings)) {
            for (var test : terminal) {
                verifyTerminal(mvc, test);
            }
        }
        require(before.equals(counts()), "restart or exact replays changed authoritative counts");
        System.out.println("PASS proof PostgreSQL and MVC restarted; four exact histories and receipts " + before);
        workflow.restart().run();
        try (var mvc = new B08MvcSession(settings)) {
            var existing = unavailable(get(mvc, terminal.getFirst().ref(), base.ownerBearer));
            var missing = unavailable(get(mvc, ABSENT, base.ownerBearer));
            require(existing.equals(missing) && before.equals(counts()), "uncertified reader identified an existing ref");
        }
        System.out.println("PASS uncertified workflow restart defers present and absent refs identically; durable counts "
                + before);
    }

    private SyntheticEffectiveAccessConfiguration.Settings reopen(QualifyPostgresql.Connections workflow,
            QualifyPostgresql.Connections proof, QualifyPostgresql.SqlConnection projector,
            QualifyPostgresql.SqlConnection resolver, QualifyPostgresql.SqlConnection createProof,
            QualifyPostgresql.ReleaseLogins logins) {
        var prior = base.settings.create();
        var replay = new SyntheticCreateConfiguration.GuardedReplay(prior.guardedReplay().orElseThrow().originalReceipts(),
                JdbcReleaseEvidence.preparation(B08MvcSession.dataSource(logins.preparation())),
                JdbcReleaseEvidence.handoffs(B08MvcSession.dataSource(logins.handoff())));
        var create = new SyntheticCreateConfiguration.Settings(B08MvcSession.dataSource(workflow.runtime()),
                B08MvcSession.dataSource(createProof), prior.policy(), prior.writer(), prior.actors(), prior.clock(),
                prior.secrets(), Optional.of(replay));
        return new SyntheticEffectiveAccessConfiguration.Settings(create, B08MvcSession.dataSource(proof.runtime()),
                B08MvcSession.dataSource(workflow.observer()), B08MvcSession.dataSource(projector),
                B08MvcSession.dataSource(resolver), base.settings.operators(), base.settings.observationKeys(), true);
    }

    private PendingConfirmation stageRevocation(AuthenticatedActor actor, Grant grant) {
        return base.profile.updates().stageRevocation(OPERATOR, new GrantKey(actor, grant, Optional.of(TYPE)));
    }

    private void revoke(AuthenticatedActor actor, Grant grant) {
        base.baseline.confirm(stageRevocation(actor, grant));
    }

    private RequestRef create(B08MvcSession mvc) throws Exception {
        var before = counts();
        var response = B17Postgresql.call(mvc, "/requests", base.ownerBearer, B17Postgresql.issue(mvc, base.ownerBearer),
                Map.of("type", TYPE.name(), "name", "Protected history ticket", "body", BODY));
        require(response.status() == 201 && counts().equals(before.advance(1, 1, 1, 0)),
                "Create did not write exactly one row, receipt and fact");
        return new RequestRef(response.json().path("ref").asString());
    }

    private static B08MvcSession.Call action(B08MvcSession mvc, RequestRef ref, String action, String bearer, String reason)
            throws Exception {
        String key = B17Postgresql.issue(mvc, bearer);
        String observation = B17Postgresql.observation(mvc, ref, action, bearer);
        var payload = reason == null ? Map.of("expectedRevision", observation)
                : Map.of("expectedRevision", observation, "reason", reason);
        return new B08MvcSession.Call("/requests/" + ref.value() + "/actions/" + action, bearer, key,
                JSON.writeValueAsString(payload));
    }

    private B08MvcSession.Reply edit(B08MvcSession mvc, RequestRef ref, String key, String observation) throws Exception {
        return mvc.invoke(MockMvcRequestBuilders.patch("/requests/{ref}/body", ref.value())
                .header("Idempotency-Key", key).contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("expectedRevision", observation, "body", "Edited body 東京"))),
                base.ownerBearer);
    }

    private static void result(B08MvcSession.Reply receipt, String action, String to, String revision) {
        require(receipt.status() == 200 && receipt.json().path("action").asString().equals(action)
                && receipt.json().path("state").asString().equals(to)
                && receipt.json().path("resultRevision").asString().equals(revision),
                "unexpected " + action + " result " + receipt.status());
    }

    private static MockHttpServletRequestBuilder history(RequestRef ref) {
        return MockMvcRequestBuilders.get("/requests/{ref}/history", ref.value());
    }

    private static MockHttpServletResponse get(B08MvcSession mvc, RequestRef ref, String bearer) throws Exception {
        return mvc.raw(history(ref), bearer);
    }

    private static String view(MockHttpServletResponse response, RequestRef ref, List<Step> steps) throws Exception {
        var expected = JSON.createObjectNode().put("ref", ref.value());
        var expectedChanges = expected.putArray("changes");
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            var change = expectedChanges.addObject().put("sequence", index + 1).put("action", step.action());
            if (step.from() == null) {
                change.putNull("from");
            } else {
                change.put("from", step.from());
            }
            change.put("to", step.to());
        }
        var bytes = response.getContentAsByteArray();
        require(response.getStatus() == 200 && Arrays.equals(JSON.writeValueAsBytes(expected), bytes),
                "history response differs from exact ordered mask at status " + response.getStatus());
        var headers = B19Postgresql.headers(response);
        require(headers.equals(Map.of("Cache-Control", List.of("no-store"), "Content-Type",
                List.of("application/json;charset=UTF-8"), "Content-Length", List.of(Integer.toString(bytes.length)))),
                "unexpected history protocol headers");
        var body = new String(bytes, StandardCharsets.UTF_8);
        System.out.printf("WIRE %d %s %s%n", response.getStatus(), headers, body);
        return body;
    }

    private static ObjectNode unavailable(MockHttpServletResponse response) throws Exception {
        return problem(response, 503, "authority_unavailable", "Authority unavailable");
    }

    private static void unavailable(B08MvcSession.Reply response) {
        require(response.status() == 503 && response.json().path("type").asString().endsWith(":authority_unavailable")
                && response.cache().equals("no-store"), "expected authority deferral, got " + response.status());
    }

    private static ObjectNode problem(MockHttpServletResponse response, int status, String code, String title)
            throws Exception {
        return B19Postgresql.problem(response, status, code, title);
    }

    private String state(RequestRef ref) {
        return base.sql.queryForObject("""
                SELECT state || ':' || revision || ':' || last_sequence FROM requests.request WHERE ref = ?
                """, String.class, ref.value());
    }

    private Counts counts() {
        return base.sql.queryForObject("""
                SELECT (SELECT count(*) FROM requests.request), (SELECT count(*) FROM requests.operation_receipt),
                       (SELECT count(*) FROM requests.transition_fact), (SELECT count(*) FROM requests.transition_reason)
                """, (row, index) -> new Counts(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)));
    }

    private void maintenance(Runnable change) {
        var source = base.sql.getDataSource();
        var records = new JdbcWorkflowRecords(source);
        new JdbcTransactionRunner(source).run(() -> {
            records.lockExclusive(OWNER.organization()).orElseThrow();
            change.run();
            return null;
        });
    }

    private Connection observe(Connection connection) {
        var readHistory = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (self, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        if (sql.contains("FROM requests.policy_guard") && sql.contains(" FOR ")) {
                            guardedReads.incrementAndGet();
                        }
                        if (sql.stripLeading().startsWith("SELECT") && sql.contains("FROM requests.transition_fact")) {
                            require(!sql.contains("actor_ref") && !sql.contains("recorded_") && !sql.contains("SELECT *"),
                                    "history selected protected occurrence columns");
                            historyReads.incrementAndGet();
                            readHistory.set(true);
                        }
                    }
                    if (method.getName().equals("commit") && readHistory.get()) {
                        var cut = beforeReadCommit.getAndSet(null);
                        if (cut != null) {
                            System.out.println("CUT history enqueued at guarded commit");
                            cut.entered().countDown();
                            await(cut.resume());
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(4, SECONDS), "guarded history cut timed out");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Step(String action, String from, String to) { }
    private record Cut(CountDownLatch entered, CountDownLatch resume) { }
    private record Terminal(RequestRef ref, B08MvcSession.Call input, B08MvcSession.Reply receipt,
                            List<Step> steps, String history) { }
    private record Counts(long requests, long receipts, long facts, long reasons) {
        Counts advance(long requestDelta, long receiptDelta, long factDelta, long reasonDelta) {
            return new Counts(requests + requestDelta, receipts + receiptDelta, facts + factDelta, reasons + reasonDelta);
        }
    }
}
