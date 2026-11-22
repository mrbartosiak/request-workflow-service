import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.authority.JdbcReleaseEvidence.Input;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.GuardedRelease;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.ReleaseBuffer;
import com.company.requests.application.ReleaseEvidence.ClientReceipt;
import com.company.requests.application.ReleaseEvidence.Correlation;
import com.company.requests.application.ReleaseEvidence.Handoff;
import com.company.requests.application.ReleaseEvidence.PreparedStatus;
import com.company.requests.application.ReleaseEvidence.RestoredEffect;
import com.company.requests.application.ReleasePreparation;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.Action;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.TransitionFact;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/** B12 fixture with scoped, independently authenticated evidence inputs and the actual Spring MVC routes. */
public final class B12Postgresql {
    private static final Instant NOW = Instant.parse("2026-11-22T20:00:00Z");
    private static final Duration WINDOW = Duration.ofMillis(2400);
    private static final String JSON = """
            {"type":"HR_TICKET","name":"fixture name","body":"fixture body"}
            """;
    private static final String CHANGED = """
            {"type":"HR_TICKET","name":"changed name","body":"fixture body"}
            """;
    private static final IntentDigest.Create INTENT = new IntentDigest.Create(B10Postgresql.TYPE, "fixture name", "fixture body");
    private static final byte[] PAYLOAD = "synthetic envelope".getBytes(StandardCharsets.UTF_8);
    private static final GrantKey RESULT_GRANT = new GrantKey(B10Postgresql.OWNER, Grant.CREATE_RESULT,
            Optional.of(B10Postgresql.TYPE));
    private static final PreparedStatus PREPARED = new PreparedStatus(Handoff.UNKNOWN, ClientReceipt.UNKNOWN);
    private static final PreparedStatus HANDED_OFF = new PreparedStatus(Handoff.PROVED, ClientReceipt.UNKNOWN);
    private static final PreparedStatus ACKNOWLEDGED = new PreparedStatus(Handoff.PROVED, ClientReceipt.CONFIRMED);

    private final AtomicInteger localLookups = new AtomicInteger();
    private final AtomicInteger originalLookups = new AtomicInteger();
    private final AtomicBoolean distrustOriginal = new AtomicBoolean();
    private final AtomicBoolean withholdHandoff = new AtomicBoolean();
    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final AtomicReference<Runnable> afterPrepare = new AtomicReference<>();
    private final AtomicReference<Correlation> prepared = new AtomicReference<>();
    private final Map<ReceiptKey, OperationReceipt> originalEvidence = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final OperationSecrets secrets = new OperationSecrets(1, Map.of(1, secret()), 1, Map.of(1, secret()));
    private final String bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(secret());
    private final JdbcTemplate workflowOwner;
    private final JdbcTemplate proofOwner;
    private final B10Postgresql baseline;
    private final ReleasePreparation durablePreparation;
    private final JdbcReleaseEvidence.Reader reader;
    private final DataSource client;
    private final DataSource handoff;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;
    private final SyntheticEffectiveAccessConfiguration.Components profile;
    private final QualifyPostgresql.ScopeRestore restore;

    private B12Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                         QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                         QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                         QualifyPostgresql.ScopeRestore restore, B10FixtureSources sources) throws SQLException {
        this.restore = restore;
        workflowOwner = new JdbcTemplate(B08MvcSession.dataSource(workflow.migration()));
        var proofAdmin = B08MvcSession.dataSource(proof.migration());
        proofOwner = new JdbcTemplate(proofAdmin);
        baseline = new B10Postgresql(workflow, proof, projector, resolver, createProof, sources, this::observeLookups);
        String oldKey = baseline.components().create().issue(B10Postgresql.OWNER).key();
        require(baseline.components().create().execute(B10Postgresql.OWNER, oldKey, INTENT, ignored -> PAYLOAD)
                instanceof CreateCommand.Released, "legacy preparation before upgrade");
        UUID legacy = proofOwner.queryForObject("SELECT correlation_id FROM request_authority.release_prepared", UUID.class);

        var provisioning = new JdbcAuthorityProvisioning(proofAdmin);
        provisioning.migrateReleaseEvidence();
        provisioning.migrateReleaseEvidence();
        provisioning.authorizeReleaseEvidence("b12_prepare", B10Postgresql.ORG, Input.PREPARATION);
        provisioning.authorizeReleaseEvidence("b12_handoff", B10Postgresql.ORG, Input.HANDOFF);
        provisioning.authorizeReleaseEvidence("b12_client", B10Postgresql.ORG, Input.CLIENT);
        provisioning.authorizeReleaseEvidence("b12_reader", B10Postgresql.ORG, Input.READ);
        expectUnavailable(() -> provisioning.authorizeReleaseEvidence("b12_handoff", B10Postgresql.ORG, Input.CLIENT));
        proofOwner.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b12_prepare, b12_handoff, b12_client, b12_reader;
                GRANT EXECUTE ON FUNCTION request_authority.prepare_release(uuid, text, bytea, text, bigint, bigint,
                    bigint, uuid, bigint, text) TO b12_prepare;
                GRANT EXECUTE ON FUNCTION request_authority.record_release_evidence(uuid, text, bytea, text)
                    TO b12_handoff, b12_client;
                GRANT EXECUTE ON FUNCTION request_authority.read_release_evidence(uuid, text) TO b12_reader;
                """);
        durablePreparation = JdbcReleaseEvidence.preparation(B08MvcSession.dataSource(logins.preparation()));
        reader = JdbcReleaseEvidence.reader(B08MvcSession.dataSource(logins.reader()));
        client = B08MvcSession.dataSource(logins.client());
        handoff = B08MvcSession.dataSource(logins.handoff());
        require(reader.find(B10Postgresql.ORG, legacy).orElseThrow().equals(PREPARED), "legacy evidence state after V005");
        require(count(proofOwner, "request_authority.release_prepared") == 1, "legacy preparation preserved");
        workflowOwner.execute("GRANT SELECT ON requests.request, requests.operation_receipt TO b04_observer");
        System.out.println("PASS V005 keeps B08 preparation with handoff and client receipt unknown");

        var base = baseline.settings();
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", bearer, B10Postgresql.OWNER)));
        var ports = new SyntheticCreateConfiguration.GuardedReplay((admission, key) -> {
            originalLookups.incrementAndGet();
            if (distrustOriginal.get()) {
                throw new AuthorityUnavailableException();
            }
            return Optional.ofNullable(originalEvidence.get(key.receiptKey()));
        }, releasePreparation(), this::recordHandoff);
        var create = new SyntheticCreateConfiguration.Settings(base.create().command(), base.create().proof(),
                base.create().policy(), base.create().writer(), actors, clock::get, secrets, Optional.of(ports));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(create, base.preparation(), base.originalObserver(),
                base.projection(), base.resolver(), base.operators());
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.guardedRelease(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B12Postgresql(workflow, proof, projector, resolver, createProof, logins, restore, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B12 " + cause.getClass().getSimpleName() + detail);
                java.util.Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        try (var mvc = new B08MvcSession(settings)) {
            var restored = new JdbcTemplate(B08MvcSession.dataSource(restore.snapshot()));
            String key = issue(mvc);
            var first = post(mvc, key, JSON);
            success(first);
            Correlation firstRelease = prepared.get();
            require(status(firstRelease).equals(HANDED_OFF), "MVC response alone leaves client acknowledgment unknown");
            Counts counts = counts();

            require(post(mvc, key, JSON).equals(first), "exact current replay and headers");
            Correlation replay = prepared.get();
            require(!replay.id().equals(firstRelease.id()) && replay.resultDigest().equals(firstRelease.resultDigest()),
                    "duplicate request must use a separate release correlation for the same original result");
            require(counts.equals(counts()), "exact replay repeated an effect");
            String receivedRef = first.json().path("ref").asString();
            verifyClientResult(first, firstRelease);
            evidenceInputs(firstRelease, replay, recoveredEffect(restored, receivedRef));
            System.out.println("PASS exact replay preserved the original 201, safe headers and effect counts " + counts);

            withholdHandoff.set(true);
            require(post(mvc, key, JSON).equals(first), "accepted buffer when evidence write is lost");
            Correlation unretained = prepared.get();
            require(status(unretained).equals(PREPARED) && !status(unretained).lostAcknowledged(RestoredEffect.VERIFIED_MISSING),
                    "accepted handoff with missing evidence");
            System.out.println("PASS accepted enqueue without retained handoff evidence stays prepared, client receipt unknown");

            withholdHandoff.set(true);
            var received = post(mvc, key, JSON);
            require(received.equals(first), "same result without retained enqueue proof");
            var independent = prepared.get();
            verifyClientResult(received, independent);
            JdbcReleaseEvidence.acknowledgments(client).record(independent);
            require(status(independent).equals(new PreparedStatus(Handoff.UNKNOWN, ClientReceipt.CONFIRMED)),
                    "independent client evidence must not fabricate handoff evidence");
            System.out.println("PASS independent client receipt could be confirmed while handoff evidence stayed unknown");

            var small = new ReleaseBuffer(1);
            require(profile.create().execute(B10Postgresql.OWNER, key, INTENT, ignored -> PAYLOAD, small)
                    == CreateCommand.Rejection.OUTCOME_UNKNOWN, "bounded buffer failure");
            require(small.take().isEmpty() && status(prepared.get()).equals(PREPARED), "unaccepted bounded slot");
            require(post(mvc, key, JSON).equals(first) && counts.equals(counts()), "buffer failure consumed the committed receipt");
            System.out.println("PASS rejected buffer left one effect and the same authorized retry result");

            rollbackAfterStage(key);
            require(post(mvc, key, JSON).equals(first) && counts.equals(counts()), "aborted enqueue consumed the committed receipt");
            earlierGenerations(mvc);
            revocationOrders(mvc, key, first);
            expiry(mvc);
        }
    }

    private void verifyClientResult(B08MvcSession.Reply reply, Correlation correlation) {
        var observed = reply.json();
        String ref = proofOwner.queryForObject("""
                SELECT result_ref FROM request_authority.release_result_prepared
                WHERE correlation_id = ? AND organization = ? AND encode(result_digest, 'hex') = ?
                """, String.class, correlation.id(), correlation.organization().value(), correlation.resultDigest());
        require(reply.status() == 201 && reply.location().equals("/requests/" + ref)
                        && observed.path("ref").asString().equals(ref) && observed.path("action").asString().equals("create")
                        && observed.path("resultRevision").asString().equals("1")
                        && observed.path("state").asString().equals("CREATED"),
                "client result did not match the independently prepared correlation");
    }

    private void evidenceInputs(Correlation first, Correlation duplicate, RestoredEffect restored) {
        var clientEvidence = JdbcReleaseEvidence.acknowledgments(client);
        var handoffs = JdbcReleaseEvidence.handoffs(handoff);
        require(status(duplicate).equals(HANDED_OFF) && !status(duplicate).lostAcknowledged(RestoredEffect.VERIFIED_MISSING),
                "duplicate's independent client receipt should be unknown");
        expectUnavailable(() -> JdbcReleaseEvidence.acknowledgments(handoff).record(first));
        expectUnavailable(() -> JdbcReleaseEvidence.handoffs(client).record(first));
        expectUnavailable(() -> clientEvidence.record(new Correlation(first.id(), B10Postgresql.OTHER, first.resultDigest())));
        expectUnavailable(() -> clientEvidence.record(new Correlation(first.id(), first.organization(), "0".repeat(64))));
        expectUnavailable(() -> clientEvidence.record(new Correlation(UUID.randomUUID(), first.organization(), first.resultDigest())));
        expectUnavailable(() -> reader.find(B10Postgresql.OTHER, first.id()));
        require(reader.find(B10Postgresql.ORG, UUID.randomUUID()).isEmpty(), "absent evidence should be explicit");
        handoffs.record(first);
        clientEvidence.record(first);
        clientEvidence.record(first);
        require(count(proofOwner, "request_authority.release_handoff_proved") == 2
                        && count(proofOwner, "request_authority.release_client_acknowledged") == 1,
                "repeated evidence must be idempotent per release correlation");
        require(status(first).equals(ACKNOWLEDGED) && status(duplicate).equals(HANDED_OFF),
                "client acknowledgment spilled across duplicate requests");
        require(restored == RestoredEffect.VERIFIED_MISSING && status(first).lostAcknowledged(restored)
                        && !status(duplicate).lostAcknowledged(restored)
                        && !status(first).lostAcknowledged(RestoredEffect.VERIFIED_PRESENT)
                        && !status(first).lostAcknowledged(RestoredEffect.UNKNOWN),
                "lost acknowledged requires both independent acknowledgment and verified missing effect");
        System.out.println("PASS scoped evidence and duplicate correlations, only the independently acknowledged missing effect counted after verified rewind");
    }

    private RestoredEffect recoveredEffect(JdbcTemplate snapshot, String ref) {
        long requests = snapshot.queryForObject("SELECT count(*) FROM requests.request WHERE organization = ? AND ref = ?",
                Long.class, B10Postgresql.ORG.value(), ref);
        long receipts = snapshot.queryForObject("SELECT count(*) FROM requests.operation_receipt WHERE organization = ? AND result_ref = ?",
                Long.class, B10Postgresql.ORG.value(), ref);
        require(requests == receipts && requests <= 1, "restored workflow and receipt membership disagree");
        return requests == 1 ? RestoredEffect.VERIFIED_PRESENT : RestoredEffect.VERIFIED_MISSING;
    }

    private void rollbackAfterStage(String signedKey) {
        var source = settings.create().command();
        var tx = JdbcTransactionRunner.forCreate(source);
        var stores = new JdbcWorkflowRecords(source);
        var receipts = new JdbcReceiptStore(source);
        var keys = new OperationKeyIssuer(profile.access(), clock::get, secrets, Duration.ofSeconds(3));
        var failing = new TransactionRunner() {
            @Override
            public <T> T run(Supplier<T> work) {
                return tx.run(() -> {
                    T outcome = work.get();
                    require(outcome == GuardedRelease.Outcome.HANDED_OFF, "expected pending buffer stage before rollback");
                    throw new CommitOutcomeUnknownException(new SQLException("synthetic guarded commit cut", "08006"));
                });
            }
        };
        var release = new GuardedRelease(failing, stores, stores, profile.access(), WINDOW, this::recordHandoff);
        var buffer = new ReleaseBuffer(128);
        try {
            release.handoff(B10Postgresql.ORG, guard -> profile.access().requireCreate(B10Postgresql.OWNER,
                    B10Postgresql.TYPE, guard), () -> true, context -> {
                        var verified = keys.verify(signedKey, B10Postgresql.OWNER, context.admission());
                        var receipt = receipts.find(verified.receiptKey()).orElseThrow();
                        return Optional.of(new GuardedRelease.Prepared(new GuardedRelease.Payload(PAYLOAD,
                                releasePreparation().prepare(context.admission(), receipt)),
                                current -> !profile.access().permittedFields(B10Postgresql.OWNER,
                                        current.guard(), receipt.resultRef()).isEmpty()));
                    }, buffer);
            throw new AssertionError("expected guarded commit cut");
        } catch (CommitOutcomeUnknownException expected) {
            require(buffer.take().isEmpty() && status(prepared.get()).equals(PREPARED), "aborted staged handoff evidence");
        }
        System.out.println("PASS durable preparation survived rollback after buffer staging, no drainable bytes or handoff proof");
    }

    private void earlierGenerations(B08MvcSession mvc) throws Exception {
        var previous = new WriterEpoch(UUID.randomUUID(), B10Postgresql.WRITER.generation() - 1);
        var policy = settings.effectivePolicy();
        var oldKeys = new OperationKeyIssuer(new CurrentAccess(ignored -> { }, previous, List.of(policy)),
                clock::get, secrets, Duration.ofSeconds(3));
        var oldAdmission = new Admission(policy.genesis(), previous);
        String old = oldKeys.issue(B10Postgresql.OWNER).key();
        String absent = oldKeys.issue(B10Postgresql.OWNER).key();
        String purged = oldKeys.issue(B10Postgresql.OWNER).key();
        var visible = historic(oldKeys.verify(old, B10Postgresql.OWNER, oldAdmission), true);
        historic(oldKeys.verify(purged, B10Postgresql.OWNER, oldAdmission), false);
        Counts before = counts();
        long preparations = count(proofOwner, "request_authority.release_result_prepared");
        var initial = prior(mvc, absent, JSON);
        problem(initial, 409, "retry_fenced");
        distrustOriginal.set(true);
        var untrusted = prior(mvc, old, JSON);
        distrustOriginal.set(false);
        var missingAfterPurge = prior(mvc, purged, JSON);

        var disclosed = prior(mvc, old, JSON);
        success(disclosed);
        require(disclosed.json().path("ref").asString().equals(visible.resultRef().value()),
                "trusted earlier receipt must release the original result");
        require(prior(mvc, old, JSON).equals(disclosed), "exact old replay status and safe headers");
        problem(prior(mvc, old, CHANGED), 409, "key_conflict");
        baseline.confirm(profile.updates().stageRevocation(B10Postgresql.OPERATOR, RESULT_GRANT));
        var concealed = prior(mvc, old, JSON);
        baseline.confirm(profile.updates().stageEnablement(B10Postgresql.OPERATOR, RESULT_GRANT));
        require(normalized(initial).equals(normalized(untrusted))
                        && normalized(initial).equals(normalized(missingAfterPurge))
                        && normalized(initial).equals(normalized(concealed)), "earlier-generation fenced variants differ");
        require(before.equals(counts()) && count(proofOwner, "request_authority.release_result_prepared") == preparations + 2,
                "earlier replay repeated a local effect or fenced variants recorded a preparation");
        System.out.println("PASS each earlier variant used one trusted-port lookup and zero local lookups");
        System.out.println("PASS identical normalized fenced 409 for missing, untrusted, purged and concealed outcomes");
        System.out.println("PASS opted-in earlier proof permitted exact replay and authorized conflict, zero effect writes");

        int local = localLookups.get();
        int original = originalLookups.get();
        problem(mvc.invoke(B08MvcSession.Call.create("", old, JSON)), 401, "authentication_required");
        var other = new AuthenticatedActor("b10-issuer", B10Postgresql.ORG, "unbound-principal",
                AuthenticatedActor.Kind.HUMAN, B10Postgresql.OWNER.person());
        var otherPolicy = new SyntheticScopePolicy(B10Postgresql.ORG, 1, List.of(
                new SyntheticScopePolicy.Grant(other, true, Set.of(B10Postgresql.TYPE), Set.of(B10Postgresql.TYPE))));
        var otherKeys = new OperationKeyIssuer(new CurrentAccess(ignored -> { }, previous, List.of(otherPolicy)),
                clock::get, secrets, Duration.ofSeconds(3));
        problem(post(mvc, otherKeys.issue(other).key(), JSON), 400, "invalid_input");
        proofOwner.execute("REVOKE SELECT ON request_authority.scope_journal_head FROM b04_proof");
        try {
            problem(post(mvc, old, JSON), 503, "authority_unavailable");
        } finally {
            proofOwner.execute("GRANT SELECT ON request_authority.scope_journal_head TO b04_proof");
        }
        require(localLookups.get() == local && originalLookups.get() == original, "authorization preflight leaked a lookup");
        System.out.println("PASS unauthenticated, wrong signed scope, and unavailable confirmed authority performed zero lookups");
    }

    private OperationReceipt historic(OperationKeyIssuer.VerifiedKey key, boolean retained) {
        // The private synthetic proof source emulates an already certified exact original receipt.
        // A separately seeded workflow row models what recovery retained and never serves as that proof.
        var ref = new RequestRef(Base64.getUrlEncoder().withoutPadding().encodeToString(secret()).substring(0, 32));
        var operation = new OperationReceipt(key.receiptKey(), new IntentDigest(secrets).calculate(INTENT), key.generation(),
                key.expiresAt(), Action.CREATE, ref, 1, RequestState.CREATED, Optional.empty(), 201);
        originalEvidence.put(key.receiptKey(), operation);
        if (retained) {
            var source = settings.create().command();
            var stores = new JdbcWorkflowRecords(source);
            var receipts = new JdbcReceiptStore(source);
            var effect = Lifecycle.create(new Lifecycle.Creation(ref, INTENT.type(), B10Postgresql.ORG, INTENT.name(),
                            new RequestBody(INTENT.body()), new CreationOrder(key.generation(), originalEvidence.size()),
                            B10Postgresql.OWNER.person().orElseThrow()),
                    new TransitionFact.Occurrence(NOW, B10Postgresql.OWNER.principal()));
            JdbcTransactionRunner.forCreate(source).run(() -> {
                stores.insert(effect.request());
                stores.insertBody(B10Postgresql.ORG, ref, effect.body());
                stores.appendFact(B10Postgresql.ORG, effect.transition());
                receipts.insertInitialAssignment(B10Postgresql.ORG, ref, B10Postgresql.OWNER.person().orElseThrow());
                receipts.insert(operation);
                return null;
            });
        }
        return operation;
    }

    private void revocationOrders(B08MvcSession mvc, String key, B08MvcSession.Reply first) throws Exception {
        var createGrant = new GrantKey(B10Postgresql.OWNER, Grant.CREATE, Optional.of(B10Postgresql.TYPE));
        Counts before = counts();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            afterPrepare.set(() -> {
                entered.countDown();
                await(resume);
            });
            var receiving = executor.submit(() -> post(mvc, key, JSON));
            try {
                await(entered);
                var writer = executor.submit(() -> profile.updates().stageRevocation(B10Postgresql.OPERATOR, createGrant));
                baseline.requireWaiting("FOR UPDATE");
                resume.countDown();
                require(receiving.get(4, TimeUnit.SECONDS).equals(first), "shared release lost to later queued revocation");
                baseline.confirm(writer.get(4, TimeUnit.SECONDS));
            } finally {
                resume.countDown();
            }
            int lookups = localLookups.get();
            problem(post(mvc, key, JSON), 404, "not_found");
            require(localLookups.get() == lookups, "confirmed submitted revocation performed a receipt lookup");
            baseline.confirm(profile.updates().stageEnablement(B10Postgresql.OPERATOR, createGrant));

            var writing = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            baseline.pauseBeforeScopeWitness(writing, finish);
            var writer = executor.submit(() -> profile.updates().stageRevocation(B10Postgresql.OPERATOR, createGrant));
            try {
                await(writing);
                var waiting = executor.submit(() -> post(mvc, key, JSON));
                baseline.requireWaiting("FOR SHARE");
                finish.countDown();
                var pending = writer.get(4, TimeUnit.SECONDS);
                problem(waiting.get(4, TimeUnit.SECONDS), 503, "authority_unavailable");
                baseline.confirm(pending);
                problem(post(mvc, key, JSON), 404, "not_found");
                require(localLookups.get() == lookups, "prior writer or confirmed denial performed a receipt lookup");
            } finally {
                finish.countDown();
            }
            baseline.confirm(profile.updates().stageEnablement(B10Postgresql.OPERATOR, createGrant));
        }
        require(counts().equals(before), "revocation races repeated an effect");
        System.out.println("PASS PostgreSQL shared enqueue first and exclusive revocation first with audited confirmation and zero denied lookups");
    }

    private void expiry(B08MvcSession mvc) throws Exception {
        String expiring = issue(mvc);
        Counts before = counts();
        afterPrepare.set(() -> clock.set(NOW.plus(OperationKeyIssuer.VALIDITY)));
        problem(post(mvc, expiring, JSON), 409, "key_expired");
        require(status(prepared.get()).equals(PREPARED) && counts().equals(before.withOneCreate()),
                "expiry after preparation must preserve only the committed effect and unknown handoff");
        Counts retained = counts();
        int local = localLookups.get();
        int original = originalLookups.get();
        problem(post(mvc, expiring, JSON), 409, "key_expired");
        require(localLookups.get() == local && originalLookups.get() == original && counts().equals(retained),
                "signed cutoff preceded all receipt access");
        System.out.println("PASS trusted time advanced after durable preparation, expiry won at enqueue and prevented later lookup");
        System.out.println("PASS workflow and proof final counts " + retained);
    }

    private ReleasePreparation releasePreparation() {
        return new ReleasePreparation() {
            @Override
            public void append(Admission admission, OperationReceipt receipt) {
                prepare(admission, receipt);
            }

            @Override
            public Optional<Correlation> prepare(Admission admission, OperationReceipt receipt) {
                var correlation = durablePreparation.prepare(admission, receipt).orElseThrow();
                prepared.set(correlation);
                var cut = afterPrepare.getAndSet(null);
                if (cut != null) {
                    cut.run();
                }
                return Optional.of(correlation);
            }
        };
    }

    private void recordHandoff(Correlation correlation) {
        if (withholdHandoff.getAndSet(false)) {
            throw new AuthorityUnavailableException();
        }
        JdbcReleaseEvidence.handoffs(handoff).record(correlation);
    }

    private Connection observeLookups(Connection target) {
        return Connection.class.cast(Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.contains("SELECT intent_digest_version") && sql.contains("FROM requests.operation_receipt")) {
                        localLookups.incrementAndGet();
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }));
    }

    private PreparedStatus status(Correlation correlation) {
        return reader.find(correlation.organization(), correlation.id()).orElseThrow();
    }

    private B08MvcSession.Reply prior(B08MvcSession mvc, String key, String body) throws Exception {
        int local = localLookups.get();
        int original = originalLookups.get();
        var reply = post(mvc, key, body);
        require(originalLookups.get() == original + 1 && localLookups.get() == local,
                "earlier result checked outside the original proof source");
        return reply;
    }

    private String issue(B08MvcSession mvc) throws Exception {
        var response = mvc.invoke(B08MvcSession.Call.issue(bearer));
        require(response.status() == 201, "key issuance status=" + response.status());
        return response.json().path("key").asString();
    }

    private B08MvcSession.Reply post(B08MvcSession mvc, String key, String body) throws Exception {
        return mvc.invoke(B08MvcSession.Call.create(bearer, key, body));
    }

    private static void success(B08MvcSession.Reply reply) {
        require(reply.status() == 201 && reply.location().startsWith("/requests/") && reply.cache().equals("no-store")
                && reply.contentType().startsWith("application/json"), "expected 201 with original safe headers, got " + reply.status());
    }

    private static void problem(B08MvcSession.Reply reply, int status, String code) {
        require(reply.status() == status && reply.location().isEmpty() && reply.cache().equals("no-store")
                && reply.contentType().startsWith("application/problem+json")
                && reply.json().path("type").asString().equals("urn:requests:problem:" + code),
                "expected normalized " + code + " status=" + status + " actual=" + reply.status());
    }

    private static B08MvcSession.Reply normalized(B08MvcSession.Reply reply) {
        var json = (ObjectNode) reply.json();
        json.remove("instance");
        json.remove("correlationId");
        return new B08MvcSession.Reply(reply.status(), reply.location(), reply.cache(), reply.contentType(), json.toString());
    }

    private Counts counts() {
        return new Counts(count(workflowOwner, "requests.operation_receipt"), count(workflowOwner, "requests.request"),
                count(workflowOwner, "requests.request_body"), count(workflowOwner, "requests.transition_fact"),
                count(workflowOwner, "requests.request_assignment"), count(proofOwner, "request_authority.creation_reservation"));
    }

    private static long count(JdbcTemplate database, String table) {
        return database.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private record Counts(long receipts, long requests, long bodies, long facts, long assignments, long reservations) {
        Counts withOneCreate() {
            return new Counts(receipts + 1, requests + 1, bodies + 1, facts + 1, assignments + 1, reservations + 1);
        }
    }

    private byte[] secret() {
        var value = new byte[32];
        random.nextBytes(value);
        return value;
    }

    private static void expectUnavailable(Runnable run) {
        try {
            run.run();
        } catch (AuthorityUnavailableException expected) {
            return;
        }
        throw new AssertionError("expected independent evidence authorization denial");
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(4, TimeUnit.SECONDS), "B12 bounded barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("B12 barrier interrupted");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
