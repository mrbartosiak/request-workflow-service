import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.ActionContextService;
import com.company.requests.application.ActionContextService.Rejection;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.TransitionFact;
import com.company.requests.domain.TransitionReason;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Actual configured MVC route and separately certified synthetic PostgreSQL scopes. */
public final class B13Postgresql {
    private static final Instant NOW = Instant.parse("2026-11-22T21:00:00.123456789Z");
    private static final String NAME = " Case 名 ";
    private static final String BODY = "  e\u0301 line\n\té 東京  ";
    private static final JsonMapper JSON = new JsonMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final AtomicReference<TimeCut> timeCut = new AtomicReference<>();
    private final AtomicInteger targetReads = new AtomicInteger();
    private final AtomicInteger bodyReads = new AtomicInteger();
    private final String ownerBearer = bearer();
    private final String aliasBearer = bearer();
    private final String assigneeBearer = bearer();
    private final B10Postgresql baseline;
    private final JdbcTransactionRunner commandTx;
    private final JdbcTransactionRunner fixtureTx;
    private final JdbcWorkflowRecords fixtureRecords;
    private final JdbcTemplate owner;
    private final JdbcTemplate proofOwner;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;
    private final SyntheticEffectiveAccessConfiguration.Components profile;

    private B13Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                         QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                         QualifyPostgresql.SqlConnection createProof, B10FixtureSources sources) throws SQLException {
        baseline = new B10Postgresql(workflow, proof, projector, resolver, createProof, sources, this::observeReads);
        var ownerSource = B08MvcSession.dataSource(workflow.migration());
        var migrations = new JdbcWorkflowMigrations(ownerSource);
        migrations.migrateActionContext();
        migrations.migrateActionContext();
        owner = new JdbcTemplate(ownerSource);
        proofOwner = new JdbcTemplate(B08MvcSession.dataSource(proof.migration()));
        fixtureRecords = new JdbcWorkflowRecords(ownerSource);
        fixtureTx = new JdbcTransactionRunner(ownerSource);

        var base = baseline.settings();
        commandTx = JdbcTransactionRunner.forCreate(base.create().command());
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", ownerBearer, B10Postgresql.OWNER),
                new SyntheticActorResolver.Binding("alias", aliasBearer, B10Postgresql.OWNER),
                new SyntheticActorResolver.Binding("assignee", assigneeBearer, B10Postgresql.ASSIGNEE)));
        var create = new SyntheticCreateConfiguration.Settings(base.create().command(), base.create().proof(),
                base.create().policy(), base.create().writer(), actors, this::time,
                new OperationSecrets(1, Map.of(1, secret()), 1, Map.of(1, secret())));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(create, base.preparation(), base.originalObserver(),
                base.projection(), base.resolver(), base.operators(),
                Optional.of(new ObservationCodec.Keys(7, Map.of(7, secret()))));
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.actionContext(args, (workflow, proof, projector, resolver, createProof) -> {
                try (var sources = new B10FixtureSources()) {
                    new B13Postgresql(workflow, proof, projector, resolver, createProof, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B13 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        try (var mvc = new B08MvcSession(settings)) {
            var created = create(mvc, BODY);
            var second = create(mvc, BODY);
            var verified = create(mvc, BODY);
            var accepted = create(mvc, BODY);
            var deleted = create(mvc, BODY);
            var hidden = create(mvc, BODY);
            var max = create(mvc, "\u0001".repeat(16_384));
            transition(verified, new Lifecycle.Verify());
            transition(accepted, new Lifecycle.Verify());
            transition(accepted, new Lifecycle.Accept());
            transition(deleted, new Lifecycle.Delete(new TransitionReason("synthetic fixture deletion")));
            var businessCounts = counts();

            int reads = targetReads.get();
            problem(get(mvc, created, "verify", ""), 401, "authentication_required");
            problem(get(mvc, created, "VERIFY", ownerBearer), 400, "invalid_input");
            problem(mvc.invoke(MockMvcRequestBuilders.get(path(created)).queryParam("action", "verify", "edit"),
                    ownerBearer), 400, "invalid_input");
            problem(mvc.invoke(MockMvcRequestBuilders.get(path(created)).queryParam("action", "verify").content("{}"),
                    ownerBearer), 400, "invalid_input");
            require(reads == targetReads.get(), "authentication or validation reached target storage");
            problem(get(mvc, created, "edit", ownerBearer), 404, "not_found");
            System.out.println("PASS actual route authenticates and validates before storage and starts with no implied row permission");

            var ownerActor = B10Postgresql.OWNER;
            enable(ownerActor, Grant.ROW_READ, Grant.EXECUTE_EDIT);
            reads = bodyReads.get();
            problem(get(mvc, created, "edit", ownerBearer), 403, "action_denied");
            enable(ownerActor, Grant.CONTEXT_EDIT);
            problem(get(mvc, created, "edit", ownerBearer), 403, "action_denied");
            require(reads == bodyReads.get(), "denied context reached the material body");
            enable(ownerActor, Grant.FIELD_READ_BODY);
            var first = success(get(mvc, created, "edit", ownerBearer), created, "edit", "CREATED", "body", BODY);
            var alias = success(get(mvc, created, "edit", aliasBearer), created, "edit", "CREATED", "body", BODY);
            require(!first.equals(alias), "repeat observations require fresh AEAD nonces");
            require(verify(created, Action.EDIT, alias) instanceof ActionContextService.Verified,
                    "trusted directory alias changed the stable observation binding");
            success(get(mvc, max, "edit", ownerBearer), max, "edit", "CREATED", "body", "\u0001".repeat(16_384));
            System.out.println("PASS independent row, context and material grants with exact Unicode and worst-case escaped body");

            enable(ownerActor, Grant.CONTEXT_VERIFY, Grant.CONTEXT_PUBLISH, Grant.CONTEXT_DELETE, Grant.EXECUTE_DELETE);
            problem(get(mvc, created, "verify", ownerBearer), 403, "action_denied");
            problem(get(mvc, created, "delete", ownerBearer), 403, "action_denied");
            problem(get(mvc, accepted, "delete", ownerBearer), 403, "action_denied");
            enable(ownerActor, Grant.FIELD_READ_NAME, Grant.EXECUTE_VERIFY);
            success(get(mvc, created, "verify", ownerBearer), created, "verify", "CREATED", "body", BODY);
            success(get(mvc, created, "delete", ownerBearer), created, "delete", "CREATED", "name", NAME);
            problem(get(mvc, accepted, "publish", ownerBearer), 403, "action_denied");
            problem(get(mvc, accepted, "delete", ownerBearer), 409, "illegal_state");
            enable(ownerActor, Grant.EXECUTE_PUBLISH, Grant.CONTEXT_REJECT, Grant.EXECUTE_REJECT,
                    Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT);
            success(get(mvc, accepted, "publish", ownerBearer), accepted, "publish", "ACCEPTED", "name", NAME);
            success(get(mvc, verified, "reject", ownerBearer), verified, "reject", "VERIFIED", "name", NAME);
            problem(get(mvc, verified, "accept", aliasBearer), 403, "action_denied");
            enable(B10Postgresql.ASSIGNEE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_ACCEPT,
                    Grant.EXECUTE_ACCEPT, Grant.CONTEXT_EDIT, Grant.EXECUTE_EDIT);
            baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, verified, B10Postgresql.ASSIGNEE));
            success(get(mvc, verified, "accept", assigneeBearer), verified, "accept", "VERIFIED", "body", BODY);
            System.out.println("PASS all six action shapes, independent Verify and Publish, and stable-person Accept");

            baseline.confirm(profile.updates().stageVisibility(B10Postgresql.OPERATOR, hidden, false, Set.of(Field.STATE)));
            reads = bodyReads.get();
            for (var ref : List.of(new RequestRef("synthetic-absent"), new RequestRef("synthetic-foreign-request"),
                    deleted, hidden)) {
                problem(get(mvc, ref, "edit", ownerBearer), 404, "not_found");
                require(verify(ref, Action.EDIT, "display-guess") == Rejection.CONCEALED,
                        "unobservable target leaked verifier status");
            }
            require(reads == bodyReads.get(), "concealed, foreign or deleted targets reached material body");
            problem(mvc.invoke(MockMvcRequestBuilders.get("/requests/synthetic-detail-probe"), ownerBearer),
                    404, "not_found");
            System.out.println("PASS absent, foreign, hidden and deleted rows normalize before material or observation evaluation");

            String token = success(get(mvc, created, "edit", ownerBearer), created, "edit", "CREATED", "body", BODY);
            require(verify(created, Action.EDIT, token) instanceof ActionContextService.Verified, "exact observation");
            require(verify(second, Action.EDIT, token) == Rejection.STALE
                            && verify(created, Action.VERIFY, token) == Rejection.STALE
                            && verify(created, Action.EDIT, alter(token)) == Rejection.STALE
                            && verify(created, Action.EDIT, token.replace("obs1.7.", "obs1.400.")) == Rejection.STALE
                            && verify(created, Action.EDIT, "1") == Rejection.MALFORMED
                            && verify(created, Action.EDIT, "invalid-token") == Rejection.MALFORMED,
                    "cross-ref, cross-action, tampered, unknown-key and invalid observation cases");
            clock.set(NOW.plus(Duration.ofMinutes(15)).minusNanos(1));
            require(verify(created, Action.EDIT, token) instanceof ActionContextService.Verified, "expired before cutoff");
            clock.set(NOW.plus(Duration.ofMinutes(15)));
            require(verify(created, Action.EDIT, token) == Rejection.STALE, "not expired at cutoff");
            clock.set(NOW);
            updateBody(created, BODY + " ");
            require(verify(created, Action.EDIT, token) == Rejection.STALE, "changed fact with same version passed");
            updateBody(created, BODY);
            require(verify(created, Action.EDIT, token) instanceof ActionContextService.Verified, "exact fact restoration");
            baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, created, B10Postgresql.ASSIGNEE));
            require(verify(created, Action.EDIT, "bad") == Rejection.DENIED, "denied target leaked token syntax");
            require(commandTx.run(() -> profile.actionContext().orElseThrow().verifyNewIntent(
                    B10Postgresql.ASSIGNEE, created, Action.EDIT, token)) == Rejection.STALE,
                    "prior actor or assignment token passed");
            baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, created, ownerActor));
            require(verify(created, Action.EDIT, token) == Rejection.STALE, "same assignment value reused old version");
            var beforeRevision = success(get(mvc, second, "edit", ownerBearer), second, "edit", "CREATED", "body", BODY);
            advanceRevision(second);
            require(verify(second, Action.EDIT, beforeRevision) == Rejection.STALE, "older request revision passed");
            System.out.println("PASS verifier exact match, closed expiry, changed fact/version, and safe stale/denial precedence");

            uncertifiedWriter(created);
            timeCut.set(new TimeCut(3, () -> clock.set(NOW.plus(Duration.ofMinutes(15)))));
            problem(get(mvc, created, "edit", ownerBearer), 503, "authority_unavailable");
            clock.set(NOW);
            require(timeCut.get() == null, "freshness check never reached handoff");
            guardRaces(mvc, created, accepted);
            require(businessCounts.equals(counts()), "context or verifier changed protected business record counts");
            System.out.println("PASS no business effect, receipt or reservation from context and verification");
        }
    }

    private void uncertifiedWriter(RequestRef ref) throws Exception {
        var base = settings.create();
        var next = new SyntheticCreateConfiguration.Settings(base.command(), base.proof(), base.policy(),
                new WriterEpoch(base.writer().writerId(), base.writer().generation() + 1),
                base.actors(), base.clock(), base.secrets());
        var wrong = new SyntheticEffectiveAccessConfiguration.Settings(next, settings.preparation(),
                settings.originalObserver(), settings.projection(), settings.resolver(), settings.operators(),
                settings.observationKeys());
        int reads = targetReads.get();
        try (var mvc = new B08MvcSession(wrong)) {
            problem(get(mvc, ref, "edit", ownerBearer), 503, "authority_unavailable");
        }
        var verifier = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(wrong).actionContext().orElseThrow();
        try {
            commandTx.run(() -> verifier.verifyNewIntent(B10Postgresql.OWNER, ref, Action.EDIT, "malformed"));
            throw new AssertionError("uncertified writer accepted observation verification");
        } catch (AuthorityUnavailableException expected) {
            require(reads == targetReads.get(), "uncertified writer reached target storage");
        }
        System.out.println("PASS uncertified writer deferred before any target or token evaluation");
    }

    private void guardRaces(B08MvcSession mvc, RequestRef ref, RequestRef accepted) throws Exception {
        var grant = key(B10Postgresql.OWNER, Grant.EXECUTE_VERIFY);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var releaseEntered = new CountDownLatch(1);
            var releaseContinues = new CountDownLatch(1);
            timeCut.set(new TimeCut(3, () -> {
                releaseEntered.countDown();
                await(releaseContinues);
            }));
            var ready = workers.submit(() -> get(mvc, ref, "verify", ownerBearer));
            try {
                await(releaseEntered);
                var revoke = workers.submit(() -> profile.updates().stageRevocation(B10Postgresql.OPERATOR, grant));
                baseline.requireWaiting("FOR UPDATE");
                releaseContinues.countDown();
                success(ready.get(5, TimeUnit.SECONDS), ref, "verify", "CREATED", "body", BODY);
                var pending = revoke.get(5, TimeUnit.SECONDS);
                int reads = targetReads.get();
                problem(get(mvc, ref, "verify", ownerBearer), 503, "authority_unavailable");
                require(reads == targetReads.get(), "unconfirmed revocation reached protected target");
                baseline.confirm(pending);
            } finally {
                releaseContinues.countDown();
            }
            problem(get(mvc, ref, "verify", ownerBearer), 403, "action_denied");
            success(get(mvc, accepted, "publish", ownerBearer), accepted, "publish", "ACCEPTED", "name", NAME);
            enable(B10Postgresql.OWNER, Grant.EXECUTE_VERIFY);

            var revokeEntered = new CountDownLatch(1);
            var revokeContinues = new CountDownLatch(1);
            baseline.pauseBeforeScopeWitness(revokeEntered, revokeContinues);
            var revoke = workers.submit(() -> profile.updates().stageRevocation(B10Postgresql.OPERATOR, grant));
            try {
                await(revokeEntered);
                var waiting = workers.submit(() -> get(mvc, ref, "verify", ownerBearer));
                baseline.requireWaiting("FOR SHARE");
                revokeContinues.countDown();
                var pending = revoke.get(5, TimeUnit.SECONDS);
                problem(waiting.get(5, TimeUnit.SECONDS), 503, "authority_unavailable");
                baseline.confirm(pending);
            } finally {
                revokeContinues.countDown();
            }
            problem(get(mvc, ref, "verify", ownerBearer), 403, "action_denied");
        }
        System.out.println("PASS both native lock orders, pending scope deferral, and trusted expiry at the buffer handoff");
    }

    private RequestRef create(B08MvcSession mvc, String body) throws Exception {
        var issued = mvc.invoke(B08MvcSession.Call.issue(ownerBearer));
        require(issued.status() == 201, "operation key issuance");
        var created = mvc.invoke(B08MvcSession.Call.create(ownerBearer, issued.json().path("key").asString(),
                JSON.writeValueAsString(Map.of("type", B10Postgresql.TYPE.name(), "name", NAME, "body", body))));
        require(created.status() == 201 && !created.json().has("expectedRevision"), "Create wire compatibility");
        return new RequestRef(created.json().path("ref").asString());
    }

    private void transition(RequestRef ref, Lifecycle.TransitionCommand command) {
        fixtureTx.run(() -> {
            fixtureRecords.lockExclusive(B10Postgresql.ORG).orElseThrow();
            var current = fixtureRecords.find(B10Postgresql.ORG, ref).orElseThrow();
            var changed = Lifecycle.transition(current, command, new TransitionFact.Occurrence(NOW, "fixture"));
            var request = changed.request();
            fixtureRecords.appendFact(B10Postgresql.ORG, changed.transition());
            require(owner.update("""
                    UPDATE requests.request SET state = ?, revision = ?, last_sequence = ? WHERE ref = ?
                    """, request.state().name(), request.revision(), request.lastSequence(), ref.value()) == 1,
                    "fixture lifecycle state");
            return null;
        });
    }

    private void updateBody(RequestRef ref, String exact) {
        fixtureTx.run(() -> {
            fixtureRecords.lockExclusive(B10Postgresql.ORG).orElseThrow();
            require(owner.update("UPDATE requests.request_body SET body = ? WHERE ref = ?", exact, ref.value()) == 1,
                    "fixture material change");
            return null;
        });
    }

    private void advanceRevision(RequestRef ref) {
        fixtureTx.run(() -> {
            fixtureRecords.lockExclusive(B10Postgresql.ORG).orElseThrow();
            require(owner.update("UPDATE requests.request SET revision = revision + 1 WHERE ref = ?", ref.value()) == 1,
                    "fixture revision change");
            return null;
        });
    }

    private ActionContextService.Verification verify(RequestRef ref, Action action, String token) {
        return commandTx.run(() -> profile.actionContext().orElseThrow().verifyNewIntent(B10Postgresql.OWNER,
                ref, action, token));
    }

    private void enable(AuthenticatedActor actor, Grant... grants) {
        for (var grant : grants) {
            baseline.confirm(profile.updates().stageEnablement(B10Postgresql.OPERATOR, key(actor, grant)));
        }
    }

    private static GrantKey key(AuthenticatedActor actor, Grant grant) {
        return new GrantKey(actor, grant, Optional.of(B10Postgresql.TYPE));
    }

    private List<Long> counts() {
        return List.of(count(owner, "requests.request"), count(owner, "requests.request_body"),
                count(owner, "requests.transition_fact"), count(owner, "requests.operation_receipt"),
                count(proofOwner, "request_authority.creation_reservation"));
    }

    private static long count(JdbcTemplate connection, String table) {
        return connection.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static B08MvcSession.Reply get(B08MvcSession mvc, RequestRef ref, String action, String bearer)
            throws Exception {
        return mvc.invoke(MockMvcRequestBuilders.get(path(ref)).queryParam("action", action), bearer);
    }

    private static String path(RequestRef ref) {
        return "/requests/" + ref.value() + "/action-context";
    }

    private static String success(B08MvcSession.Reply reply, RequestRef ref, String action, String state,
                                  String field, String exact) {
        require(reply.status() == 200, "protected context expected 200, actual " + reply.status());
        require(reply.cache().equals("no-store") && reply.location().isEmpty()
                        && reply.contentType().toLowerCase(Locale.ROOT).equals("application/json;charset=utf-8"),
                "protected context response headers");
        var json = reply.json();
        require(json.size() == 5 && json.path("ref").asString().equals(ref.value())
                        && json.path("action").asString().equals(action) && json.path("state").asString().equals(state)
                        && json.path("context").size() == 1 && json.path("context").path(field).asString().equals(exact),
                "exact protected response fields");
        String observation = json.path("expectedRevision").asString();
        require(observation.matches("obs1\\.7\\.[A-Za-z0-9_-]{112}"), "bounded opaque token");
        return observation;
    }

    private static void problem(B08MvcSession.Reply reply, int status, String code) {
        require(reply.status() == status, "expected " + status + ", actual " + reply.status());
        var json = reply.json();
        require(reply.cache().equals("no-store") && reply.contentType().startsWith("application/problem+json")
                        && json.path("type").asString().equals("urn:requests:problem:" + code)
                        && !json.has("context") && !json.has("expectedRevision") && !json.has("state"),
                "safe problem response");
    }

    private Connection observeReads(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        if (sql.contains("SELECT request_type")) {
                            targetReads.incrementAndGet();
                        } else if (sql.contains("SELECT body FROM requests.request_body")) {
                            bodyReads.incrementAndGet();
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private Instant time() {
        var cut = timeCut.get();
        if (cut != null && cut.remaining().decrementAndGet() == 0 && timeCut.compareAndSet(cut, null)) {
            cut.at().run();
        }
        return clock.get();
    }

    private record TimeCut(AtomicInteger remaining, Runnable at) {
        TimeCut(int iteration, Runnable at) {
            this(new AtomicInteger(iteration), at);
        }
    }

    private static String alter(String token) {
        int offset = 40;
        char changed = token.charAt(offset) == 'A' ? 'B' : 'A';
        return token.substring(0, offset) + changed + token.substring(offset + 1);
    }

    private static String bearer() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret());
    }

    private static byte[] secret() {
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        return material;
    }

    private static void await(CountDownLatch entered) {
        try {
            require(entered.await(4, TimeUnit.SECONDS), "fixture barrier expired");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture barrier was interrupted");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
