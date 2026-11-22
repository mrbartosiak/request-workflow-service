import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.jdbc.B08PriorWorkflowFixture;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.Action;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.TransitionFact;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class B08Postgresql {
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final String NAME = " Zażółć 東京 ";
    private static final String BODY = "line 1\r\nline 2 🚀";
    private static final String CREATE = "{\"type\":\"HR_TICKET\",\"name\":\" Zażółć 東京 \",\"body\":\"line 1\\r\\nline 2 🚀\"}";
    private static final String REORDERED = "{\"body\":\"line 1\\r\\nline 2 🚀\",\"name\":\" Zażółć 東京 \",\"type\":\"HR_TICKET\"}";
    private static final String CHANGED = CREATE.replace("line 1", "Line 1");
    private static final String SECRET_TYPE = CREATE.replace("HR_TICKET", "BUG_REPORT");
    private static final OrganizationId PRIOR_ORG = new OrganizationId("b08-v002-control");
    private static final ReceiptKey PRIOR_KEY = new ReceiptKey(B08MvcSession.ISSUER, PRIOR_ORG, "prior-person", "prior-v002");
    private static final List<String> LOCAL = List.of("request", "request_body", "transition_fact",
            "request_assignment", "operation_receipt");
    private final B08MvcSession.Parameters params = B08MvcSession.Parameters.generate();
    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final Trace trace = new Trace();
    private final DataSource workflow;
    private final DataSource proof;
    private final DataSource workflowRoot;
    private final DataSource proofRoot;
    private final JdbcTemplate workflowAdmin;
    private final JdbcTemplate proofAdmin;
    private final JdbcTemplate workflowObserver;
    private final JdbcTemplate proofObserver;
    private final QualifyPostgresql.Connections workflowCluster;
    private final QualifyPostgresql.Connections proofCluster;

    private B08Postgresql(QualifyPostgresql.Connections workflowCluster, QualifyPostgresql.Connections proofCluster) {
        this.workflowCluster = workflowCluster;
        this.proofCluster = proofCluster;
        workflow = B08MvcSession.dataSource(() -> trace.watch(workflowCluster.runtime().open(), false));
        proof = B08MvcSession.dataSource(() -> trace.watch(proofCluster.runtime().open(), true));
        workflowRoot = B08MvcSession.dataSource(workflowCluster.migration());
        proofRoot = B08MvcSession.dataSource(proofCluster.migration());
        workflowAdmin = new JdbcTemplate(workflowRoot);
        proofAdmin = new JdbcTemplate(proofRoot);
        workflowObserver = new JdbcTemplate(B08MvcSession.dataSource(workflowCluster.observer()));
        proofObserver = new JdbcTemplate(B08MvcSession.dataSource(proofCluster.observer()));
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.workflowAndProof(args, "b08", (workflow, proof) -> {
                var check = new B08Postgresql(workflow, proof);
                check.provision();
                check.crossProcess(Path.of(args[2], args[3]));
                try (var mvc = check.mvc()) {
                    check.accessAndExactReplay(mvc);
                    check.preLookupClosures(mvc);
                    check.knownRollback(mvc);
                    check.unknownCommits(mvc);
                    check.withheldRelease(mvc);
                    check.claimAndGuardOrder(mvc);
                    check.pastGeneration(mvc);
                    check.unprovedCommitResolution(mvc);
                    check.expiryDuringPreparation(mvc);
                    check.restartedWriter(mvc);
                }
            });
        } catch (Exception | AssertionError failure) {
            report(failure);
            System.exit(1);
        }
    }

    private void provision() {
        B08PriorWorkflowFixture.migrateV002(workflowRoot);
        var admin = new JdbcTransactionRunner(workflowRoot);
        var records = new JdbcWorkflowRecords(workflowRoot);
        var receipts = new JdbcReceiptStore(workflowRoot);
        var priorPerson = new PersonId("prior-person");
        var priorRef = new RequestRef("b08-v002-ref");
        var prior = new OperationReceipt(PRIOR_KEY, new ReceiptIntentDigest(1, "aa"), 2, NOW.plusSeconds(90),
                Action.CREATE, priorRef, 1, RequestState.CREATED, Optional.empty(), 201);
        admin.run(() -> {
            records.insert(new PolicyGuard(PRIOR_ORG, 1));
            var created = Lifecycle.create(new Lifecycle.Creation(priorRef, B08MvcSession.VISIBLE, PRIOR_ORG, "Prior",
                            new RequestBody("Prior body"), new CreationOrder(2, 1), priorPerson),
                    new TransitionFact.Occurrence(NOW, "prior-principal"));
            records.insert(created.request());
            records.insertBody(PRIOR_ORG, priorRef, created.body());
            records.appendFact(PRIOR_ORG, created.transition());
            receipts.insert(prior);
            return null;
        });

        var migrations = new JdbcWorkflowMigrations(workflowRoot);
        migrations.migrate();
        migrations.migrate();
        require(admin.run(() -> receipts.find(PRIOR_KEY)).orElseThrow().equals(prior), "V002 receipt changed on migration");
        require(!admin.run(() -> receipts.claim(PRIOR_KEY, prior.intentDigest(), 2, prior.expiresAt())),
                "an existing V002 receipt must defeat the new claim");
        admin.run(() -> {
            records.insert(new PolicyGuard(B08MvcSession.ORG, 1));
            return null;
        });
        var missing = new ReceiptKey(B08MvcSession.ISSUER, B08MvcSession.ORG, "test", "unfinalized-control");
        try {
            admin.run(() -> {
                require(receipts.claim(missing, new ReceiptIntentDigest(1, "bb"), B08MvcSession.GENERATION, NOW.plusSeconds(90)),
                        "candidate claim must insert");
                require(receipts.find(missing).isEmpty(), "tentative receipt must be unreadable");
                return null;
            });
            throw new AssertionError("An unfinished claim committed");
        } catch (CommitOutcomeUnknownException failure) {
            require(hasSqlState(failure, "23503"), "deferred finalization invariant must reject commit");
        }

        workflowAdmin.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer");
        workflowAdmin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer");
        workflowAdmin.execute("""
                GRANT SELECT, INSERT ON requests.request, requests.request_body, requests.transition_fact,
                    requests.operation_receipt, requests.request_assignment TO b04_workflow
                """);
        workflowAdmin.execute("GRANT SELECT, UPDATE (policy_version) ON requests.policy_guard TO b04_workflow");
        workflowAdmin.execute("""
                GRANT UPDATE (result_action, result_ref, result_revision, result_state, publication_namespace,
                    publication_id, original_status) ON requests.operation_receipt TO b04_workflow
                """);

        var provisioning = new JdbcAuthorityProvisioning(proofRoot);
        provisioning.migrate();
        provisioning.migrate();
        provisioning.certifyGenesis(B08MvcSession.POLICY.genesis());
        provisioning.certifyWriter(params.writer());
        var identity = JdbcWorkflowIdentity.inspect(workflowRoot);
        provisioning.certifyWorkflow(params.writer(), identity);
        provisioning.certifyWorkflow(params.writer(), identity);
        proofAdmin.execute("GRANT USAGE ON SCHEMA request_authority TO b04_proof, b04_observer");
        proofAdmin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA request_authority TO b04_observer");
        proofAdmin.execute("""
                GRANT SELECT ON request_authority.writer_epoch, request_authority.scope_genesis,
                    request_authority.creation_counter, request_authority.creation_reservation,
                    request_authority.workflow_certificate TO b04_proof
                """);
        proofAdmin.execute("GRANT INSERT ON request_authority.creation_reservation, request_authority.release_prepared TO b04_proof");
        proofAdmin.execute("GRANT UPDATE (last_ordinal) ON request_authority.creation_counter TO b04_proof");

        require(List.of(1, 2, 3).equals(workflowObserver.queryForList(
                "SELECT version FROM requests.schema_migration ORDER BY version", Integer.class)), "workflow migration history");
        require(List.of(1, 2).equals(proofObserver.queryForList(
                "SELECT version FROM request_authority.schema_migration ORDER BY version", Integer.class)), "proof migration history");
        expectCount(0);
        System.out.println("PASS V002 finalized-row upgrade, deferred claim finalization, separate proof migration and role grants");
    }

    private B08MvcSession mvc() {
        return new B08MvcSession(workflow, proof, clock::get, params);
    }

    private void crossProcess(Path run) throws Exception {
        String key;
        try (var mvc = mvc()) {
            problem(mvc.invoke(B08MvcSession.Call.create("", "malformed", "{")), 401, "authentication_required");
            key = issue(mvc, owner());
        }
        var create = B08MvcSession.child(workflowCluster.runtimeLogin(), proofCluster.runtimeLogin(), params, NOW,
                B08MvcSession.Call.create(owner(), key, CREATE), run.resolve("mvc-worker-create.log"));
        created(create.reply());
        expectCount(1);
        var replay = B08MvcSession.child(workflowCluster.runtimeLogin(), proofCluster.runtimeLogin(), params, NOW,
                B08MvcSession.Call.create(params.bearers().get(1), key, REORDERED), run.resolve("mvc-worker-replay.log"));
        require(create.pid() != replay.pid() && create.reply().equals(replay.reply()), "fresh JVM exact replay changed result");
        require(originAndAssignment(create.reply()), "origin, initial assignee and exact UTF-8 content must commit together");
        expectCount(1);
        require(reservations() == 1 && preparations() == 2, "cross-process replay must add preparation, never a reservation");
        require(Long.valueOf(1).equals(proofObserver.queryForObject("""
                SELECT count(DISTINCT key_digest) FROM request_authority.release_prepared
                WHERE organization = ? AND result_ref = ? AND result_revision = 1 AND generation = ?
                """, Long.class, B08MvcSession.ORG.value(), create.reply().json().get("ref").asString(),
                B08MvcSession.GENERATION)), "separate preparations must identify the same scoped key and durable result");
        System.out.printf("PASS two exited application JVMs pid=%d and pid=%d, same HTTP 201 body/Location and one five-row effect%n",
                create.pid(), replay.pid());
    }

    private void accessAndExactReplay(B08MvcSession mvc) throws Exception {
        trace.clear();
        problem(mvc.invoke(B08MvcSession.Call.issue(params.bearers().get(3))), 403, "action_denied");
        String own = issue(mvc, owner());
        problem(mvc.invoke(B08MvcSession.Call.create(params.bearers().get(2), own, CREATE)), 404, "not_found");
        problem(mvc.invoke(B08MvcSession.Call.create(params.bearers().get(3), own, CREATE)), 400, "invalid_input");
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), own, "{\"type\":\"HR_TICKET\",\"name\":\"x\",\"body\":\"x\",\"body\":\"x\"}")),
                400, "invalid_input");
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), "altered", CREATE)), 400, "invalid_input");
        require(trace.claims.get() == 0 && trace.lookups.get() == 0, "authentication, submitted grant and validation must precede SQL");

        var result = mvc.invoke(B08MvcSession.Call.create(owner(), own, CREATE));
        created(result);
        require(originAndAssignment(result), "new request did not contain trusted origin and assignment");
        expectCount(2);
        require(Long.valueOf(2).equals(workflowObserver.queryForObject("""
                SELECT count(DISTINCT ref) FROM requests.request WHERE organization = ? AND name = ?
                """, Long.class, B08MvcSession.ORG.value(), NAME)), "two fresh keys must create distinct refs");
        long preparation = preparations();
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), own, CHANGED)), 409, "key_conflict");
        require(preparation == preparations(), "conflict must disclose no minimized result");
        String concealed = issue(mvc, owner());
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), concealed, SECRET_TYPE)), 404, "not_found");
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), concealed, CREATE)), 404, "not_found");
        expectCount(3);
        require(preparation == preparations(), "concealment must append nothing");
        System.out.println("PASS fresh exact keys, byte-level conflict, actor alias, and changed intent with concealed original");
    }

    private void preLookupClosures(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        trace.clear();
        clock.set(NOW.plus(OperationKeyIssuer.VALIDITY));
        try {
            problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 409, "key_expired");
        } finally {
            clock.set(NOW);
        }
        for (String proof : List.of("scope_genesis", "writer_epoch", "workflow_certificate")) {
            proofAdmin.execute("REVOKE SELECT ON request_authority." + proof + " FROM b04_proof");
            try {
                problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 503, "authority_unavailable");
                problem(mvc.invoke(B08MvcSession.Call.issue(owner())), 503, "authority_unavailable");
            } finally {
                proofAdmin.execute("GRANT SELECT ON request_authority." + proof + " TO b04_proof");
            }
        }
        require(trace.claims.get() == 0 && trace.lookups.get() == 0, "expired/unproved keys must never arbitrate or query a receipt");
        clock.set(NOW.plus(OperationKeyIssuer.VALIDITY).minusSeconds(2));
        try {
            problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 503, "outcome_unknown");
        } finally {
            clock.set(NOW);
        }
        require(trace.claims.get() == 0 && trace.lookups.get() == 1, "admission-margin keys may only look up");
        expectCount(3);
        System.out.println("PASS expiry and missing genesis/epoch/certificate with zero claims/reads; admission margin lookup only");
    }

    private void knownRollback(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        long reservations = reservations();
        long preparations = preparations();
        trace.inject.set(Fault.FAIL_FINALIZE);
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 500, "internal_error");
        expectCount(3);
        require(reservations() == reservations + 1 && preparations() == preparations,
                "a precommit rollback must burn only the independent reservation");
        require(trace.inject.get() == Fault.NONE, "precommit cut did not run");
        System.out.println("PASS precommit write fault, zero local delta, burned reservation and zero new preparation");
    }

    private void unknownCommits(B08MvcSession mvc) throws Exception {
        String committed = issue(mvc, owner());
        long reservations = reservations();
        trace.inject.set(Fault.LOST_COMMIT_REPLY);
        created(mvc.invoke(B08MvcSession.Call.create(owner(), committed, CREATE)));
        expectCount(4);
        require(reservations() == reservations + 1, "settling a lost successful commit cannot re-execute");
        String notCommitted = issue(mvc, owner());
        trace.inject.set(Fault.UNKNOWN_ABORT);
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), notCommitted, CREATE)), 503, "outcome_unknown");
        expectCount(4);
        require(reservations() == reservations + 2, "uncertain absence must not re-execute in the resolving call");
        created(mvc.invoke(B08MvcSession.Call.create(owner(), notCommitted, CREATE)));
        expectCount(5);
        require(reservations() == reservations + 3, "certified same-key retry needs a new reservation after rollback");
        System.out.println("PASS lost commit acknowledgement proved by finalized receipt, uncertain absence withheld, certified retry commits once");
    }

    private void withheldRelease(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        long preparations = preparations();
        proofAdmin.execute("REVOKE INSERT ON request_authority.release_prepared FROM b04_proof");
        try {
            problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 503, "outcome_unknown");
            expectCount(6);
            require(preparations() == preparations, "refused proof must leave the committed effect unpublished");
        } finally {
            proofAdmin.execute("GRANT INSERT ON request_authority.release_prepared TO b04_proof");
        }
        trace.beforePreparation.set(() -> {
            expectGuardNowait(false);
            require(count("request") == 6 && count("operation_receipt") == 6,
                    "independent release prepare ran before visible commit");
        });
        created(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)));
        expectGuardNowait(true);
        expectCount(6);
        require(trace.beforePreparation.get() == null && preparations() == preparations + 1,
                "replay must prepare under the shared guard once");
        System.out.println("PASS postcommit withheld response, current guarded replay, shared lock through independent proof and unlock before return");
    }

    private void claimAndGuardOrder(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        var beforeGuard = new CountDownLatch(1);
        var continueCreate = new CountDownLatch(1);
        trace.clear();
        trace.beforeExclusive.set(() -> {
            beforeGuard.countDown();
            require(continueCreate.await(6, TimeUnit.SECONDS), "claim-order barrier timed out");
        });
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)));
            try {
                require(beforeGuard.await(6, TimeUnit.SECONDS), "first transaction never reached its exclusive guard");
                require(trace.claims.get() == 1 && trace.lookups.get() == 0 && count("operation_receipt") == 6,
                        "tentative claim should hold its key without a globally visible row");
                expectGuardNowait(true);
                problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 503, "outcome_unknown");
                require(trace.claims.get() == 2 && trace.lookups.get() == 0,
                        "the same-key contender must time out before acquiring any guard or looking up");
            } finally {
                continueCreate.countDown();
            }
            var committed = first.get(8, TimeUnit.SECONDS);
            created(committed);
            require(committed.equals(mvc.invoke(B08MvcSession.Call.create(owner(), key, REORDERED))), "contended key did not replay");
        }
        expectCount(7);
        require(trace.exclusive.get() == 1 && trace.claims.get() == 3 && trace.lookups.get() == 2,
                "lock, claim and fresh read counts differ from the controlled ordering");
        System.out.println("PASS real same-key contention with 3 claims, 1 exclusive guard, 2 fresh guarded receipt reads and one effect");
    }

    private void pastGeneration(B08MvcSession mvc) throws Exception {
        var past = new WriterEpoch(UUID.randomUUID(), B08MvcSession.GENERATION - 1);
        var access = new CurrentAccess(ignored -> { }, past, List.of(B08MvcSession.POLICY));
        var issuer = new OperationKeyIssuer(access, () -> NOW, params.secrets(), OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        var storedKey = issuer.issue(B08MvcSession.OWNER);
        var absentKey = issuer.issue(B08MvcSession.OWNER);
        var claims = issuer.verify(storedKey.key(), B08MvcSession.OWNER, access.requireCreate(B08MvcSession.OWNER, B08MvcSession.VISIBLE));
        String unrelatedRef = workflowObserver.queryForObject("""
                SELECT ref FROM requests.request WHERE organization = ? AND request_type = 'HR_TICKET' ORDER BY ref LIMIT 1
                """, String.class, B08MvcSession.ORG.value());
        var oldReceipt = new OperationReceipt(claims.receiptKey(),
                new IntentDigest(params.secrets()).calculate(new IntentDigest.Create(B08MvcSession.VISIBLE, NAME, BODY)),
                past.generation(), storedKey.expiresAt(), Action.CREATE, new RequestRef(unrelatedRef), 1,
                RequestState.CREATED, Optional.empty(), 201);
        var oldStore = new JdbcReceiptStore(workflowRoot);
        new JdbcTransactionRunner(workflowRoot).run(() -> {
            oldStore.insert(oldReceipt);
            return null;
        });
        trace.clear();
        var absent = mvc.invoke(B08MvcSession.Call.create(owner(), absentKey.key(), CREATE));
        var present = mvc.invoke(B08MvcSession.Call.create(owner(), storedKey.key(), CREATE));
        problem(absent, 409, "retry_fenced");
        problem(present, 409, "retry_fenced");
        require(maskCorrelation(absent).equals(maskCorrelation(present)) && absent.contentType().equals(present.contentType())
                && absent.cache().equals(present.cache()), "past-generation presence changed the public result");
        require(trace.claims.get() == 0 && trace.lookups.get() == 2 && trace.exclusive.get() == 0,
                "past keys must use guarded lookup only");
        require(count("request") == 7 && count("operation_receipt") == 8, "past generation wrote a new effect");
        System.out.println("PASS past-generation absent and unproved present receipt both fenced with 0 claims and 2 guarded reads");
    }

    private void restartedWriter(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        workflowCluster.restart().run();
        trace.clear();
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 503, "authority_unavailable");
        problem(mvc.invoke(B08MvcSession.Call.issue(owner())), 503, "authority_unavailable");
        require(trace.claims.get() == 0 && trace.lookups.get() == 0, "restarted writer bypassed live lineage certificate");
        for (String table : LOCAL) {
            long expected = table.equals("operation_receipt") ? 10 : 9;
            require(count(table) == expected, "restarted writer changed " + table);
        }
        expectNoTentativeClaims();
        require(reservations() == 11 && preparations() == 10, "final reservation/preparation counts disagree with the cuts");
        System.out.println("PASS uncertified PostgreSQL restart closes before lookup; 9 effects, 11 reservations, 10 preparations, 0 pending claims");
    }

    private void unprovedCommitResolution(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        long previousPreparations = preparations();
        trace.clear();
        trace.inject.set(Fault.LOST_COMMIT_REPLY);
        trace.afterCommit.set(() -> proofAdmin.execute(
                "REVOKE SELECT ON request_authority.workflow_certificate FROM b04_proof"));
        try {
            problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 503, "authority_unavailable");
            require(trace.claims.get() == 1 && trace.lookups.get() == 0 && count("request") == 8,
                    "unknown commit on unproved lineage accessed a receipt or changed more than one effect");
        } finally {
            proofAdmin.execute("GRANT SELECT ON request_authority.workflow_certificate TO b04_proof");
        }
        created(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)));
        require(count("request") == 8 && preparations() == previousPreparations + 1, "proof recheck released more than one effect");
        System.out.println("PASS unknown commit fenced before lookup when certificate is lost and recovered by same-key replay");
    }

    private void expiryDuringPreparation(B08MvcSession mvc) throws Exception {
        String key = issue(mvc, owner());
        long previousPreparations = preparations();
        trace.beforePreparation.set(() -> clock.set(NOW.plus(OperationKeyIssuer.VALIDITY)));
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 409, "key_expired");
        require(trace.beforePreparation.get() == null && count("request") == 9
                && preparations() == previousPreparations + 1, "prepared expiry must keep effect/preparation and withhold bytes");
        trace.clear();
        problem(mvc.invoke(B08MvcSession.Call.create(owner(), key, CREATE)), 409, "key_expired");
        require(trace.claims.get() == 0 && trace.lookups.get() == 0, "subsequent expiry must stop before receipt lookup");
        System.out.println("PASS release-time expiry supersedes a prepared result, with no ref, and retry stops before lookup");
    }

    private boolean originAndAssignment(B08MvcSession.Reply reply) {
        return Long.valueOf(1).equals(workflowObserver.queryForObject("""
                SELECT count(*) FROM requests.request AS r
                JOIN requests.request_body AS b USING (organization, ref)
                JOIN requests.request_assignment AS a USING (organization, ref)
                JOIN requests.transition_fact AS f USING (organization, ref)
                WHERE organization = ? AND ref = ? AND r.name = ? AND b.body = ? AND r.originating_person_id = ?
                    AND a.assignee_person_id = ? AND a.assignment_version = 1 AND f.sequence = 1
                    AND f.actor_ref = ? AND f.action = 'CREATE' AND f.revision = 1
                """, Long.class, B08MvcSession.ORG.value(), reply.json().get("ref").asString(), NAME, BODY,
                B08MvcSession.OWNER.person().orElseThrow().value(), B08MvcSession.OWNER.person().orElseThrow().value(), B08MvcSession.OWNER.principal()));
    }

    private void expectGuardNowait(boolean available) throws Exception {
        try (var connection = workflowCluster.migration().open()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("""
                    SELECT policy_version FROM requests.policy_guard WHERE organization = ? FOR UPDATE NOWAIT
                    """)) {
                lock.setString(1, B08MvcSession.ORG.value());
                try (var row = lock.executeQuery()) {
                    require(available && row.next(), "shared guard was not held through preparation");
                }
            } catch (SQLException failure) {
                if (available || !"55P03".equals(failure.getSQLState())) {
                    throw failure;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String maskCorrelation(B08MvcSession.Reply reply) {
        return reply.body().replace(reply.json().get("correlationId").asString(), "<correlation>");
    }

    private String issue(B08MvcSession mvc, String bearer) throws Exception {
        var reply = mvc.invoke(B08MvcSession.Call.issue(bearer));
        require(reply.status() == 201 && reply.cache().equals("no-store") && reply.location().isEmpty()
                && reply.json().size() == 2 && reply.json().get("expiresAt").asString()
                .equals(clock.get().plus(OperationKeyIssuer.VALIDITY).toString()), "key issue wire outcome differs");
        return reply.json().get("key").asString();
    }

    private static void created(B08MvcSession.Reply reply) {
        require(reply.status() == 201 && reply.cache().equals("no-store")
                && reply.contentType().startsWith("application/json"), "expected HTTP 201 UTF-8 JSON no-store");
        var body = reply.json();
        String ref = body.get("ref").asString();
        require(body.size() == 4 && ref.matches("[A-Za-z0-9_-]{32}") && reply.location().equals("/requests/" + ref)
                && body.get("resultRevision").isString() && body.get("resultRevision").asString().equals("1")
                && body.get("action").asString().equals("create") && body.get("state").asString().equals("CREATED"),
                "unexpected Create result fields");
    }

    private static void problem(B08MvcSession.Reply reply, int status, String code) {
        require(reply.status() == status && reply.cache().equals("no-store") && reply.location().isEmpty()
                && reply.contentType().startsWith("application/problem+json"),
                "expected sanitized HTTP " + status + " " + code + ", got " + reply.status());
        var json = reply.json();
        require(json.get("type").asString().equals("urn:requests:problem:" + code)
                && json.get("correlationId") != null && json.get("ref") == null,
                "unexpected problem code or protected reference for " + code);
    }

    private String owner() {
        return params.bearers().getFirst();
    }

    private void expectCount(long expected) {
        for (String table : LOCAL) {
            require(count(table) == expected, "unexpected local row count in " + table + " for effect count " + expected);
        }
        expectNoTentativeClaims();
    }

    private void expectNoTentativeClaims() {
        require(Long.valueOf(0).equals(workflowObserver.queryForObject("""
                SELECT count(*) FROM requests.operation_receipt WHERE NOT finalized
                """, Long.class)), "orphan tentative receipt is durable");
    }

    private long count(String table) {
        return workflowObserver.queryForObject("SELECT count(*) FROM requests." + table + " WHERE organization = ?",
                Long.class, B08MvcSession.ORG.value());
    }

    private long reservations() {
        return proofObserver.queryForObject("SELECT count(*) FROM request_authority.creation_reservation", Long.class);
    }

    private long preparations() {
        return proofObserver.queryForObject("SELECT count(*) FROM request_authority.release_prepared", Long.class);
    }

    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String error) {
        if (!condition) {
            throw new AssertionError(error);
        }
    }

    private static void report(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String detail = switch (cause) {
                case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                case AssertionError assertion -> " " + assertion.getMessage();
                default -> "";
            };
            System.err.println("B08 failed: " + cause.getClass().getName() + detail);
            Arrays.stream(cause.getStackTrace()).filter(frame -> frame.getClassName().startsWith("B08")
                    || frame.getClassName().startsWith("QualifyPostgresql")).forEach(frame ->
                    System.err.println("  at " + frame));
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            report(suppressed);
        }
    }

    private enum Fault { NONE, FAIL_FINALIZE, LOST_COMMIT_REPLY, UNKNOWN_ABORT }

    @FunctionalInterface
    private interface Cut {
        void atBoundary() throws Exception;
    }

    private static final class Trace {
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicInteger lookups = new AtomicInteger();
        private final AtomicInteger exclusive = new AtomicInteger();
        private final AtomicReference<Fault> inject = new AtomicReference<>(Fault.NONE);
        private final AtomicReference<Cut> beforeExclusive = new AtomicReference<>();
        private final AtomicReference<Cut> beforePreparation = new AtomicReference<>();
        private final AtomicReference<Cut> afterCommit = new AtomicReference<>();

        private void clear() {
            claims.set(0);
            lookups.set(0);
            exclusive.set(0);
        }

        private Connection watch(Connection raw, boolean proof) {
            boolean[] finalized = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("prepareStatement") && arguments[0] instanceof String sql) {
                    if (proof) {
                        if (sql.contains("INSERT INTO request_authority.release_prepared")) {
                            enter(beforePreparation);
                        }
                    } else if (sql.contains("INSERT INTO requests.operation_receipt") && sql.contains("DO NOTHING")) {
                        claims.incrementAndGet();
                    } else if (sql.contains("SELECT intent_digest_version")) {
                        lookups.incrementAndGet();
                    } else if (sql.contains("FROM requests.policy_guard") && sql.contains("FOR UPDATE")) {
                        exclusive.incrementAndGet();
                        enter(beforeExclusive);
                    } else if (sql.contains("UPDATE requests.operation_receipt")) {
                        if (inject.compareAndSet(Fault.FAIL_FINALIZE, Fault.NONE)) {
                            throw new SQLException("Synthetic precommit finalization failure", "XX000");
                        }
                        finalized[0] = true;
                    }
                }
                if (!proof && finalized[0] && name.equals("commit")) {
                    Fault fault = inject.getAndSet(Fault.NONE);
                    if (fault == Fault.LOST_COMMIT_REPLY || fault == Fault.UNKNOWN_ABORT) {
                        if (fault == Fault.LOST_COMMIT_REPLY) {
                            raw.commit();
                            enter(afterCommit);
                        } else {
                            raw.rollback();
                        }
                        throw new SQLException("Synthetic loss of commit confirmation", "08006");
                    }
                }
                try {
                    return method.invoke(raw, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        }

        private static void enter(AtomicReference<Cut> point) throws Exception {
            Cut cut = point.getAndSet(null);
            if (cut != null) {
                cut.atBoundary();
            }
        }
    }
}
