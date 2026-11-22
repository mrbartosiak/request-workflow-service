import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.B21CursorProbe;
import com.company.requests.application.BrowseCursorCodec;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.RequestBrowseQuery.Selection;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.util.concurrent.TimeUnit.SECONDS;

/** Private PostgreSQL and registered MVC oracle for authorized keyset browse. */
public final class B21Postgresql {
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor APPROVER = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor OPERATOR = B10Postgresql.OPERATOR;
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final RequestType HIDDEN = RequestType.BUG_REPORT;
    private static final Instant NOW = Instant.parse("2026-11-22T21:00:00.123456789Z");
    private static final long TWO_TO_53 = 9_007_199_254_740_992L;
    private static final String CREATED = "CREATED";
    private static final JsonMapper JSON = new JsonMapper();

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final AtomicInteger candidateReads = new AtomicInteger();
    private final AtomicReference<Cut> beforeBrowseCommit = new AtomicReference<>();
    private final AtomicReference<Instant> expireOnRecheck = new AtomicReference<>();
    private final List<ObjectNode> tombstones = new ArrayList<>();
    private final B17Postgresql base;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;

    private B21Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        base = new B17Postgresql(workflow, proof, projector, resolver, createProof, logins, sources, this::observe);
        var migrations = new JdbcWorkflowMigrations(base.sql.getDataSource());
        migrations.migrateHistoryGrants();
        base.enable(OWNER, TYPE, Grant.ROW_READ);
        long before = base.sql.queryForObject("SELECT count(*) FROM requests.scope_grant_override", Long.class);
        migrations.migrateBrowse();
        migrations.migrateBrowse();
        require(before == base.sql.queryForObject("SELECT count(*) FROM requests.scope_grant_override", Long.class),
                "V013 altered existing grants");
        System.out.println("PASS V013 upgrades populated V012 and reapplies with an indexed scope seek");
        var original = base.settings;
        var create = original.create();
        var timed = new SyntheticCreateConfiguration.Settings(create.command(), create.proof(), create.policy(),
                create.writer(), create.actors(), clock::get, create.secrets(), create.guardedReplay());
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var cursorKeys = new BrowseCursorCodec.Keys(11, Map.of(11, key));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(timed, original.preparation(),
                original.originalObserver(), original.projection(), original.resolver(), original.operators(),
                original.observationKeys(), original.publicationEnabled()).withBrowseKeys(cursorKeys);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.requestBrowse(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B21Postgresql(workflow, proof, projector, resolver, createProof, logins, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B21 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        try (var mvc = new B08MvcSession(settings)) {
            validation(mvc);
            base.enable(OWNER, TYPE, Grant.FIELD_READ_TYPE, Grant.FIELD_READ_NAME, Grant.FIELD_READ_STATE);
            ordered(mvc);
            filters(mvc);
            hiddenBatches(mvc);
            base.enable(OWNER, TYPE, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY,
                    Grant.CONTEXT_DELETE, Grant.EXECUTE_DELETE, Grant.RESULT_READ_DELETE);
            live(mvc);
            deleteOrder(mvc, true);
            deleteOrder(mvc, false);
            publication(mvc);
        }
    }

    private void validation(B08MvcSession mvc) throws Exception {
        int reads = candidateReads.get();
        var unauthenticated = mvc.raw(request(null, null, "0", null), "");
        B19Postgresql.problem(unauthenticated, 401, "authentication_required", "Authentication required");
        require(unauthenticated.getHeaders("WWW-Authenticate").equals(List.of("Bearer")), "missing challenge");
        for (var invalid : List.of(request(null, null, "0", null), request(null, null, "51", null),
                request(null, "created", null, null), request("　", null, null, null),
                request("a".repeat(257), null, null, null), request(null, null, null, "x".repeat(2049)),
                request(null, null, null, null).queryParam("unexpected", "value"),
                request(null, null, null, null).queryParam("size", "1", "2").content("{}"))) {
            B19Postgresql.problem(mvc.raw(invalid, base.ownerBearer), 400, "invalid_input", "Invalid input");
        }
        require(reads == candidateReads.get(), "invalid or unauthenticated request read candidates");
        System.out.println("PASS identity before validation, text and enum checks, sizes outside 1–50, no invalid seek");
    }

    private void ordered(B08MvcSession mvc) throws Exception {
        var expected = new ArrayList<ObjectNode>();
        maintenance(records -> {
            for (int offset = 12; offset >= 0; offset--) {
                var ref = new RequestRef("numeric-" + offset);
                String name = "numeric " + offset;
                var order = new CreationOrder(TWO_TO_53 + (offset == 12 ? 1 : 0), TWO_TO_53 + offset);
                seed(records, ref, name, TYPE, order, OWNER);
                expected.add(item(ref, name, CREATED, true));
            }
        });
        var first = page(get(mvc, null, null, null, null), expected.subList(0, 10), true);
        page(get(mvc, null, null, "10", first), expected.subList(10, 13), false);
        page(get(mvc, null, null, "50", null), expected, false);
        String cursor = null;
        for (int index = 0; index < expected.size(); index++) {
            cursor = page(get(mvc, null, null, "1", cursor), expected.subList(index, index + 1),
                    index + 1 < expected.size());
        }
        System.out.println("PASS default 10, effective size equality, size 1 and 50, exact bigint epoch/ordinal traversal");
        var high = new RequestRef("tie-𐀀");
        var low = new RequestRef("tie-\uE000");
        String name = "synthetic equal creation counters";
        maintenance(records -> {
            // Current allocation normally prohibits a tie. Exercise the required fallback on qualified legacy data.
            base.sql.execute("ALTER TABLE requests.request DROP CONSTRAINT request_creation_order_unique");
            var equal = new CreationOrder(TWO_TO_53 - 1, Long.MAX_VALUE);
            seed(records, low, name, TYPE, equal, OWNER);
            seed(records, high, name, TYPE, equal, OWNER);
        });
        try {
            base.enable(OWNER, TYPE, Grant.FIELD_FILTER_NAME);
            var next = page(get(mvc, name, null, "1", null), List.of(item(high, name, CREATED, true)), true);
            page(get(mvc, name, null, "1", next), List.of(item(low, name, CREATED, true)), false);
        } finally {
            maintenance(records -> {
                for (String table : List.of("request_assignment", "request_body", "transition_fact", "request")) {
                    base.sql.update("DELETE FROM requests." + table + " WHERE ref IN (?, ?)", low.value(), high.value());
                }
                base.sql.execute("""
                        ALTER TABLE requests.request ADD CONSTRAINT request_creation_order_unique
                        UNIQUE (created_epoch, created_ordinal)
                        """);
            });
        }
        System.out.println("PASS unsigned UTF-8 reference tie-break and exclusivity against both tied rows");
    }

    private void filters(B08MvcSession mvc) throws Exception {
        String exact = " Café 東京 🚦 ";
        var first = create(mvc, exact);
        var second = create(mvc, " Cafe\u0301 東京 🚦 ");
        create(mvc, " café 東京 🚦 ");
        page(get(mvc, exact, CREATED, null, null), List.of(), false);
        base.enable(OWNER, TYPE, Grant.FIELD_FILTER_STATE);
        page(get(mvc, exact, CREATED, null, null), List.of(item(first, exact, CREATED, true)), false);
        page(get(mvc, " Cafe\u0301 東京 🚦 ", CREATED, null, null),
                List.of(item(second, " Cafe\u0301 東京 🚦 ", CREATED, true)), false);
        page(get(mvc, "Café 東京 🚦", CREATED, null, null), List.of(), false);
        page(get(mvc, exact, "VERIFIED", null, null), List.of(), false);
        revoke(OWNER, Grant.FIELD_READ_NAME);
        page(get(mvc, exact, CREATED, null, null), List.of(), false);
        base.enable(OWNER, TYPE, Grant.FIELD_READ_NAME);
        revoke(OWNER, Grant.FIELD_FILTER_NAME);
        page(get(mvc, exact, null, null, null), List.of(), false);
        base.enable(OWNER, TYPE, Grant.FIELD_FILTER_NAME);
        base.baseline.confirm(base.profile.updates().stageAssignment(OPERATOR, first, APPROVER));
        page(get(mvc, exact, CREATED, null, null), List.of(), false);
        System.out.println("PASS exact case, combining characters, whitespace and AND with independent filter/read/row gates");
    }

    private void hiddenBatches(B08MvcSession mvc) throws Exception {
        String name = "hidden batch quotient";
        var high = new RequestRef("visible-before-hidden");
        var low = new RequestRef("visible-after-hidden");
        maintenance(records -> seed(records, high, name, TYPE, new CreationOrder(4, 200), OWNER));
        var original = get(mvc, name, CREATED, "1", null);
        page(original, List.of(item(high, name, CREATED, true)), false);
        maintenance(records -> {
            for (int i = 1; i <= 70; i++) {
                seed(records, new RequestRef("hidden-" + i), name, HIDDEN, new CreationOrder(4, i), OWNER);
            }
        });
        var hidden = get(mvc, name, CREATED, "1", null);
        page(hidden, List.of(item(high, name, CREATED, true)), false);
        require(Arrays.equals(original.getContentAsByteArray(), hidden.getContentAsByteArray()),
                "hidden-only candidates changed the response");
        maintenance(records -> seed(records, low, name, TYPE, new CreationOrder(3, 1), OWNER));
        int scans = candidateReads.get();
        var next = page(get(mvc, name, CREATED, "1", null), List.of(item(high, name, CREATED, true)), true);
        require(candidateReads.get() - scans >= 4, "selection and recheck did not cross the hidden batch");
        page(get(mvc, name, CREATED, "1", next), List.of(item(low, name, CREATED, true)), false);
        System.out.println("PASS 70 unauthorized rows leave exact bytes unchanged and cannot hide an eligible later batch");
    }

    private void live(B08MvcSession mvc) throws Exception {
        String name = "live page membership";
        var lower = create(mvc, name);
        var moving = create(mvc, name);
        var anchor = create(mvc, name);
        var first = page(get(mvc, name, CREATED, "1", null), List.of(item(anchor, name, CREATED, true)), true);
        var invalid = get(mvc, name, CREATED, "1", tamper(first));
        for (var changed : List.of(get(mvc, name, CREATED, "1", "malformed"),
                get(mvc, name, CREATED, "2", first), get(mvc, name, null, "1", first),
                get(mvc, "Live page membership", CREATED, "1", first),
                mvc.raw(request(name, CREATED, "1", first), base.publisherBearer),
                mvc.raw(MockMvcRequestBuilders.get(URI.create("/requests?cursor=%C0%AF")), base.ownerBearer),
                get(mvc, name, CREATED, "1", "é"))) {
            equivalentProblem(invalid, changed, 400, "invalid_input", "Invalid input");
        }

        var codec = new BrowseCursorCodec(settings.browseKeys().orElseThrow(), settings.create().secrets(),
                settings.observationKeys());
        var selection = new Selection(Optional.of(name), Optional.of(RequestState.CREATED), 1);
        var old = B21CursorProbe.precedingGeneration(codec, OWNER, selection, first);
        int scans = candidateReads.get();
        equivalentProblem(invalid, get(mvc, name, CREATED, "1", old), 400, "invalid_input", "Invalid input");
        require(scans == candidateReads.get(), "old generation queried candidates");

        clock.set(NOW.plusSeconds(900).minusNanos(1));
        var second = page(get(mvc, name, CREATED, "1", first), List.of(item(moving, name, CREATED, true)), true);
        expireOnRecheck.set(NOW.plusSeconds(900));
        equivalentProblem(invalid, get(mvc, name, CREATED, "1", first), 400, "invalid_input", "Invalid input");
        require(expireOnRecheck.get() == null && clock.get().equals(NOW.plusSeconds(900)),
                "cursor expiry was not forced during guarded recheck");
        equivalentProblem(invalid, get(mvc, name, CREATED, "1", first), 400, "invalid_input", "Invalid input");
        equivalentProblem(invalid, get(mvc, name, CREATED, "1", second), 400, "invalid_input", "Invalid input");
        clock.set(NOW);
        System.out.println("PASS uniform invalid cursor response, earlier generation before seek and exact sweep expiry");

        var verify = mvc.invoke(action(mvc, moving, "verify", base.ownerBearer));
        require(verify.status() == 200 && verify.json().path("state").asString().equals("VERIFIED"),
                "live Verify setup failed");
        create(mvc, name);
        revoke(OWNER, Grant.ROW_READ);
        page(get(mvc, name, CREATED, "1", first), List.of(), false);
        base.enable(OWNER, TYPE, Grant.ROW_READ);
        revoke(OWNER, Grant.FIELD_FILTER_STATE);
        page(get(mvc, name, CREATED, "1", first), List.of(), false);
        base.enable(OWNER, TYPE, Grant.FIELD_FILTER_STATE);
        revoke(OWNER, Grant.FIELD_READ_TYPE);
        page(get(mvc, name, CREATED, "1", first), List.of(item(lower, name, CREATED, false)), false);
        base.enable(OWNER, TYPE, Grant.FIELD_READ_TYPE);
        System.out.println("PASS live continuation recomputes grants, filter membership and per-row mask below the anchor");
    }

    private void deleteOrder(B08MvcSession mvc, boolean browseFirst) throws Exception {
        String name = browseFirst ? "browse then Delete" : "Delete then browse";
        var soleLookahead = create(mvc, name);
        var anchor = create(mvc, name);
        var delete = action(mvc, soleLookahead, "delete", base.ownerBearer);
        var cut = new Cut(new CountDownLatch(1), new CountDownLatch(1));
        String oldCursor = null;
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                if (browseFirst) {
                    require(beforeBrowseCommit.compareAndSet(null, cut), "browse cut already armed");
                    var reader = workers.submit(() -> get(mvc, name, null, "1", null));
                    await(cut.entered());
                    var writer = workers.submit(() -> mvc.invoke(delete));
                    base.baseline.requireWaiting("FOR UPDATE");
                    cut.resume().countDown();
                    oldCursor = page(reader.get(4, SECONDS), List.of(item(anchor, name, CREATED, true)), true);
                    unavailable(writer.get(4, SECONDS));
                } else {
                    base.baseline.pauseBeforeScopeWitness(cut.entered(), cut.resume());
                    var writer = workers.submit(() -> mvc.invoke(delete));
                    await(cut.entered());
                    int scans = candidateReads.get();
                    var reader = workers.submit(() -> get(mvc, name, null, "1", null));
                    base.baseline.requireWaiting("FOR SHARE");
                    cut.resume().countDown();
                    unavailable(writer.get(4, SECONDS));
                    B19Postgresql.problem(reader.get(4, SECONDS), 503, "authority_unavailable", "Authority unavailable");
                    require(scans == candidateReads.get(), "pending Delete proof allowed candidate selection");
                }
            } finally {
                cut.resume().countDown();
            }
        }
        base.baseline.confirm(deletionWitness(soleLookahead));
        page(get(mvc, name, null, "1", null), List.of(item(anchor, name, CREATED, true)), false);
        if (oldCursor != null) {
            page(get(mvc, name, null, "1", oldCursor), List.of(), false);
        }
        base.enable(OWNER, TYPE, Grant.DELETED_METADATA);
        page(get(mvc, name, null, "1", null), List.of(item(anchor, name, CREATED, true)), false);
        tombstones.addFirst(item(soleLookahead, null, "DELETED", false));
        page(get(mvc, null, "DELETED", "50", null), tombstones, false);
        revoke(OWNER, Grant.DELETED_METADATA);
        System.out.println("PASS " + (browseFirst ? "page and cursor handed off before waiting Delete"
                : "prior Delete blocks browse and pending proof defers") + "; confirmed tombstone cannot name-match");
    }

    private void publication(B08MvcSession mvc) throws Exception {
        base.enable(APPROVER, TYPE, Grant.ROW_READ, Grant.FIELD_READ_STATE, Grant.FIELD_READ_TYPE,
                Grant.FIELD_READ_NAME, Grant.FIELD_FILTER_NAME, Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT,
                Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH, Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH);
        base.enable(APPROVER, TYPE, Grant.FIELD_READ_BODY);
        var published = base.accepted(mvc, TYPE);
        String name = "Exact synthetic name";
        var anchor = create(mvc, name);
        base.baseline.confirm(base.profile.updates().stageAssignment(OPERATOR, anchor, APPROVER));
        var command = action(mvc, published, "publish", base.publisherBearer);
        var anchorItem = item(anchor, name, CREATED, true);
        unavailable(mvc.invoke(command));
        var binding = base.binding(TYPE, published);
        require(binding.state() == State.PENDING, "Publish did not enter pending proof");
        base.baseline.confirm(base.scopeWitness(published));
        var withoutProof = mvc.raw(request(name, null, "1", null), base.publisherBearer);
        page(withoutProof, List.of(anchorItem), false);
        base.confirm(binding);
        var first = page(mvc.raw(request(name, null, "1", null), base.publisherBearer), List.of(anchorItem), true);
        page(mvc.raw(request(name, null, "1", first), base.publisherBearer),
                List.of(item(published, name, "PUBLISHED", true)), false);
        base.enable(APPROVER, TYPE, Grant.FIELD_READ_PUBLICATION_ID);
        var permitted = item(published, name, "PUBLISHED", true)
                .put("publicationId", Long.toString(binding.allocation().orElseThrow().value()));
        page(mvc.raw(request(name, null, "1", first), base.publisherBearer), List.of(permitted), false);
        page(get(mvc, null, "PUBLISHED", "50", null), List.of(), false);
        System.out.println("PASS unproved publication supplies no lookahead, proved publication ID has its own live grant");
    }

    private PendingConfirmation deletionWitness(RequestRef ref) {
        return base.sql.queryForObject("""
                SELECT s.attempt_id, s.sequence, s.after_version FROM requests.scope_authority_witness s
                JOIN requests.request_visibility_fact v ON v.organization = s.organization
                    AND v.applied_version = s.after_version WHERE v.ref = ?
                """, (row, index) -> new PendingConfirmation(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3)),
                ref.value());
    }

    private RequestRef create(B08MvcSession mvc, String name) throws Exception {
        var response = B17Postgresql.call(mvc, "/requests", base.ownerBearer, B17Postgresql.issue(mvc, base.ownerBearer),
                Map.of("type", TYPE.name(), "name", name, "body", "Protected browse body"));
        require(response.status() == 201, "fixture Create failed with " + response.status());
        return new RequestRef(response.json().path("ref").asString());
    }

    private static B08MvcSession.Call action(B08MvcSession mvc, RequestRef ref, String action, String bearer)
            throws Exception {
        String key = B17Postgresql.issue(mvc, bearer);
        String observation = B17Postgresql.observation(mvc, ref, action, bearer);
        var payload = action.equals("delete") ? Map.of("expectedRevision", observation, "reason", "Protected reason")
                : Map.of("expectedRevision", observation);
        return new B08MvcSession.Call("/requests/" + ref.value() + "/actions/" + action, bearer, key,
                JSON.writeValueAsString(payload));
    }

    private void revoke(AuthenticatedActor actor, Grant grant) {
        base.baseline.confirm(base.profile.updates().stageRevocation(OPERATOR, new GrantKey(actor, grant, Optional.of(TYPE))));
    }

    private void maintenance(java.util.function.Consumer<JdbcWorkflowRecords> work) {
        var source = base.sql.getDataSource();
        var records = new JdbcWorkflowRecords(source);
        new JdbcTransactionRunner(source).run(() -> {
            records.lockExclusive(OWNER.organization()).orElseThrow();
            work.accept(records);
            return null;
        });
    }

    private void seed(JdbcWorkflowRecords records, RequestRef ref, String name, RequestType type,
                      CreationOrder order, AuthenticatedActor assignee) {
        var person = assignee.person().orElseThrow();
        var creation = Lifecycle.create(new Lifecycle.Creation(ref, type, OWNER.organization(), name,
                        new RequestBody("Protected browse fixture payload"), order, OWNER.person().orElseThrow()),
                new TransitionFact.Occurrence(NOW, OWNER.principal()));
        records.insert(creation.request());
        records.insertBody(OWNER.organization(), ref, creation.body());
        records.appendFact(OWNER.organization(), creation.transition());
        base.sql.update("""
                INSERT INTO requests.request_assignment (organization, ref, assignee_person_id, assignment_version)
                VALUES (?, ?, ?, 1)
                """, OWNER.organization().value(), ref.value(), person.value());
    }

    private MockHttpServletResponse get(B08MvcSession mvc, String name, String state, String size, String cursor)
            throws Exception {
        return mvc.raw(request(name, state, size, cursor), base.ownerBearer);
    }

    private static MockHttpServletRequestBuilder request(String name, String state, String size, String cursor) {
        var request = MockMvcRequestBuilders.get("/requests");
        if (name != null) { request.queryParam("name", name); }
        if (state != null) { request.queryParam("state", state); }
        if (size != null) { request.queryParam("size", size); }
        if (cursor != null) { request.queryParam("cursor", cursor); }
        return request;
    }

    private static ObjectNode item(RequestRef ref, String name, String state, boolean typed) {
        var item = JSON.createObjectNode().put("ref", ref.value());
        if (typed) { item.put("type", TYPE.name()); }
        if (name != null) { item.put("name", name); }
        if (state != null) { item.put("state", state); }
        return item;
    }

    private static String page(MockHttpServletResponse response, List<ObjectNode> items, boolean hasNext) throws Exception {
        require(response.getStatus() == 200, "browse expected 200, got " + response.getStatus());
        byte[] bytes = response.getContentAsByteArray();
        var actual = JSON.readTree(bytes);
        require(actual.has("nextCursor") == hasNext, "unexpected continuation for " + items.size() + " item(s)");
        var expected = JSON.createObjectNode();
        var list = expected.putArray("items");
        items.forEach(list::add);
        String token = null;
        if (hasNext) {
            token = actual.path("nextCursor").asString();
            require(token.length() <= 2048 && token.matches("browse1\\.[1-9][0-9]*\\.[A-Za-z0-9_-]+"),
                    "invalid opaque ASCII cursor envelope");
            expected.put("nextCursor", token);
        }
        require(Arrays.equals(bytes, JSON.writeValueAsBytes(expected)), "browse bytes differ from exact authorized mask");
        require(B19Postgresql.headers(response).equals(Map.of("Cache-Control", List.of("no-store"),
                "Content-Type", List.of("application/json;charset=UTF-8"),
                "Content-Length", List.of(Integer.toString(bytes.length)))), "unexpected browse headers");
        expected.remove("nextCursor");
        System.out.printf("WIRE 200 items=%d next=%s protectedBodySha256=%s%n", items.size(), hasNext,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(expected))));
        return token;
    }

    private static void equivalentProblem(MockHttpServletResponse expected, MockHttpServletResponse actual,
                                          int status, String code, String title) throws Exception {
        var one = B19Postgresql.problem(expected, status, code, title);
        var two = B19Postgresql.problem(actual, status, code, title);
        one.remove("correlationId");
        two.remove("correlationId");
        require(one.equals(two), "cursor failure returned distinct content");
    }

    private static void unavailable(B08MvcSession.Reply reply) {
        require(reply.status() == 503 && reply.cache().equals("no-store")
                && reply.json().path("type").asString().endsWith(":authority_unavailable"), "expected safe pending 503");
    }

    private static String tamper(String cursor) {
        int index = 25;
        return cursor.substring(0, index) + (cursor.charAt(index) == 'A' ? 'B' : 'A') + cursor.substring(index + 1);
    }

    private Connection observe(Connection connection) {
        var reads = new AtomicInteger();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (self, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.contains("SELECT ref, created_epoch, created_ordinal FROM requests.request")) {
                        candidateReads.incrementAndGet();
                        if (reads.incrementAndGet() == 2) {
                            Optional.ofNullable(expireOnRecheck.getAndSet(null)).ifPresent(clock::set);
                        }
                    }
                    if (method.getName().equals("commit") && reads.get() > 0) {
                        var cut = beforeBrowseCommit.getAndSet(null);
                        if (cut != null) {
                            require(reads.get() >= 2, "page and authorized lookahead were not rechecked on the same session");
                            System.out.println("CUT guarded page and lookahead rechecked and offered before READ COMMIT");
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
        require(latch.await(4, SECONDS), "browse cut timed out");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Cut(CountDownLatch entered, CountDownLatch resume) { }
}
