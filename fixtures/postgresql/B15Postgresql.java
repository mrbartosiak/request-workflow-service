import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.authority.JdbcReleaseEvidence.Input;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.ActionContextService;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeChange;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.PolicyGuard;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Terminal routes and protected projections on the independently observed PostgreSQL fixture. */
public final class B15Postgresql {
    private static final Instant NOW = Instant.parse("2026-11-22T21:00:00.123456789Z");
    private static final String NAME = "  Nom 東京  ";
    private static final String BODY = "  Historical body e\u0301  ";
    private static final String REASON = " \tReason 名 e\u0301 🚦\n ";
    private static final String WITNESS = "requests.scope_authority_witness";
    private static final String PREPARATION = "request_authority.release_result_prepared";
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor APPROVER = B10Postgresql.ASSIGNEE;
    private static final JsonMapper JSON = new JsonMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final AtomicReference<Pause> pause = new AtomicReference<>();
    private final AtomicInteger claims = new AtomicInteger();
    private final AtomicInteger receiptReads = new AtomicInteger();
    private final AtomicInteger materialReads = new AtomicInteger();
    private final String ownerBearer = bearer();
    private final String aliasBearer = bearer();
    private final String approverBearer = bearer();
    private final B10Postgresql baseline;
    private final DataSource ownerSource;
    private final JdbcTemplate workflow;
    private final JdbcTemplate authority;
    private final JdbcTransactionRunner transactions;
    private final JdbcWorkflowRecords records;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;
    private final SyntheticEffectiveAccessConfiguration.Components profile;

    private B15Postgresql(QualifyPostgresql.Connections workflowConnections, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        baseline = new B10Postgresql(workflowConnections, proof, projector, resolver, createProof, sources, this::observe);
        ownerSource = B08MvcSession.dataSource(workflowConnections.migration());
        workflow = new JdbcTemplate(ownerSource);
        var proofOwner = B08MvcSession.dataSource(proof.migration());
        authority = new JdbcTemplate(proofOwner);
        var migrations = new JdbcWorkflowMigrations(ownerSource);
        migrations.migrateRequestActions();
        workflow.execute("""
                GRANT UPDATE (state, revision, last_sequence) ON requests.request TO b04_workflow;
                GRANT UPDATE (body) ON requests.request_body TO b04_workflow;
                """);
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
        var base = baseline.settings();
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", ownerBearer, OWNER),
                new SyntheticActorResolver.Binding("alias", aliasBearer, OWNER),
                new SyntheticActorResolver.Binding("approver", approverBearer, APPROVER)));
        var replay = new SyntheticCreateConfiguration.GuardedReplay(OriginalReceiptProof.unavailable(),
                JdbcReleaseEvidence.preparation(B08MvcSession.dataSource(logins.preparation())),
                JdbcReleaseEvidence.handoffs(B08MvcSession.dataSource(logins.handoff())));
        var create = new SyntheticCreateConfiguration.Settings(base.create().command(), base.create().proof(),
                base.create().policy(), base.create().writer(), actors, clock::get,
                new OperationSecrets(1, Map.of(1, secret()), 1, Map.of(1, secret())), Optional.of(replay));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(create, base.preparation(), base.originalObserver(),
                base.projection(), base.resolver(), base.operators(),
                Optional.of(new ObservationCodec.Keys(7, Map.of(7, secret()))));
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
        transactions = JdbcTransactionRunner.forCreate(create.command());
        records = new JdbcWorkflowRecords(create.command());
    }

    public static void main(String[] args) {
        boolean red = Boolean.getBoolean("requests.b15.baselineRed");
        try {
            QualifyPostgresql.terminalRequestActions(args, (workflow, proof, projector, resolver, createProof, logins,
                    restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B15Postgresql(workflow, proof, projector, resolver, createProof, logins, sources)
                            .exercise(red);
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B15 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise(boolean red) throws Exception {
        try (var mvc = new B08MvcSession(settings)) {
            enable(OWNER, Grant.ROW_READ, Grant.FIELD_READ_NAME, Grant.FIELD_READ_BODY, Grant.CONTEXT_EDIT,
                    Grant.EXECUTE_EDIT, Grant.RESULT_READ_EDIT, Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY,
                    Grant.RESULT_READ_VERIFY, Grant.CONTEXT_DELETE, Grant.EXECUTE_DELETE,
                    Grant.CONTEXT_REJECT, Grant.EXECUTE_REJECT);
            if (red) {
                var deletion = terminal(mvc, create(mvc).ref(), "delete", ownerBearer);
                var verified = create(mvc).ref();
                verify(mvc, verified);
                var rejection = terminal(mvc, verified, "reject", ownerBearer);
                long facts = count(workflow, "requests.transition_fact");
                long receipts = count(workflow, "requests.operation_receipt");
                var deleted = submit(mvc, deletion, ownerBearer);
                var rejected = submit(mvc, rejection, ownerBearer);
                require(facts == count(workflow, "requests.transition_fact")
                        && receipts == count(workflow, "requests.operation_receipt"), "baseline changed terminal effects");
                require(deleted.status() == 200 && rejected.status() == 200,
                        "B14 baseline red with valid observations, keys and reasons: delete=" + deleted.status()
                                + " reject=" + rejected.status());
            }

            upgradeTerminalSchema();
            enable(OWNER, Grant.RESULT_READ_DELETE, Grant.RESULT_READ_REJECT);
            enable(APPROVER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME, Grant.CONTEXT_ACCEPT,
                    Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_REJECT,
                    Grant.EXECUTE_REJECT, Grant.RESULT_READ_REJECT);
            var delete = create(mvc);
            var verified = create(mvc).ref();
            var accepted = create(mvc).ref();
            var late = create(mvc).ref();
            var rollback = create(mvc).ref();
            var deleteFirst = create(mvc).ref();
            verify(mvc, verified);
            verify(mvc, accepted);
            baseline.confirm(profile.updates().stageAssignment(B10Postgresql.OPERATOR, accepted, APPROVER));
            result(change(mvc, accepted, "accept", approverBearer, issue(mvc, approverBearer),
                    observation(mvc, accepted, "accept", approverBearer), null), accepted, "accept", "ACCEPTED", 3);
            reasonValidation(mvc, delete.ref(), verified);
            reject(mvc, verified, ownerBearer, 3, "CREATED>VERIFIED>REJECTED");
            reject(mvc, accepted, approverBearer, 4, "CREATED>VERIFIED>ACCEPTED>REJECTED");
            lateRevocation(mvc, late);
            rollback(mvc, rollback);
            releaseWins(mvc, delete);
            deleteWins(mvc, deleteFirst);
        }
    }

    private void upgradeTerminalSchema() {
        var schema = new JdbcWorkflowMigrations(ownerSource);
        long overrides = count(workflow, "requests.scope_grant_override");
        schema.migrateTerminalRequestActions();
        schema.migrateTerminalRequestActions();
        workflow.execute("GRANT INSERT ON requests.transition_reason TO b04_workflow");
        require(overrides > 0 && overrides == count(workflow, "requests.scope_grant_override")
                        && Boolean.TRUE.equals(workflow.queryForObject("""
                        SELECT has_table_privilege('b04_workflow', 'requests.transition_reason', 'INSERT')
                            AND NOT has_table_privilege('b04_workflow', 'requests.transition_reason', 'SELECT, UPDATE, DELETE')
                        """, Boolean.class)), "V009 changed V008 grants or exposed retained reasons to the command role");
        System.out.println("PASS V009 preserves populated V008 grants and gives the runtime only reason INSERT access");
    }

    private void reasonValidation(B08MvcSession mvc, RequestRef created, RequestRef verified) throws Exception {
        var before = counts();
        var observedClaims = claims.get();
        var observedReads = receiptReads.get();
        for (var input : List.of(terminal(mvc, created, "delete", ownerBearer),
                terminal(mvc, verified, "reject", ownerBearer))) {
            String observation = JSON.writeValueAsString(input.observation());
            String prefix = "{\"expectedRevision\":" + observation;
            for (String invalid : List.of("", ",\"reason\":null", ",\"reason\":false", ",\"reason\":\"\"",
                    ",\"reason\":\" \u00a0\u2002\"", ",\"reason\":\"\\u0000\"",
                    ",\"reason\":\"\\uD800\"", ",\"reason\":\"a\",\"reason\":\"b\"",
                    ",\"reason\":" + JSON.writeValueAsString("界".repeat(683)))) {
                problem(raw(mvc, input, ownerBearer, prefix + invalid + "}"), 400, "invalid_input");
            }
        }
        require(before.equals(counts()) && observedClaims == claims.get() && observedReads == receiptReads.get(),
                "invalid reason reached an effect or receipt");
        problem(context(mvc, created, "reject", ownerBearer), 409, "illegal_state");
        problem(context(mvc, verified, "delete", ownerBearer), 409, "illegal_state");
        var wrongAction = observation(mvc, created, "edit", ownerBearer);
        problem(change(mvc, created, "delete", ownerBearer, issue(mvc, ownerBearer), wrongAction, REASON),
                409, "stale_observation");
        require(before.equals(counts()), "state or wrong-action observation left an effect");
        System.out.println("PASS both actual routes require exact UTF-8 reason before receipts, and enforce context eligibility");
    }

    private void reject(B08MvcSession mvc, RequestRef ref, String bearer, long revision, String history) throws Exception {
        var input = terminal(mvc, ref, "reject", bearer);
        var reason = " " + "界".repeat(682) + " ";
        require(reason.getBytes(StandardCharsets.UTF_8).length == 2048, "reason fixture boundary");
        var before = counts();
        var result = change(mvc, ref, "reject", bearer, input.key(), input.observation(), reason);
        result(result, ref, "reject", "REJECTED", revision);
        exactTerminal(ref, "REJECTED", revision, revision, history, reason);
        require(counts().equals(before.terminal(false)), "Reject added an unexpected reason, receipt or witness count");
        require(result.equals(change(mvc, ref, "reject", bearer, input.key(), input.observation(), reason)),
                "Reject replay did not reproduce the original status and bytes");
        problem(change(mvc, ref, "reject", bearer, input.key(), input.observation(), reason.strip()),
                409, "key_conflict");
        problem(change(mvc, ref, "reject", bearer, issue(mvc, bearer), input.observation(), reason),
                409, "stale_observation");
        problem(context(mvc, ref, "reject", bearer), 409, "illegal_state");
        require(counts().equals(before.terminal(false)), "terminal Reject retry or conflict changed counts");
        System.out.println("PASS " + history + " has contiguous facts, one exact 2048-byte reason and one result");
    }

    private void lateRevocation(B08MvcSession mvc, RequestRef ref) throws Exception {
        var input = terminal(mvc, ref, "delete", ownerBearer);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var cut = arm("INSERT INTO requests.operation_receipt");
            var command = workers.submit(() -> submit(mvc, input, ownerBearer));
            try {
                await(cut.entered());
                revoke(OWNER, Grant.EXECUTE_DELETE);
                var before = counts();
                var priorReads = receiptReads.get();
                cut.resume().countDown();
                problem(command.get(4, SECONDS), 403, "action_denied");
                require(before.equals(counts()) && priorReads == receiptReads.get()
                                && versionOf(ref).equals("CREATED:1:1"),
                        "late revocation left a terminal effect, receipt read or scope change");
            } finally {
                cut.resume().countDown();
            }
        }
        enable(OWNER, Grant.EXECUTE_DELETE);
        System.out.println("PASS ordered revocation after submitted authorization wins before Delete effect and rolls back claim");
    }

    private void rollback(B08MvcSession mvc, RequestRef ref) throws Exception {
        var input = terminal(mvc, ref, "delete", ownerBearer);
        for (String table : List.of("requests.transition_reason", WITNESS)) {
            var before = counts();
            long version = scopeVersion();
            long prepared = count(authority, PREPARATION);
            workflow.execute("REVOKE INSERT ON " + table + " FROM b04_workflow");
            try {
                problem(submit(mvc, input, ownerBearer), 500, "internal_error");
            } finally {
                workflow.execute("GRANT INSERT ON " + table + " TO b04_workflow");
            }
            require(before.equals(counts()) && version == scopeVersion() && versionOf(ref).equals("CREATED:1:1")
                            && prepared == count(authority, PREPARATION), "failed terminal write leaked a local effect");
            var attempt = authority.queryForObject("""
                    SELECT attempt_id FROM request_authority.scope_journal_attempt WHERE state = 'PENDING'
                    """, UUID.class);
            problem(context(mvc, ref, "delete", ownerBearer), 503, "authority_unavailable");
            baseline.abort(attempt);
            require(context(mvc, ref, "delete", ownerBearer).status() == 200 && before.equals(counts()),
                    "audited original rollback did not recover the unchanged request");
            System.out.println("PASS failure inserting " + table + " rolls back all local writes and audited abort reopens");
        }
    }

    private void releaseWins(B08MvcSession mvc, Created request) throws Exception {
        var ref = request.ref();
        var editKey = issue(mvc, ownerBearer);
        var editObservation = observation(mvc, ref, "edit", ownerBearer);
        result(change(mvc, ref, "edit", ownerBearer, editKey, editObservation, BODY), ref, "edit", "CREATED", 2);
        var input = terminal(mvc, ref, "delete", ownerBearer);
        var before = counts();
        long version = scopeVersion();
        long prepared = count(authority, PREPARATION);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2); var observer = ownerSource.getConnection()) {
            var projection = workers.submit(() -> profile.actionContext().orElseThrow()
                    .release(OWNER, ref, Action.EDIT, reply -> {
                        require(reply instanceof ActionContextService.View view && BODY.equals(view.value()),
                                "release-first cut did not reach the protected projection");
                        entered.countDown();
                        await(resume);
                        var view = (ActionContextService.View) reply;
                        return JSON.writeValueAsBytes(Map.of("body", view.value(), "expectedRevision",
                                view.expectedRevision()));
                    }));
            try {
                await(entered);
                var command = workers.submit(() -> submit(mvc, input, ownerBearer));
                nativeWait(observer, "UPDATE");
                resume.countDown();
                var released = JSON.readTree(projection.get(4, SECONDS));
                require(BODY.equals(released.path("body").asString()) && released.has("expectedRevision"),
                        "release-first cut lost the earlier legitimate projection");
                problem(command.get(4, SECONDS), 503, "authority_unavailable");
            } finally {
                resume.countDown();
            }
        }
        var pending = verifyDelete(ref, before, version, 3);
        int reads = receiptReads.get();
        int priorClaims = claims.get();
        problem(submit(mvc, input, ownerBearer), 503, "authority_unavailable");
        problem(context(mvc, ref, "edit", ownerBearer), 503, "authority_unavailable");
        try {
            transactions.run(() -> profile.access().requireDispatchScope(
                    records.lockShared(OWNER.organization()).orElseThrow()));
            throw new AssertionError("pending Delete admitted dispatch");
        } catch (AuthorityUnavailableException closed) {
            require(reads == receiptReads.get() && priorClaims == claims.get() && count(authority, PREPARATION) == prepared,
                    "pending Delete reached receipt access, repeated effect or protected release preparation");
        }
        baseline.confirm(pending);

        problem(submit(mvc, input, ownerBearer), 404, "not_found");
        require(projection(ref).isEmpty(), "deleted metadata was disclosed without its independent grant");
        enable(OWNER, Grant.DELETED_METADATA);
        require(projection(ref).equals(Set.of(Field.REF, Field.STATE)), "deleted metadata contains protected fields");
        revoke(OWNER, Grant.FIELD_READ_NAME);
        var minimal = submit(mvc, input, ownerBearer);
        result(minimal, ref, "delete", "DELETED", 3);
        clock.set(NOW.plus(Duration.ofMinutes(15)));
        try (var restarted = new B08MvcSession(settings)) {
            require(minimal.equals(submit(restarted, input, aliasBearer)),
                    "Delete replay after MVC restart and observation expiry changed the original minimal result");
        } finally {
            clock.set(NOW);
        }
        problem(change(mvc, ref, "delete", ownerBearer, input.key(), input.observation(), REASON + " "),
                409, "key_conflict");
        problem(change(mvc, ref, "delete", ownerBearer, issue(mvc, ownerBearer), input.observation(), REASON),
                409, "illegal_state");
        problem(change(mvc, ref, "edit", ownerBearer, editKey, editObservation, BODY), 404, "not_found");
        problem(change(mvc, ref, "delete", ownerBearer, editKey, input.observation(), REASON), 404, "not_found");
        problem(context(mvc, ref, "delete", ownerBearer), 404, "not_found");
        require(priorClaims == claims.get(), "a deleted-target retry attempted to claim a new effect");
        problem(mvc.invoke(B08MvcSession.Call.create(ownerBearer, request.key(), createBody())), 404, "not_found");
        revoke(OWNER, Grant.CONTEXT_DELETE);
        problem(submit(mvc, input, ownerBearer), 404, "not_found");
        enable(OWNER, Grant.CONTEXT_DELETE);
        revoke(OWNER, Grant.RESULT_READ_DELETE);
        problem(submit(mvc, input, ownerBearer), 404, "not_found");
        require(projection(ref).equals(Set.of(Field.REF, Field.STATE)), "result revocation changed metadata access");
        long laterPrepared = count(authority, PREPARATION);
        require(laterPrepared == prepared + 2, "withheld Delete or pre-delete result left release preparation");
        var after = counts();
        require(after.requests() == before.requests() && after.facts() == before.facts() + 1
                        && after.reasons() == before.reasons() + 1 && after.receipts() == before.receipts() + 1
                        && after.masks() == before.masks() + 1, "post-confirmation paths altered terminal effects");
        enable(OWNER, Grant.RESULT_READ_DELETE, Grant.FIELD_READ_NAME);
        System.out.println("PASS release first wins natively; confirmed Delete suppresses all older results and permits exact minimal replay");
    }

    private void deleteWins(B08MvcSession mvc, RequestRef ref) throws Exception {
        var input = terminal(mvc, ref, "delete", ownerBearer);
        var before = counts();
        long version = scopeVersion();
        int priorMaterialReads = materialReads.get();
        try (var workers = Executors.newFixedThreadPool(2); var observer = ownerSource.getConnection()) {
            var cut = arm("INSERT INTO requests.scope_authority_witness");
            var delete = workers.submit(() -> submit(mvc, input, ownerBearer));
            try {
                await(cut.entered());
                var projection = workers.submit(() -> context(mvc, ref, "edit", ownerBearer));
                nativeWait(observer, "SHARE");
                cut.resume().countDown();
                problem(delete.get(4, SECONDS), 503, "authority_unavailable");
                problem(projection.get(4, SECONDS), 503, "authority_unavailable");
            } finally {
                cut.resume().countDown();
            }
        }
        baseline.confirm(verifyDelete(ref, before, version, 2));
        problem(context(mvc, ref, "edit", ownerBearer), 404, "not_found");
        require(materialReads.get() == priorMaterialReads && projection(ref).equals(Set.of(Field.REF, Field.STATE)),
                "winning Delete allowed a prior protected material read or token");
        result(submit(mvc, input, ownerBearer), ref, "delete", "DELETED", 2);
        System.out.println("PASS Delete first wins natively, withholding the waiting projection before proof and concealing afterward");
    }

    private PendingConfirmation verifyDelete(RequestRef ref, Counts before, long version, long revision) {
        exactTerminal(ref, "DELETED", revision, 2, "CREATED>DELETED", REASON);
        require(counts().equals(before.terminal(true)) && scopeVersion() == version + 1, "Delete cardinality mismatch");
        var pending = workflow.queryForObject("""
                SELECT attempt_id, sequence FROM requests.scope_authority_witness WHERE after_version = ?
                """, (row, index) -> new PendingConfirmation(row.getObject(1, UUID.class), row.getLong(2), version + 1),
                version + 1);
        var mask = Set.of(Field.REF, Field.ACTION, Field.RESULT_REVISION, Field.STATE);
        var expected = new EffectiveScopeChange(new PolicyGuard(OWNER.organization(), version), OWNER,
                new EffectiveScopeChange.SetVisibility(ref, Visibility.INITIAL, new Visibility(1, false, mask))).delta();
        require(Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT r.xmin = t.xmin AND r.xmin = p.xmin AND r.xmin = o.xmin AND r.xmin = v.xmin
                    AND r.xmin = w.xmin AND w.workflow_transaction::text = w.xmin::text AND w.delta_digest = ?
                    AND w.sequence = ? AND v.field_mask = 15
                FROM requests.request r
                JOIN requests.transition_fact t ON t.organization = r.organization AND t.ref = r.ref
                    AND t.sequence = r.last_sequence
                JOIN requests.transition_reason p ON p.organization = t.organization AND p.ref = t.ref
                    AND p.sequence = t.sequence
                JOIN requests.operation_receipt o ON o.organization = r.organization AND o.result_ref = r.ref
                    AND o.result_action = 'DELETE' AND o.result_revision = r.revision
                JOIN requests.request_visibility_fact v ON v.organization = r.organization AND v.ref = r.ref
                JOIN requests.scope_authority_witness w ON w.organization = v.organization
                    AND w.after_version = v.applied_version
                WHERE r.ref = ? AND w.attempt_id = ?
                """, Boolean.class, expected.digest(), pending.sequence(), ref.value(), pending.attemptId())),
                "request, terminal fact, reason, receipt, mask and witness XIDs or exact coordinates differ");
        require(Boolean.TRUE.equals(authority.queryForObject("""
                SELECT a.state = 'PENDING' AND a.sequence = ? AND encode(a.delta_digest, 'hex') = ?
                    AND a.after_version = ? AND d.exact_delta = ?
                FROM request_authority.scope_journal_attempt a
                JOIN request_authority.scope_journal_delta d USING (attempt_id)
                WHERE a.attempt_id = ?
                """, Boolean.class, pending.sequence(), expected.digest(), pending.effectiveVersion(), expected.bytes(),
                pending.attemptId())), "independent authority delta bytes or ordered position differ");
        System.out.printf("PASS Delete atomic tuple XIDs, exact pending delta sequence=%d sha256=%s%n",
                pending.sequence(), expected.digest());
        return pending;
    }

    private void exactTerminal(RequestRef ref, String state, long revision, long sequence, String history, String reason) {
        require(versionOf(ref).equals(state + ":" + revision + ":" + sequence), "wrong request state or counters");
        require(Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT string_agg(to_state, '>' ORDER BY sequence) = ?
                    AND count(*) = ? AND max(sequence) = ? AND count(*) FILTER (WHERE from_state IS NULL) = 1
                FROM requests.transition_fact WHERE ref = ?
                """, Boolean.class, history, sequence, sequence, ref.value())), "terminal history is incomplete");
        require(Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT convert_to(p.reason, 'UTF8') = ? AND p.retention_status = 'RETAINED' AND p.xmin = t.xmin
                    AND p.xmin = r.xmin AND p.xmin = o.xmin AND r.name = ? AND b.body = ?
                    AND b.retention_status = 'RETAINED' AND o.original_status = 200
                FROM requests.transition_reason p
                JOIN requests.transition_fact t USING (organization, ref, sequence)
                JOIN requests.request r USING (organization, ref)
                JOIN requests.request_body b USING (organization, ref)
                JOIN requests.operation_receipt o ON o.organization = p.organization AND o.result_ref = p.ref
                    AND o.result_revision = t.revision AND o.result_action = t.action
                WHERE p.ref = ? AND p.sequence = ? AND p.terminal_state = ?
                """, Boolean.class, reason.getBytes(StandardCharsets.UTF_8), NAME, BODY, ref.value(), sequence, state)),
                "terminal payload, receipt or transaction identity differs");
    }

    private Set<Field> projection(RequestRef ref) {
        return transactions.run(() -> profile.access().permittedFields(OWNER,
                records.lockShared(OWNER.organization()).orElseThrow(), ref));
    }

    private static void nativeWait(Connection observer, String mode) throws Exception {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(250);
        try (var statement = observer.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename = 'b04_workflow'
                    AND wait_event_type = 'Lock' AND query LIKE ? AND cardinality(pg_blocking_pids(pid)) > 0)
                """)) {
            statement.setString(1, "%FROM requests.policy_guard%FOR " + mode + "%");
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        System.out.println("PASS PostgreSQL reports a blocked " + mode + " on the organization guard");
                        return;
                    }
                }
                Thread.sleep(2);
            } while (System.nanoTime() < deadline);
        }
        throw new AssertionError("PostgreSQL did not report the expected " + mode + " guard wait");
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (self, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        var query = sql.stripLeading();
                        if (query.startsWith("INSERT INTO requests.operation_receipt")) {
                            claims.incrementAndGet();
                        } else if (query.startsWith("SELECT intent_digest_version")) {
                            receiptReads.incrementAndGet();
                        } else if (query.startsWith("SELECT body FROM requests.request_body")) {
                            materialReads.incrementAndGet();
                        }
                        var cut = pause.get();
                        if (cut != null && query.startsWith(cut.prefix()) && pause.compareAndSet(cut, null)) {
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

    private Pause arm(String prefix) {
        var cut = new Pause(prefix, new CountDownLatch(1), new CountDownLatch(1));
        require(pause.compareAndSet(null, cut), "another SQL cut remains armed");
        return cut;
    }

    private void enable(AuthenticatedActor actor, Grant... grants) {
        for (var permission : grants) {
            baseline.confirm(profile.updates().stageEnablement(B10Postgresql.OPERATOR, grant(actor, permission)));
        }
    }

    private void revoke(AuthenticatedActor actor, Grant permission) {
        baseline.confirm(profile.updates().stageRevocation(B10Postgresql.OPERATOR, grant(actor, permission)));
    }

    private static GrantKey grant(AuthenticatedActor actor, Grant permission) {
        return new GrantKey(actor, permission, Optional.of(B10Postgresql.TYPE));
    }

    private Created create(B08MvcSession mvc) throws Exception {
        var key = issue(mvc, ownerBearer);
        var response = mvc.invoke(B08MvcSession.Call.create(ownerBearer, key, createBody()));
        require(response.status() == 201, "Create setup failed with status " + response.status());
        return new Created(new RequestRef(response.json().get("ref").asString()), key);
    }

    private void verify(B08MvcSession mvc, RequestRef ref) throws Exception {
        result(change(mvc, ref, "verify", ownerBearer, issue(mvc, ownerBearer),
                observation(mvc, ref, "verify", ownerBearer), null), ref, "verify", "VERIFIED", 2);
    }

    private static String createBody() {
        return JSON.writeValueAsString(Map.of("type", B10Postgresql.TYPE.name(), "name", NAME, "body", BODY));
    }

    private static String issue(B08MvcSession mvc, String bearer) throws Exception {
        var response = mvc.invoke(B08MvcSession.Call.issue(bearer));
        require(response.status() == 201, "key setup failed with status " + response.status());
        return response.json().get("key").asString();
    }

    private static String observation(B08MvcSession mvc, RequestRef ref, String action, String bearer) throws Exception {
        var response = context(mvc, ref, action, bearer);
        require(response.status() == 200, "context setup for " + action + " failed with status " + response.status());
        return response.json().get("expectedRevision").asString();
    }

    private static B08MvcSession.Reply context(B08MvcSession mvc, RequestRef ref, String action, String bearer)
            throws Exception {
        return mvc.invoke(MockMvcRequestBuilders.get("/requests/{ref}/action-context", ref.value())
                .queryParam("action", action), bearer);
    }

    private static Terminal terminal(B08MvcSession mvc, RequestRef ref, String action, String bearer) throws Exception {
        return new Terminal(ref, action, issue(mvc, bearer), observation(mvc, ref, action, bearer));
    }

    private static B08MvcSession.Reply submit(B08MvcSession mvc, Terminal input, String bearer) throws Exception {
        return change(mvc, input.ref(), input.action(), bearer, input.key(), input.observation(), REASON);
    }

    private static B08MvcSession.Reply raw(B08MvcSession mvc, Terminal input, String bearer, String body) throws Exception {
        return mvc.invoke(new B08MvcSession.Call("/requests/" + input.ref().value() + "/actions/" + input.action(),
                bearer, input.key(), body));
    }

    private static B08MvcSession.Reply change(B08MvcSession mvc, RequestRef ref, String action, String bearer,
                                              String key, String observation, String payload) throws Exception {
        if (action.equals("edit")) {
            return mvc.invoke(MockMvcRequestBuilders.patch("/requests/{ref}/body", ref.value())
                    .header("Idempotency-Key", key).contentType("application/json").content(JSON.writeValueAsBytes(
                            Map.of("expectedRevision", observation, "body", payload))), bearer);
        }
        return raw(mvc, new Terminal(ref, action, key, observation), bearer, payload == null
                ? JSON.writeValueAsString(Map.of("expectedRevision", observation))
                : JSON.writeValueAsString(Map.of("expectedRevision", observation, "reason", payload)));
    }

    private static void problem(B08MvcSession.Reply response, int status, String code) {
        require(response.status() == status && response.json().path("type").asString().equals("urn:requests:problem:" + code)
                        && response.location().isEmpty() && response.cache().equals("no-store")
                        && !response.body().contains(NAME) && !response.body().contains(BODY)
                        && !response.json().has("expectedRevision") && !response.json().has("reason"),
                "expected " + status + " " + code + " but got " + response.status() + " "
                        + response.json().path("type").asString());
    }

    private static void result(B08MvcSession.Reply response, RequestRef ref, String action, String state, long revision) {
        require(response.status() == 200 && response.location().isEmpty() && response.cache().equals("no-store")
                        && response.json().equals(JSON.valueToTree(Map.of("ref", ref.value(), "action", action,
                        "resultRevision", Long.toString(revision), "state", state))),
                "protected result for " + action + " failed with status " + response.status());
    }

    private String versionOf(RequestRef ref) {
        return workflow.queryForObject("SELECT state || ':' || revision || ':' || last_sequence FROM requests.request WHERE ref = ?",
                String.class, ref.value());
    }

    private long scopeVersion() {
        return workflow.queryForObject("SELECT policy_version FROM requests.policy_guard WHERE organization = ?",
                Long.class, OWNER.organization().value());
    }

    private Counts counts() {
        return new Counts(count(workflow, "requests.request"), count(workflow, "requests.transition_fact"),
                count(workflow, "requests.transition_reason"), count(workflow, "requests.operation_receipt"),
                count(workflow, "requests.request_visibility_fact"), count(workflow, WITNESS));
    }

    private static long count(JdbcTemplate client, String table) {
        return client.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(4, SECONDS), "fixture cut opened too late");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture cut interrupted");
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
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private record Created(RequestRef ref, String key) { }

    private record Terminal(RequestRef ref, String action, String key, String observation) { }

    private record Pause(String prefix, CountDownLatch entered, CountDownLatch resume) { }

    private record Counts(long requests, long facts, long reasons, long receipts, long masks, long witnesses) {
        Counts terminal(boolean delete) {
            long delta = delete ? 1 : 0;
            return new Counts(requests, facts + 1, reasons + 1, receipts + 1, masks + delta, witnesses + delta);
        }
    }
}
