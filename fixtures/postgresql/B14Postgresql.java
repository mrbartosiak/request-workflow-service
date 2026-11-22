import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.authority.JdbcReleaseEvidence.Input;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeChange;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.PolicyGuard;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/** Actual B14 MVC commands on independently certified and observed PostgreSQL lineages. */
public final class B14Postgresql {
    private static final Instant NOW = Instant.parse("2026-11-22T21:00:00.123456789Z");
    private static final String NAME = " Case 名 ";
    private static final String BODY = "  e\u0301 line\n\té 東京  ";
    private static final String CHANGED = " revised body α ";
    private static final JsonMapper JSON = new JsonMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String WITNESS = "requests.scope_authority_witness";
    private static final String PREPARATION = "request_authority.release_result_prepared";
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor APPROVER = B10Postgresql.ASSIGNEE;

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final AtomicInteger receiptReads = new AtomicInteger();
    private final AtomicInteger claims = new AtomicInteger();
    private final String ownerBearer = bearer();
    private final String aliasBearer = bearer();
    private final String approverBearer = bearer();
    private final OperationSecrets secrets = new OperationSecrets(1, Map.of(1, secret()), 1, Map.of(1, secret()));
    private final B10Postgresql baseline;
    private final DataSource ownerSource;
    private final JdbcTemplate workflow;
    private final JdbcTemplate authority;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;
    private final SyntheticEffectiveAccessConfiguration.Components profile;
    private final JdbcTransactionRunner command;
    private final JdbcWorkflowRecords records;

    private B14Postgresql(QualifyPostgresql.Connections workflowConnections, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        var policy = B10Postgresql.policy().withAssigneeOnlyOnAccept(Set.of(B10Postgresql.TYPE));
        baseline = new B10Postgresql(workflowConnections, proof, projector, resolver, createProof, sources,
                this::observeReceiptReads, policy);
        ownerSource = B08MvcSession.dataSource(workflowConnections.migration());
        workflow = new JdbcTemplate(ownerSource);
        var proofOwner = B08MvcSession.dataSource(proof.migration());
        authority = new JdbcTemplate(proofOwner);
        var migrations = new JdbcWorkflowMigrations(ownerSource);
        migrations.migrateActionContext();
        migrations.migrateRequestActions();
        migrations.migrateRequestActions();
        workflow.execute("""
                GRANT UPDATE (state, revision, last_sequence) ON requests.request TO b04_workflow;
                GRANT UPDATE (body) ON requests.request_body TO b04_workflow;
                """);
        require(Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT has_column_privilege('b04_workflow','requests.request','revision','UPDATE')
                    AND NOT has_column_privilege('b04_workflow','requests.request','name','UPDATE')
                    AND NOT has_column_privilege('b04_workflow','requests.request','originating_person_id','UPDATE')
                """, Boolean.class)), "command role can update immutable request fields");
        var provisioning = new JdbcAuthorityProvisioning(proofOwner);
        provisioning.migrateReleaseEvidence();
        provisioning.authorizeReleaseEvidence("b12_prepare", OWNER.organization(), Input.PREPARATION);
        provisioning.authorizeReleaseEvidence("b12_handoff", OWNER.organization(), Input.HANDOFF);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b12_prepare, b12_handoff;
                GRANT EXECUTE ON FUNCTION request_authority.prepare_release(uuid, text, bytea, text, bigint, bigint,
                    bigint, uuid, bigint, text) TO b12_prepare;
                GRANT EXECUTE ON FUNCTION request_authority.record_release_evidence(uuid, text, bytea, text)
                    TO b12_handoff;
                """);
        System.out.println("PASS V008 least-privilege mutable columns and independent release evidence");

        var base = baseline.settings();
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", ownerBearer, OWNER),
                new SyntheticActorResolver.Binding("alias", aliasBearer, OWNER),
                new SyntheticActorResolver.Binding("assignee", approverBearer, APPROVER)));
        var ports = new SyntheticCreateConfiguration.GuardedReplay(OriginalReceiptProof.unavailable(),
                JdbcReleaseEvidence.preparation(B08MvcSession.dataSource(logins.preparation())),
                JdbcReleaseEvidence.handoffs(B08MvcSession.dataSource(logins.handoff())));
        var create = new SyntheticCreateConfiguration.Settings(base.create().command(), base.create().proof(),
                policy, base.create().writer(), actors, clock::get, secrets, Optional.of(ports));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(create, base.preparation(), base.originalObserver(),
                base.projection(), base.resolver(), base.operators(),
                Optional.of(new ObservationCodec.Keys(7, Map.of(7, secret()))));
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
        command = JdbcTransactionRunner.forCreate(create.command());
        records = new JdbcWorkflowRecords(create.command());
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.requestActions(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B14Postgresql(workflow, proof, projector, resolver, createProof, logins, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B14 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        try (var mvc = new B08MvcSession(settings)) {
            var first = create(mvc);
            var rollback = create(mvc);
            var keyRace = create(mvc);
            var rowRace = create(mvc);
            var originalEditKey = issue(mvc, ownerBearer);
            var originalVerifyKey = issue(mvc, ownerBearer);
            enable(OWNER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_EDIT, Grant.EXECUTE_EDIT,
                    Grant.RESULT_READ_EDIT, Grant.RESULT_READ_VERIFY, Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT);
            enable(APPROVER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT,
                    Grant.RESULT_READ_ACCEPT, Grant.FIELD_READ_NAME, Grant.CONTEXT_PUBLISH, Grant.EXECUTE_PUBLISH);
            var initial = counts();
            var beforeAccess = receiptReads.get();
            var beforeClaims = claims.get();
            problem(change(mvc, first.ref(), "verify", ownerBearer, originalVerifyKey, "malformed", null),
                    403, "action_denied");
            enable(OWNER, Grant.CONTEXT_VERIFY);
            problem(change(mvc, first.ref(), "verify", ownerBearer, originalVerifyKey, "malformed", null),
                    403, "action_denied");
            enable(OWNER, Grant.EXECUTE_VERIFY);
            var bodyRevocation = profile.updates().stageRevocation(B10Postgresql.OPERATOR,
                    grant(OWNER, Grant.FIELD_READ_BODY));
            baseline.confirm(bodyRevocation);
            problem(change(mvc, first.ref(), "verify", ownerBearer, originalVerifyKey, "malformed", null),
                    403, "action_denied");
            enable(OWNER, Grant.FIELD_READ_BODY);
            problem(change(mvc, new RequestRef("synthetic-foreign-request"), "verify", ownerBearer,
                    originalVerifyKey, "malformed", null), 404, "not_found");
            problem(change(mvc, first.ref(), "verify", ownerBearer, originalVerifyKey, "1", null),
                    400, "invalid_input");
            require(beforeAccess == receiptReads.get() && beforeClaims == claims.get() && initial.businessEquals(counts()),
                    "denied, concealed or invalid use reached receipt lookup or created an effect");
            System.out.println("PASS missing typed Context, Execute and material grants precede receipt and observation");

            var editObservation = observation(mvc, first.ref(), "edit", ownerBearer);
            var verifyObservation = observation(mvc, first.ref(), "verify", ownerBearer);
            problem(change(mvc, first.ref(), "verify", ownerBearer, originalVerifyKey, editObservation, null),
                    409, "stale_observation");
            workflow.update("UPDATE requests.request_body SET body = ? WHERE organization = ? AND ref = ?", CHANGED,
                    OWNER.organization().value(), first.ref().value());
            problem(change(mvc, first.ref(), "verify", ownerBearer, originalVerifyKey, verifyObservation, null),
                    409, "stale_observation");
            require(snapshot(first.ref()).equals(new Snapshot(NAME, CHANGED, "CREATED", 1, 1, 1)),
                    "same-revision changed decision fact or stale token altered workflow counters");
            require(initial.businessEquals(counts()), "stale commands left a tentative receipt or effect");
            System.out.println("PASS wrong action and same-revision changed material value roll back the tentative key");

            exactEditsAndVerify(mvc, first, originalEditKey, originalVerifyKey);
            rollbackAfterState(mvc, rollback.ref());
            nativeWaits(mvc, keyRace.ref(), rowRace.ref());
            acceptAndConfirm(mvc, first.ref());
        }
    }

    private void exactEditsAndVerify(B08MvcSession mvc, Created first, String editKey, String verifyKey) throws Exception {
        var counts = counts();
        var observation = observation(mvc, first.ref(), "edit", ownerBearer);
        var priorVerify = observation(mvc, first.ref(), "verify", ownerBearer);
        var edited = change(mvc, first.ref(), "edit", ownerBearer, editKey, observation, CHANGED);
        result(edited, first.ref(), "edit", "CREATED", 2);
        problem(change(mvc, first.ref(), "verify", ownerBearer, verifyKey, priorVerify, null),
                409, "stale_observation");
        var equalAgain = change(mvc, first.ref(), "edit", ownerBearer, issue(mvc, ownerBearer),
                observation(mvc, first.ref(), "edit", ownerBearer), CHANGED);
        result(equalAgain, first.ref(), "edit", "CREATED", 3);
        require(snapshot(first.ref()).equals(new Snapshot(NAME, CHANGED, "CREATED", 3, 1, 1)),
                "equal Edit did not advance exactly once per distinct key without history");
        require(counts.witnesses() == counts().witnesses() && counts.receipts() + 2 == counts().receipts(),
                "ordinary Edit created a scope witness or extra receipt");
        problem(change(mvc, first.ref(), "edit", ownerBearer, first.key(), observation(mvc, first.ref(), "edit", ownerBearer),
                CHANGED), 409, "key_conflict");
        problem(mvc.invoke(B08MvcSession.Call.create(ownerBearer, editKey, createBody())), 409, "key_conflict");
        problem(change(mvc, first.ref(), "verify", ownerBearer, editKey,
                observation(mvc, first.ref(), "verify", ownerBearer), null), 409, "key_conflict");
        problem(change(mvc, first.ref(), "edit", ownerBearer, editKey, observation, CHANGED + " "),
                409, "key_conflict");
        var verified = change(mvc, first.ref(), "verify", ownerBearer, verifyKey,
                observation(mvc, first.ref(), "verify", ownerBearer), null);
        result(verified, first.ref(), "verify", "VERIFIED", 4);
        require(snapshot(first.ref()).equals(new Snapshot(NAME, CHANGED, "VERIFIED", 4, 2, 2)),
                "Verify history or immutable fields mismatch");
        clock.set(NOW.plus(Duration.ofMinutes(15)));
        require(edited.equals(change(mvc, first.ref(), "edit", aliasBearer, editKey, observation, CHANGED)),
                "authorized exact replay did not reproduce original state and response after observation expiry");
        var revoked = profile.updates().stageRevocation(B10Postgresql.OPERATOR, grant(OWNER, Grant.RESULT_READ_EDIT));
        baseline.confirm(revoked);
        problem(change(mvc, first.ref(), "edit", ownerBearer, editKey, observation, CHANGED), 404, "not_found");
        problem(mvc.invoke(B08MvcSession.Call.create(ownerBearer, editKey, createBody())), 404, "not_found");
        enable(OWNER, Grant.RESULT_READ_EDIT);
        require(edited.equals(change(mvc, first.ref(), "edit", ownerBearer, editKey, observation, CHANGED)),
                "restored current original grant did not restore exact replay");
        var onVerified = change(mvc, first.ref(), "edit", ownerBearer, issue(mvc, ownerBearer),
                observation(mvc, first.ref(), "edit", ownerBearer), CHANGED);
        result(onVerified, first.ref(), "edit", "VERIFIED", 5);
        require(snapshot(first.ref()).equals(new Snapshot(NAME, CHANGED, "VERIFIED", 5, 2, 2)),
                "Edit on VERIFIED altered state or sequence");
        System.out.println("PASS equal distinct Edits advance revision only, Verify appends one fact, shared key and replay check current original grants");
    }

    private void rollbackAfterState(B08MvcSession mvc, RequestRef target) throws Exception {
        var key = issue(mvc, ownerBearer);
        var observation = observation(mvc, target, "verify", ownerBearer);
        var before = snapshot(target);
        var counts = counts();
        workflow.execute("REVOKE INSERT ON requests.transition_fact FROM b04_workflow");
        try {
            problem(change(mvc, target, "verify", ownerBearer, key, observation, null), 500, "internal_error");
            require(before.equals(snapshot(target)) && counts.equals(counts()),
                    "failed fact insert committed state, body, fact, receipt or witness");
        } finally {
            workflow.execute("GRANT INSERT ON requests.transition_fact TO b04_workflow");
        }
        var verified = change(mvc, target, "verify", ownerBearer, key, observation, null);
        result(verified, target, "verify", "VERIFIED", 2);
        require(counts.receipts() + 1 == counts().receipts() && counts.facts() + 1 == counts().facts(),
                "same key after rollback produced wrong counts");
        System.out.println("PASS native failure after state UPDATE rolls back effect and receipt; exact key can execute once");
    }

    private void nativeWaits(B08MvcSession mvc, RequestRef keyTarget, RequestRef rowTarget) throws Exception {
        String signed = issue(mvc, ownerBearer);
        String observed = observation(mvc, keyTarget, "edit", ownerBearer);
        var digest = new IntentDigest(secrets).calculate(new IntentDigest.Edit(keyTarget, CHANGED, observed));
        var keys = new OperationKeyIssuer(profile.access(), clock::get, secrets, OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        var verified = keys.verify(signed, OWNER, command.run(() -> profile.access().requireDispatchScope(
                records.lockShared(OWNER.organization()).orElseThrow())));
        var ownerTransactions = new JdbcTransactionRunner(ownerSource);
        var ownerReceipts = new JdbcReceiptStore(ownerSource);
        var held = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var observer = ownerSource.getConnection();
             var blocker = ownerSource.getConnection()) {
            var claim = workers.submit(() -> {
                try {
                    ownerTransactions.run(() -> {
                        require(ownerReceipts.claim(verified.receiptKey(), digest, verified.generation(),
                                verified.expiresAt()), "native blocker failed to own key");
                        held.countDown();
                        await(resume);
                        throw new DeliberateRollback();
                    });
                } catch (DeliberateRollback rolledBack) {
                    // The waiting command can now claim the original key.
                }
            });
            try {
                await(held);
                var waiter = workers.submit(() -> change(mvc, keyTarget, "edit", ownerBearer, signed, observed, CHANGED));
                waiting(observer, "%INSERT INTO requests.operation_receipt%", "receipt arbitration");
                claimGuard(blocker, "FOR UPDATE NOWAIT");
                blocker.rollback();
                resume.countDown();
                result(waiter.get(4, TimeUnit.SECONDS), keyTarget, "edit", "CREATED", 2);
                claim.get(4, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
            }

            signedWait(mvc, workers, observer, blocker, rowTarget, "edit", "policy_guard", 2);
            signedWait(mvc, workers, observer, blocker, rowTarget, "verify", "request_body", 3);
            require(snapshot(rowTarget).equals(new Snapshot(NAME, CHANGED, "VERIFIED", 3, 2, 2)),
                    "changed Edit did not replace the protected body exactly");
        }
        System.out.println("PASS native lock order receipt before exclusive organization before protected material row");
    }

    private void signedWait(B08MvcSession mvc, java.util.concurrent.ExecutorService workers, Connection observer,
                            Connection blocker, RequestRef target, String action, String blockedTable, long revision)
            throws Exception {
        var signed = issue(mvc, ownerBearer);
        var observed = observation(mvc, target, action, ownerBearer);
        if (blockedTable.equals("policy_guard")) {
            claimGuard(blocker, "FOR SHARE");
        } else {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT 1 FROM requests.request_body WHERE ref = ? FOR UPDATE")) {
                statement.setString(1, target.value());
                statement.executeQuery().close();
            }
        }
        try {
            var waiter = workers.submit(() -> change(mvc, target, action, ownerBearer, signed, observed,
                    action.equals("edit") ? CHANGED : null));
            waiting(observer, "%FROM requests." + blockedTable + "%FOR UPDATE%", blockedTable);
            blocker.rollback();
            result(waiter.get(4, TimeUnit.SECONDS), target, action, action.equals("edit") ? "CREATED" : "VERIFIED", revision);
        } finally {
            blocker.rollback();
        }
    }

    private static void claimGuard(Connection connection, String lock) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM requests.policy_guard WHERE organization = ? " + lock)) {
            statement.setString(1, OWNER.organization().value());
            statement.executeQuery().close();
        }
    }

    private static void waiting(Connection observer, String pattern, String label) throws Exception {
        long started = System.nanoTime();
        try (var statement = observer.prepareStatement("""
                SELECT 1 FROM pg_stat_activity
                WHERE usename = 'b04_workflow' AND wait_event_type = 'Lock'
                    AND query LIKE ? AND cardinality(pg_blocking_pids(pid)) > 0
                """)) {
            statement.setString(1, pattern);
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        System.out.printf("PASS PostgreSQL %s wait observed after %d ms%n", label,
                                MILLISECONDS.convert(System.nanoTime() - started, TimeUnit.NANOSECONDS));
                        return;
                    }
                }
                Thread.sleep(4);
            } while (System.nanoTime() - started < MILLISECONDS.toNanos(750));
        }
        throw new AssertionError("Expected native lock wait was absent for " + label);
    }

    private void acceptAndConfirm(B08MvcSession mvc, RequestRef target) throws Exception {
        var ownerKey = issue(mvc, ownerBearer);
        var approverKey = issue(mvc, approverBearer);
        var earlier = counts();
        var receiptReadsBefore = receiptReads.get();
        var claimsBefore = claims.get();
        problem(change(mvc, target, "accept", aliasBearer, ownerKey, "malformed", null), 403, "action_denied");
        baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, target, APPROVER));
        problem(change(mvc, target, "accept", ownerBearer, ownerKey, "malformed", null), 403, "action_denied");
        baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, target, OWNER));
        problem(change(mvc, target, "accept", aliasBearer, ownerKey, "malformed", null), 403, "action_denied");
        require(receiptReadsBefore == receiptReads.get() && claimsBefore == claims.get() && earlier.businessEquals(counts()),
                "same-human Accept reached a receipt after reassigning the target");
        baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, target, APPROVER));
        require(OWNER.person().orElseThrow().value().equals(origin(target)), "assignment changed the originating person");
        var observation = observation(mvc, target, "accept", approverBearer);
        rollbackAfterScopeChange(mvc, target, approverKey, observation);
        var before = counts();
        var version = version();
        var reads = receiptReads.get();
        var claimsAtAdmission = claims.get();
        long prepared = count(authority, PREPARATION);
        require(dispatchScope(target) == RequestState.VERIFIED, "current dispatch scope check failed before Accept");

        problem(change(mvc, target, "accept", approverBearer, approverKey, observation, null), 503, "authority_unavailable");
        var after = counts();
        require(snapshot(target).equals(new Snapshot(NAME, CHANGED, "ACCEPTED", 6, 3, 3))
                        && after.receipts() == before.receipts() + 1 && after.facts() == before.facts() + 1
                        && after.witnesses() == before.witnesses() + 1 && after.requests() == before.requests()
                        && after.bodies() == before.bodies(),
                "withheld Accept did not commit exactly one scoped effect");
        var pending = workflow.queryForObject("""
                SELECT attempt_id, sequence FROM requests.scope_authority_witness WHERE after_version = ?
                """, (row, index) -> new PendingConfirmation(row.getObject(1, UUID.class), row.getLong(2), version + 1),
                version + 1);
        require(pending != null, "Accept scope witness missing");
        var expected = new EffectiveScopeChange(new PolicyGuard(OWNER.organization(), version), APPROVER,
                new EffectiveScopeChange.SetVisibility(target, Visibility.INITIAL,
                        new Visibility(1, true, Set.of(Field.values())))).delta();
        exactScopeCommit(target, pending, expected);
        problem(change(mvc, target, "accept", approverBearer, approverKey, observation, null), 503, "authority_unavailable");
        expectClosed(() -> dispatchScope(target));
        require(after.equals(counts()) && reads == receiptReads.get() && claimsAtAdmission + 1 == claims.get()
                        && prepared == count(authority, PREPARATION),
                "pending scope reached receipt lookup, protected release preparation or a repeated effect");
        System.out.println("PASS pending original scope confirmation fences replay, handoff and dispatch admission");

        baseline.confirm(pending);
        var accepted = change(mvc, target, "accept", approverBearer, approverKey, observation, null);
        result(accepted, target, "accept", "ACCEPTED", 6);
        require(accepted.equals(change(mvc, target, "accept", approverBearer, approverKey, observation, null)),
                "Accept replay changed its original committed bytes");
        require(after.equals(counts()) && prepared + 2 == count(authority, PREPARATION)
                        && count(authority, PREPARATION) == count(authority, "request_authority.release_handoff_proved"),
                "confirmation and replay altered effects or failed to establish handoff");
        require(Lifecycle.next(dispatchScope(target), Action.PUBLISH) == RequestState.PUBLISHED
                        && !observation(mvc, target, "publish", approverBearer).isEmpty(),
                "confirmed distinct-person Accept did not admit the successor state");
        problem(change(mvc, target, "accept", aliasBearer, ownerKey, "malformed", null), 404, "not_found");
        require(OWNER.person().orElseThrow().value().equals(origin(target)), "Accept changed the originating person");
        System.out.println("PASS reassigned same-human denial, distinct approver, audited original confirmation and exact replay");
    }

    private void rollbackAfterScopeChange(B08MvcSession mvc, RequestRef target, String key, String observation)
            throws Exception {
        var before = counts();
        var snapshot = snapshot(target);
        long version = version();
        var prepared = count(authority, PREPARATION);
        workflow.execute("REVOKE INSERT ON " + WITNESS + " FROM b04_workflow");
        try {
            problem(change(mvc, target, "accept", approverBearer, key, observation, null), 500, "internal_error");
            require(before.equals(counts()) && snapshot.equals(snapshot(target)) && version == version()
                            && prepared == count(authority, PREPARATION)
                            && count(workflow, "requests.request_visibility_fact") == 0,
                    "a rejected witness left state, visibility, fact, receipt, version or preparation");
        } finally {
            workflow.execute("GRANT INSERT ON " + WITNESS + " TO b04_workflow");
        }
        var attempt = authority.queryForObject("""
                SELECT attempt_id FROM request_authority.scope_journal_attempt WHERE state = 'PENDING'
                """, UUID.class);
        expectClosed(() -> dispatchScope(target));
        baseline.abort(attempt);
        require(dispatchScope(target) == RequestState.VERIFIED && before.equals(counts()),
                "audited abort failed to reopen the original state without an effect");
        System.out.println("PASS witness INSERT failure rolls back state, fact, visibility and receipt; original abort reopens");
    }

    private void exactScopeCommit(RequestRef ref, PendingConfirmation pending,
                                  com.company.requests.application.ScopeAuthorityChange.Delta expected) {
        var attempt = pending.attemptId();
        require(Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT r.xmin::text = t.xmin::text AND r.xmin::text = o.xmin::text
                    AND r.xmin::text = v.xmin::text AND r.xmin::text = w.xmin::text
                    AND w.workflow_transaction::text = w.xmin::text AND w.delta_digest = ?
                    AND t.actor_ref = ? AND v.assignee_only AND w.sequence = ?
                FROM requests.request r
                JOIN requests.transition_fact t USING (organization, ref)
                JOIN requests.request_visibility_fact v USING (organization, ref)
                JOIN requests.operation_receipt o ON o.organization = r.organization AND o.result_ref = r.ref
                JOIN requests.scope_authority_witness w ON w.organization = r.organization
                 AND w.after_version = v.applied_version
                WHERE r.ref = ? AND t.action = 'ACCEPT' AND o.result_action = 'ACCEPT' AND w.attempt_id = ?
                """, Boolean.class, expected.digest(), APPROVER.principal(), pending.sequence(), ref.value(), attempt)),
                "scope witness, transition, mask and receipt did not share the exact PostgreSQL transaction");
        require(Boolean.TRUE.equals(authority.queryForObject("""
                SELECT a.state = 'PENDING' AND a.sequence = ? AND encode(a.delta_digest, 'hex') = ? AND d.exact_delta = ?
                    AND a.after_version = ?
                FROM request_authority.scope_journal_attempt a
                JOIN request_authority.scope_journal_delta d USING (attempt_id)
                WHERE a.attempt_id = ?
                """, Boolean.class, pending.sequence(), expected.digest(), expected.bytes(),
                pending.effectiveVersion(), attempt)), "ordered independent delta disagrees with scope witness");
        System.out.printf("PASS exact PENDING delta sequence=%d sha256=%s and one PostgreSQL effect transaction%n",
                pending.sequence(), expected.digest());
    }

    private RequestState dispatchScope(RequestRef target) {
        return command.run(() -> {
            var guard = records.lockExclusive(OWNER.organization()).orElseThrow();
            profile.access().requireDispatchScope(guard);
            return records.find(OWNER.organization(), target).orElseThrow().state();
        });
    }

    private void enable(AuthenticatedActor actor, Grant... grants) {
        for (var permission : grants) {
            baseline.confirm(profile.updates().stageEnablement(B10Postgresql.OPERATOR, grant(actor, permission)));
        }
    }

    private static GrantKey grant(AuthenticatedActor actor, Grant permission) {
        return new GrantKey(actor, permission, Optional.of(B10Postgresql.TYPE));
    }

    private Connection observeReceiptReads(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (self, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        var statement = sql.stripLeading();
                        if (statement.startsWith("SELECT intent_digest_version")) {
                            receiptReads.incrementAndGet();
                        } else if (statement.startsWith("INSERT INTO requests.operation_receipt")) {
                            claims.incrementAndGet();
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }

    private Created create(B08MvcSession mvc) throws Exception {
        var key = issue(mvc, ownerBearer);
        var reply = mvc.invoke(B08MvcSession.Call.create(ownerBearer, key, createBody()));
        require(reply.status() == 201, "Create fixture failed with status " + reply.status());
        return new Created(new RequestRef(reply.json().get("ref").asString()), key);
    }

    private static String createBody() {
        return JSON.writeValueAsString(Map.of("type", B10Postgresql.TYPE.name(), "name", NAME, "body", BODY));
    }

    private static String issue(B08MvcSession mvc, String bearer) throws Exception {
        var response = mvc.invoke(B08MvcSession.Call.issue(bearer));
        require(response.status() == 201, "key issue failed with status " + response.status());
        return response.json().get("key").asString();
    }

    private static String observation(B08MvcSession mvc, RequestRef ref, String action, String bearer) throws Exception {
        var reply = mvc.invoke(MockMvcRequestBuilders.get("/requests/{ref}/action-context", ref.value())
                .queryParam("action", action), bearer);
        require(reply.status() == 200, "missing eligible context for " + action + ", status " + reply.status());
        return reply.json().get("expectedRevision").asString();
    }

    private static B08MvcSession.Reply change(B08MvcSession mvc, RequestRef ref, String action, String bearer,
                                              String key, String observation, String body) throws Exception {
        var request = action.equals("edit") ? MockMvcRequestBuilders.patch("/requests/{ref}/body", ref.value())
                : MockMvcRequestBuilders.post("/requests/{ref}/actions/{action}", ref.value(), action);
        var json = body == null ? Map.of("expectedRevision", observation)
                : Map.of("expectedRevision", observation, "body", body);
        return mvc.invoke(request.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(json)), bearer);
    }

    private static void problem(B08MvcSession.Reply reply, int status, String code) {
        require(reply.status() == status && reply.json().path("type").asString().equals("urn:requests:problem:" + code)
                        && reply.location().isEmpty() && reply.cache().equals("no-store"),
                "expected " + status + " " + code + " but got " + reply.status() + " "
                        + reply.json().path("type").asString());
    }

    private static void result(B08MvcSession.Reply reply, RequestRef ref, String action, String state, long revision) {
        require(reply.status() == 200 && reply.location().isEmpty() && reply.cache().equals("no-store")
                        && reply.contentType().equalsIgnoreCase("application/json;charset=UTF-8"),
                "expected 200 for " + action + " but got " + reply.status());
        var expected = JSON.valueToTree(Map.of("ref", ref.value(), "action", action, "state", state,
                "resultRevision", Long.toString(revision)));
        require(expected.equals(reply.json()), "protected command result differs from the original mutation");
    }

    private Snapshot snapshot(RequestRef ref) {
        return workflow.queryForObject("""
                SELECT r.name, b.body, r.state, r.revision, r.last_sequence,
                    (SELECT count(*) FROM requests.transition_fact WHERE ref = r.ref)
                FROM requests.request r JOIN requests.request_body b USING (organization, ref)
                WHERE r.organization = ? AND r.ref = ?
                """, (row, index) -> new Snapshot(row.getString(1), row.getString(2), row.getString(3),
                row.getLong(4), row.getLong(5), row.getLong(6)), OWNER.organization().value(), ref.value());
    }

    private String origin(RequestRef ref) {
        return workflow.queryForObject("SELECT originating_person_id FROM requests.request WHERE ref = ?",
                String.class, ref.value());
    }

    private long version() {
        return workflow.queryForObject("SELECT policy_version FROM requests.policy_guard WHERE organization = ?",
                Long.class, OWNER.organization().value());
    }

    private Counts counts() {
        return new Counts(count(workflow, "requests.request"), count(workflow, "requests.request_body"),
                count(workflow, "requests.transition_fact"), count(workflow, "requests.operation_receipt"),
                count(workflow, WITNESS), count(authority, "request_authority.creation_reservation"));
    }

    private static long count(JdbcTemplate connection, String table) {
        return connection.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static void expectClosed(Runnable work) {
        try {
            work.run();
        } catch (AuthorityUnavailableException unavailable) {
            return;
        }
        throw new AssertionError("Dispatch scope opened without a confirmed original witness");
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(4, TimeUnit.SECONDS), "fixture latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture latch interrupted");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String bearer() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret());
    }

    private static byte[] secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private record Snapshot(String name, String body, String state, long revision, long sequence, long facts) { }

    private record Counts(long requests, long bodies, long facts, long receipts, long witnesses, long reservations) {
        boolean businessEquals(Counts other) {
            return requests == other.requests && bodies == other.bodies && facts == other.facts
                    && receipts == other.receipts && reservations == other.reservations;
        }
    }

    private record Created(RequestRef ref, String key) { }

    private static final class DeliberateRollback extends RuntimeException { }
}
