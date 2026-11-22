import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityResolver;
import com.company.requests.adapter.jdbc.JdbcScopeAuthorityWitnesses;
import com.company.requests.adapter.jdbc.JdbcScopeProjectionStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityMutation;
import com.company.requests.application.ScopeAuthorityResolution.OperatorCredential;
import com.company.requests.application.ScopeAuthorityResolution.Operation;
import com.company.requests.application.ScopeCheckpoint;
import com.company.requests.application.ScopeProjectionStore;
import com.company.requests.application.ScopeProofResolver;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.SyntheticScopePolicy.Grant;
import com.company.requests.application.WriterEpoch;
import com.company.requests.application.WriterLineage.Identity;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** B11 opt-in oracle on owned PostgreSQL clusters. */
public final class B11Postgresql {
    private static final OrganizationId SCOPE = new OrganizationId("b11-scope");
    private static final OrganizationId OTHER = new OrganizationId("b11-other");
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 9);
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("b11-issuer", SCOPE, "b11-actor",
            AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("b11-person")));
    private static final SyntheticScopePolicy POLICY = new SyntheticScopePolicy(SCOPE, 1,
            List.of(new Grant(ACTOR, true, Set.of(TYPE), Set.of(TYPE))));
    private static final Admission ADMISSION = new Admission(POLICY.genesis(), WRITER);
    private static final Admission OTHER_ADMISSION = new Admission(
            new SyntheticScopePolicy(OTHER, 1, List.of()).genesis(), WRITER);
    private static final Delta DELTA = new Delta("scoped-authority-ą-東京".getBytes(StandardCharsets.UTF_8));
    private static final String ATTEMPTS = "request_authority.scope_journal_attempt";
    private static final String AUDIT = "request_authority.scope_resolver_audit";
    private static final String HEAD = "request_authority.scope_journal_head";
    private static final String DELTAS = "request_authority.scope_journal_delta";
    private static final String PROJECTION = "requests.scope_authority_projection";
    private static final String CONFIRMATION = "request_authority.scope_journal_confirmation";
    private static final String WITNESS = "requests.scope_authority_witness";

    private final OperatorCredential operator = credential();
    private final OperatorCredential confirmOnly = credential();
    private final JdbcTemplate workflowOwner;
    private final JdbcTemplate proofOwner;
    private final JdbcTemplate commandJdbc;
    private final DataSource observer;
    private final DataSource resolverLogin;
    private final JdbcScopeAuthorityJournal journal;
    private final JdbcTransactionRunner commandTx;
    private final JdbcWorkflowRecords commandGuards;
    private final JdbcScopeAuthorityWitnesses witnesses;
    private final ScopeAuthorityMutation mutation;
    private final JdbcTransactionRunner projectionTx;
    private final JdbcWorkflowRecords projectionGuards;
    private final JdbcScopeProjectionStore projection;
    private final ScopeProofResolver resolver;
    private final CurrentAccess publicAccess;

    private B11Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                         QualifyPostgresql.SqlConnection projectionLogin, QualifyPostgresql.SqlConnection resolveLogin) {
        var command = source(workflow.runtime());
        var prepare = source(proof.runtime());
        var projector = source(projectionLogin);
        observer = source(workflow.observer());
        resolverLogin = source(resolveLogin);
        var workflowAdmin = source(workflow.migration());
        var proofAdmin = source(proof.migration());
        workflowOwner = new JdbcTemplate(workflowAdmin);
        proofOwner = new JdbcTemplate(proofAdmin);
        commandJdbc = new JdbcTemplate(command);
        journal = JdbcScopeAuthorityJournal.withConfirmedProjection(prepare, observer);
        commandTx = JdbcTransactionRunner.forCreate(command);
        commandGuards = new JdbcWorkflowRecords(command);
        witnesses = new JdbcScopeAuthorityWitnesses(command);
        mutation = new ScopeAuthorityMutation(commandTx, commandGuards, journal, witnesses);
        projectionTx = JdbcTransactionRunner.forCreate(projector);
        projectionGuards = new JdbcWorkflowRecords(projector);
        projection = new JdbcScopeProjectionStore(projector);
        resolver = resolver(observer, projection);
        publicAccess = new CurrentAccess(journal, WRITER, List.of(POLICY));
        provision(workflowAdmin, proofAdmin, command);
        checkPrivileges(prepare, projector, source(proof.observer()));
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.scopedResolverAndRestore(args, (workflow, proof, restore, projection, resolver) ->
                    new B11Postgresql(workflow, proof, projection, resolver).exercise(restore));
        } catch (Exception | AssertionError failure) {
            System.err.println(failure instanceof AssertionError ? "B11 oracle failed: " + failure.getMessage()
                    : "B11 fixture failed: " + failure.getClass().getSimpleName());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    System.err.println("JDBC SQLSTATE " + sql.getSQLState());
                }
            }
            Arrays.stream(failure.getStackTrace()).limit(14).forEach(frame -> System.err.println("    at " + frame));
            System.exit(1);
        }
    }

    private void provision(DataSource workflowAdmin, DataSource proofAdmin, DataSource command) {
        var workflowMigrations = new JdbcWorkflowMigrations(workflowAdmin);
        workflowMigrations.migrateScopeWitnesses();
        workflowMigrations.migrateScopeProjection();
        workflowMigrations.migrateScopeProjection();
        workflowOwner.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer, b11_projection");
        workflowOwner.execute("GRANT SELECT, INSERT ON requests.policy_guard TO b04_workflow");
        workflowOwner.execute("GRANT SELECT, UPDATE (policy_version) ON requests.policy_guard TO b11_projection");
        workflowOwner.execute("GRANT UPDATE (policy_version) ON requests.policy_guard TO b04_workflow");
        workflowOwner.execute("GRANT INSERT ON " + WITNESS + " TO b04_workflow");
        workflowOwner.execute("GRANT SELECT ON requests.policy_guard, " + PROJECTION + ", " + WITNESS + " TO b04_observer");
        workflowOwner.execute("GRANT SELECT, INSERT, UPDATE ON " + PROJECTION + " TO b11_projection");
        commandTx.run(() -> {
            commandGuards.insert(new PolicyGuard(SCOPE, 1));
            commandGuards.insert(new PolicyGuard(OTHER, 1));
            return null;
        });
        var provisioning = new JdbcAuthorityProvisioning(proofAdmin);
        provisioning.migrate();
        provisioning.certifyWriter(WRITER);
        provisioning.certifyWorkflow(WRITER, JdbcWorkflowIdentity.inspect(command));
        provisioning.certifyGenesis(ADMISSION.genesis());
        provisioning.certifyGenesis(OTHER_ADMISSION.genesis());
        provisioning.migrateScopeJournal();
        provisioning.migrateScopeResolver();
        provisioning.migrateScopeResolver();
        var operatorId = UUID.randomUUID();
        provisioning.authorizeScopeOperator(operatorId, operator, SCOPE, Operation.CONFIRM);
        provisioning.authorizeScopeOperator(operatorId, operator, SCOPE, Operation.CONFIRM);
        provisioning.authorizeScopeOperator(operatorId, operator, SCOPE, Operation.ABORT);
        provisioning.authorizeScopeOperator(UUID.randomUUID(), confirmOnly, SCOPE, Operation.CONFIRM);
        proofOwner.execute("GRANT USAGE ON SCHEMA request_authority TO b04_proof, b04_observer, b11_resolver");
        proofOwner.execute("""
                GRANT SELECT ON request_authority.scope_genesis, request_authority.writer_epoch,
                    request_authority.workflow_certificate, request_authority.scope_journal_head,
                    request_authority.scope_journal_attempt, request_authority.scope_journal_confirmation
                    TO b04_proof, b11_resolver
                """);
        proofOwner.execute("GRANT INSERT ON " + ATTEMPTS + ", " + DELTAS + " TO b04_proof");
        proofOwner.execute("GRANT UPDATE (sequence, state) ON " + ATTEMPTS + " TO b04_proof");
        proofOwner.execute("GRANT UPDATE (last_sequence) ON " + HEAD + " TO b04_proof");
        proofOwner.execute("GRANT UPDATE (state) ON " + ATTEMPTS + " TO b11_resolver");
        proofOwner.execute("GRANT INSERT ON " + CONFIRMATION + " TO b11_resolver");
        proofOwner.execute("GRANT SELECT, INSERT, UPDATE (terminal_state, outcome) ON " + AUDIT + " TO b11_resolver");
        proofOwner.execute("""
                GRANT SELECT ON request_authority.scope_resolver_operator, request_authority.scope_resolver_grant,
                    request_authority.scope_resolver_delta_check TO b11_resolver
                """);
    }

    private void checkPrivileges(DataSource prepare, DataSource projector, DataSource proofObserver) {
        for (var reader : List.of(prepare, proofObserver, resolverLogin)) {
            expectSql("42501", () -> new JdbcTemplate(reader).execute("SELECT exact_delta FROM " + DELTAS));
        }
        expectSql("42501", () -> new JdbcTemplate(prepare).execute("SELECT * FROM " + AUDIT));
        expectSql("42501", () -> new JdbcTemplate(prepare).execute("DELETE FROM " + CONFIRMATION));
        expectSql("42501", () -> commandJdbc.execute("DELETE FROM " + PROJECTION));
        expectSql("42501", () -> new JdbcTemplate(projector).execute("DELETE FROM requests.operation_receipt"));
        expectSql("42501", () -> new JdbcTemplate(projector).execute("DELETE FROM requests.request"));
        require(workflowOwner.queryForList("SELECT version FROM requests.schema_migration ORDER BY version", Integer.class)
                .equals(List.of(1, 2, 3, 4, 5)), "workflow migration history");
        require(proofOwner.queryForList("SELECT version FROM request_authority.schema_migration ORDER BY version", Integer.class)
                .equals(List.of(1, 2, 3, 4)), "proof migration history");
        System.out.println("PASS additive migration/reapply and independent preparation, resolution, projection and observer roles");
    }
    private void exercise(QualifyPostgresql.ScopeRestore restore) throws Exception {
        require(publicAccess.requireCreate(ACTOR, TYPE).equals(ADMISSION), "normal genesis admission");
        var effects = businessCounts();
        var restored = source(restore.snapshot());
        var entry = commit(ADMISSION, DELTA);
        require(entry.sequence().orElseThrow() == 1 && version(SCOPE) == 2, "one scoped local commit");
        require(count(workflowOwner, WITNESS) == 1 && state(entry) == State.PENDING, "one authentic pending witness");
        var restoreJdbc = new JdbcTemplate(restored);
        require(restoreJdbc.queryForObject("SELECT policy_version FROM requests.policy_guard WHERE organization = ?",
                Long.class, SCOPE.value()) == 1 && count(restoreJdbc, WITNESS) == 0, "restore predates named local effect");
        publicDeferred();
        rejectOperator(entry);
        rejectEvidence(entry, restored);
        require(count(proofOwner, CONFIRMATION) == 0 && count(workflowOwner, PROJECTION) == 0,
                "failed operations produce no confirmation or projection");
        installAfterLostProjection(entry);
        require(businessCounts().equals(effects), "confirmation leaves business effects unchanged");

        closeRolledBackAttempts();
        var next = commit(ADMISSION, new Delta("next-authority-change".getBytes(StandardCharsets.UTF_8)));
        require(next.sequence().orElseThrow() == 3, "next confirmed version follows numbered abort");
        require(resolve(next, Operation.CONFIRM) == State.COMMITTED, "second effective change uses rebuilt version");
        require(resolve(entry, Operation.CONFIRM) == State.COMMITTED, "old exact retry preserves newer projection");
        require(journal.confirmedAuthority().current(ADMISSION).policyVersion() == 3, "latest projected version");
        require(version(OTHER) == 1 && count(workflowOwner, PROJECTION) == 1, "other organization unchanged");
        require(businessCounts().equals(effects), "all resolver work leaves business and release rows intact");
        require(count(proofOwner, CONFIRMATION) == 2 && count(workflowOwner, WITNESS) == 2,
                "only original workflow changes produce witnesses and confirmations");
        reconcileAudit();
        System.out.println("PASS verified multi-entry projection with two commits, an ordered abort and unnumbered closure");
        System.out.println("PASS zero resolver-created lifecycle, assignment, transition, receipt, reservation or release rows");
    }

    private void rejectOperator(Entry entry) {
        var id = entry.attempt().id();
        expect(AccessDeniedException.class, () -> resolver.resolve(null, ADMISSION, id, Operation.CONFIRM));
        expect(AccessDeniedException.class, () -> resolver.resolve(credential(), ADMISSION, id, Operation.CONFIRM));
        expect(AccessDeniedException.class, () -> resolver.resolve(operator, OTHER_ADMISSION, id, Operation.CONFIRM));
        expect(AccessDeniedException.class, () -> resolver.resolve(confirmOnly, ADMISSION, id, Operation.ABORT));
        var other = registerAndRollback(OTHER_ADMISSION);
        expect(AuthorityUnavailableException.class, () -> resolve(other, Operation.CONFIRM));
        expect(AuthorityUnavailableException.class,
                () -> resolver.resolve(operator, ADMISSION, UUID.randomUUID(), Operation.CONFIRM));
        require(state(other) == State.REGISTERED, "foreign attempt remains unchanged");
        System.out.println("PASS absent and invalid operator, wrong organization, wrong operation and foreign attempt fencing");
    }

    private void rejectEvidence(Entry entry, DataSource restored) {
        var id = entry.attempt().id();
        var wrongWriter = new Admission(POLICY.genesis(), new WriterEpoch(UUID.randomUUID(), WRITER.generation()));
        var wrongGeneration = new Admission(POLICY.genesis(), new WriterEpoch(WRITER.writerId(), WRITER.generation() + 1));
        expect(AuthorityUnavailableException.class, () -> resolver.resolve(operator, wrongWriter, id, Operation.CONFIRM));
        expect(AuthorityUnavailableException.class, () -> resolver.resolve(operator, wrongGeneration, id, Operation.CONFIRM));
        for (var mismatch : List.of(
                new Cut(true, 1, OTHER.value()), new Cut(true, 2, 2L), new Cut(true, 3, UUID.randomUUID()),
                new Cut(false, 1, UUID.randomUUID()), new Cut(false, 2, 99L), new Cut(false, 3, 99L),
                new Cut(false, 4, 99L), new Cut(false, 5, "f".repeat(64)), new Cut(false, 6, "1"))) {
            var altered = resolver(observerCut(WITNESS, mismatch), projection);
            expect(AuthorityUnavailableException.class, () -> altered.resolve(operator, ADMISSION, id, Operation.CONFIRM));
            require(mismatch.reached().get(), "witness mismatch reached original query column " + mismatch.column());
        }
        var nullStatus = new Cut(false, 1, null);
        var noStatus = resolver(observerCut("pg_xact_status", nullStatus), projection);
        expect(AuthorityUnavailableException.class, () -> noStatus.resolve(operator, ADMISSION, id, Operation.CONFIRM));
        require(nullStatus.reached().get(), "injected unknown XID status reached original query");
        var unavailable = resolver(source(() -> { throw new SQLException("fixture unavailable", "08006"); }), projection);
        expect(AuthorityUnavailableException.class, () -> unavailable.resolve(operator, ADMISSION, id, Operation.CONFIRM));
        var rewound = resolver(restored, projection);
        expect(AuthorityUnavailableException.class, () -> rewound.resolve(operator, ADMISSION, id, Operation.CONFIRM));
        expect(AuthorityUnavailableException.class, () -> rewound.resolve(operator, ADMISSION, id, Operation.ABORT));
        expect(AuthorityUnavailableException.class, () -> resolve(entry, Operation.ABORT));
        expect(AuthorityUnavailableException.class, () -> journal.abort(ADMISSION, id));
        System.out.println("PASS exact scope, attempt, sequence, writer, generation, version, digest and full XID comparisons");
        System.out.println("PASS same-system-ID rewind, unavailable observer and injected SQL NULL status leave dispute pending");

        var deltaCheck = proofOwner.queryForObject("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'request_authority.scope_journal_delta'::regclass AND contype = 'c'
                  AND pg_get_constraintdef(oid) LIKE '%sha256(exact_delta)%'
                """, String.class);
        require(deltaCheck != null && deltaCheck.matches("[a-z0-9_]+"), "exact delta check belongs to scratch schema");
        proofOwner.execute("ALTER TABLE " + DELTAS + " DROP CONSTRAINT " + deltaCheck);
        try {
            proofOwner.update("UPDATE " + DELTAS + " SET exact_delta = ? WHERE attempt_id = ?", new byte[]{1}, id);
            expect(AuthorityUnavailableException.class, () -> resolve(entry, Operation.CONFIRM));
        } finally {
            proofOwner.update("UPDATE " + DELTAS + " SET exact_delta = ? WHERE attempt_id = ?", DELTA.bytes(), id);
            proofOwner.execute("ALTER TABLE " + DELTAS + " ADD CONSTRAINT " + deltaCheck + " "
                    + "CHECK (octet_length(exact_delta) > 0 AND sha256(exact_delta) = delta_digest)");
        }
        proofOwner.update("UPDATE " + HEAD + " SET last_sequence = 2 WHERE organization = ?", SCOPE.value());
        try {
            expect(AuthorityUnavailableException.class, () -> resolve(entry, Operation.CONFIRM));
            publicDeferred();
            require(state(entry) == State.PENDING, "gap rolls back terminal application");
        } finally {
            proofOwner.update("UPDATE " + HEAD + " SET last_sequence = 1 WHERE organization = ?", SCOPE.value());
        }
        updateVersion(workflowOwner, SCOPE, 2, 4);
        try {
            expect(AuthorityUnavailableException.class, () -> resolve(entry, Operation.CONFIRM));
            require(state(entry) == State.PENDING, "guard mismatch rolls back terminal application");
        } finally {
            updateVersion(workflowOwner, SCOPE, 4, 2);
        }
        publicDeferred();
        System.out.println("PASS saved bytes rehashed independently, missing ordered slot preserved and guarded version checked");
    }

    private void installAfterLostProjection(Entry entry) {
        var id = entry.attempt().id();
        var interrupted = resolver(observer, new ScopeProjectionStore() {
            @Override public Identity writer(PolicyGuard guard) { return projection.writer(guard); }
            @Override public void replace(PolicyGuard guard, ScopeCheckpoint checkpoint) {
                throw new AuthorityUnavailableException();
            }
        });
        expect(AuthorityUnavailableException.class,
                () -> interrupted.resolve(operator, ADMISSION, id, Operation.CONFIRM));
        require(state(entry) == State.COMMITTED, "confirmation committed before projection fault");
        require(proofOwner.queryForObject("SELECT count(*) FROM " + AUDIT
                + " WHERE attempt_id = ? AND terminal_state = 'COMMITTED' AND outcome = 'DEFERRED'", Long.class, id)
                == 1, "audit separates terminal decision from deferred projection");
        publicDeferred();
        require(resolve(entry, Operation.CONFIRM) == State.COMMITTED, "exact retry projects");
        var checkpoint = journal.confirmedAuthority().current(ADMISSION);
        require(checkpoint.sequence() == 1 && checkpoint.policyVersion() == 2, "verified current projection");
        expect(IllegalStateException.class, () -> projection.replace(new PolicyGuard(SCOPE, 2), checkpoint));
        expect(IllegalStateException.class, () -> projectionTx.run(() -> {
            projection.replace(projectionGuards.lockShared(SCOPE).orElseThrow(), checkpoint);
            return null;
        }));
        expect(AuthorityUnavailableException.class, () -> projectionTx.run(() -> {
            projection.replace(projectionGuards.lockExclusive(OTHER).orElseThrow(), checkpoint);
            return null;
        }));
        expect(IllegalStateException.class, () -> commandTx.run(() -> {
            projection.replace(commandGuards.lockExclusive(SCOPE).orElseThrow(), checkpoint);
            return null;
        }));
        workflowOwner.update("UPDATE " + PROJECTION + " SET prefix_digest = ? WHERE organization = ?",
                "f".repeat(64), SCOPE.value());
        publicDeferred();
        require(resolve(entry, Operation.CONFIRM) == State.COMMITTED, "repair from proof");
        require(journal.confirmedAuthority().current(ADMISSION).equals(checkpoint), "exact projection repaired");
        expect(AuthorityUnavailableException.class, () -> publicAccess.requireCreate(ACTOR, TYPE, new PolicyGuard(SCOPE, 2)));
        require(count(workflowOwner, PROJECTION) == 1, "one scope projection after retries");
        System.out.println("PASS commit-to-projection cut stays fenced, exact retry rebuilds and corrupted projection defers");
        System.out.println("PASS projection requires owned exact exclusive scope, version and connection");
    }

    private void closeRolledBackAttempts() {
        expect(Rollback.class, () -> mutation.run(ADMISSION, DELTA, guard -> { throw new Rollback(); }));
        var numbered = journal.findUnresolved(ADMISSION).orElseThrow();
        publicDeferred();
        expect(AuthorityUnavailableException.class, () -> resolve(numbered, Operation.CONFIRM));
        require(resolve(numbered, Operation.ABORT) == State.ABORTED, "exact original abort");
        require(resolve(numbered, Operation.ABORT) == State.ABORTED, "abort retry");
        require(journal.confirmedAuthority().current(ADMISSION).sequence() == numbered.sequence().orElseThrow(),
                "numbered abort in projection");
        var registered = registerAndRollback(ADMISSION);
        require(registered.sequence().isEmpty(), "attempt still unnumbered");
        publicDeferred();
        require(resolve(registered, Operation.ABORT) == State.ABORTED, "unnumbered abort");
        var late = journal.prepare(registered.attempt(), DELTA);
        require(late.state() == State.ABORTED && late.sequence().isEmpty(), "delayed prepare sees durable closure");
        require(journal.confirmedAuthority().current(ADMISSION).policyVersion() == 2, "abort preserves effective version");
        System.out.println("PASS original rollback closes numbered and unnumbered attempts idempotently, including late prepare");
    }

    private void reconcileAudit() {
        require(proofOwner.queryForObject("SELECT count(*) FROM " + AUDIT
                + " WHERE NOT authorized AND operator_id IS NULL", Long.class) == 2, "two unauthenticated audit rows");
        require(proofOwner.queryForObject("SELECT count(*) FROM " + AUDIT
                + " WHERE NOT authorized AND operator_id IS NOT NULL", Long.class) == 2, "two denied authorization rows");
        require(proofOwner.queryForObject("SELECT count(*) FROM " + AUDIT
                + " WHERE authorized AND outcome IS NULL", Long.class) == 0, "all authorized work reconciled");
        require(proofOwner.queryForObject("SELECT count(*) FROM " + AUDIT + " WHERE outcome = 'PROJECTED' "
                + "AND terminal_state IS NULL", Long.class) == 0, "projected audit rows have a terminal state");
        var audit = proofOwner.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'request_authority' AND table_name = 'scope_resolver_audit' ORDER BY ordinal_position",
                String.class);
        require(audit.equals(List.of("audit_id", "recorded_at", "operator_id", "organization", "attempt_id",
                "operation", "authorized", "terminal_state", "outcome")), "minimized audit schema");
        System.out.println("PASS reconciled minimized audit for unauthenticated, denied, deferred, terminal and projected work");
    }

    private Entry commit(Admission admission, Delta delta) {
        return mutation.run(admission, delta, guard -> {
            updateVersion(commandJdbc, guard.organization(), guard.policyVersion(), guard.policyVersion() + 1);
            return null;
        }).prepared();
    }

    private Entry registerAndRollback(Admission admission) {
        expect(Rollback.class, () -> commandTx.run(() -> {
            var guard = commandGuards.lockExclusive(admission.genesis().organization()).orElseThrow();
            journal.register(witnesses.begin(admission, guard, DELTA));
            throw new Rollback();
        }));
        return journal.findUnresolved(admission).orElseThrow();
    }

    private State resolve(Entry entry, Operation operation) {
        return resolver.resolve(operator, ADMISSION, entry.attempt().id(), operation);
    }

    private ScopeProofResolver resolver(DataSource workflowEvidence, ScopeProjectionStore store) {
        return new ScopeProofResolver(projectionTx, projectionGuards,
                new JdbcScopeAuthorityResolver(resolverLogin, workflowEvidence), store);
    }

    private void publicDeferred() {
        expect(AuthorityUnavailableException.class, () -> publicAccess.requireCreate(ACTOR, TYPE));
    }

    private State state(Entry entry) {
        return State.valueOf(proofOwner.queryForObject("SELECT state FROM " + ATTEMPTS + " WHERE attempt_id = ?",
                String.class, entry.attempt().id()));
    }

    private long version(OrganizationId organization) {
        return workflowOwner.queryForObject("SELECT policy_version FROM requests.policy_guard WHERE organization = ?",
                Long.class, organization.value());
    }

    private static void updateVersion(JdbcTemplate jdbc, OrganizationId organization, long prior, long next) {
        require(jdbc.update("UPDATE requests.policy_guard SET policy_version = ? WHERE organization = ? AND policy_version = ?",
                next, organization.value(), prior) == 1, "version update");
    }

    private List<Long> businessCounts() {
        return List.of(count(workflowOwner, "requests.request"), count(workflowOwner, "requests.request_body"),
                count(workflowOwner, "requests.transition_fact"), count(workflowOwner, "requests.request_assignment"),
                count(workflowOwner, "requests.operation_receipt"),
                count(proofOwner, "request_authority.creation_reservation"),
                count(proofOwner, "request_authority.release_prepared"));
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
    private DataSource observerCut(String sqlFragment, Cut cut) {
        return source(() -> proxy(Connection.class, observer.getConnection(), (target, method, args) -> {
            Object result = invoke(target, method, args);
            if (result instanceof PreparedStatement statement && ((String) args[0]).contains(sqlFragment)) {
                return proxy(PreparedStatement.class, statement, (prepared, call, values) -> {
                    if (cut.input() && values != null && values.length >= 2
                            && call.getName().startsWith("set") && values[0].equals(cut.column())) {
                        values[1] = cut.replacement();
                        cut.reached().set(true);
                    }
                    Object actual = invoke(prepared, call, values);
                    if (!cut.input() && actual instanceof ResultSet rows) {
                        return proxy(ResultSet.class, rows, (row, getter, indexes) -> {
                            if (getter.getName().startsWith("get") && indexes != null && indexes.length > 0
                                    && indexes[0].equals(cut.column())) {
                                cut.reached().set(true);
                                return cut.replacement();
                            }
                            return invoke(row, getter, indexes);
                        });
                    }
                    return actual;
                });
            }
            return result;
        }));
    }

    private static <T> T proxy(Class<T> type, T target, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(target, method, args)));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static OperatorCredential credential() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new OperatorCredential(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private static DataSource source(QualifyPostgresql.SqlConnection connection) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                try {
                    return connection.open();
                } catch (SQLException failure) {
                    if (!"08001".equals(failure.getSQLState())) {
                        throw failure;
                    }
                    // A second bounded connect retries only setup, before a query or transaction can start.
                    return connection.open();
                }
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }
        };
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void expectSql(String state, Runnable work) {
        try {
            work.run();
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return;
            }
            throw failure;
        }
        throw new AssertionError("Expected SQLSTATE " + state);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Cut(boolean input, int column, Object replacement, AtomicBoolean reached) {
        Cut(boolean input, int column, Object replacement) {
            this(input, column, replacement, new AtomicBoolean());
        }
    }
    @FunctionalInterface private interface Call {
        Object invoke(Object target, Method method, Object[] args) throws Throwable;
    }
    private static final class Rollback extends RuntimeException { }
}
