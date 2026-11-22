import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.authority.JdbcSyntheticAuthority;
import com.company.requests.adapter.jdbc.JdbcScopeAuthorityWitnesses;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.LocalAttempt;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityJournal;
import com.company.requests.application.ScopeAuthorityMutation;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.SyntheticScopePolicy.Grant;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestType;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** B09's opt-in oracle on the qualified, independently provisioned PostgreSQL fixture. */
public final class B09Postgresql {
    private static final OrganizationId SCOPE = new OrganizationId("b09-scope");
    private static final OrganizationId OTHER = new OrganizationId("b09-other");
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 9);
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("b09-issuer", SCOPE, "b09-actor",
            AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("b09-person")));
    private static final SyntheticScopePolicy POLICY = new SyntheticScopePolicy(SCOPE, 1,
            List.of(new Grant(ACTOR, true, Set.of(TYPE), Set.of(TYPE))));
    private static final SyntheticScopePolicy OTHER_POLICY = new SyntheticScopePolicy(OTHER, 1, List.of());
    private static final Admission ADMISSION = new Admission(POLICY.genesis(), WRITER);
    private static final Admission OTHER_ADMISSION = new Admission(OTHER_POLICY.genesis(), WRITER);
    private static final Delta DELTA = new Delta("fixture-authority-change-ą€".getBytes(StandardCharsets.UTF_8));

    private final DataSource command;
    private final DataSource proof;
    private final JdbcTemplate workflowOwner;
    private final JdbcTemplate proofOwner;
    private final JdbcTemplate workflowObserver;
    private final JdbcTemplate proofObserver;
    private final JdbcTemplate commandJdbc;
    private final JdbcScopeAuthorityJournal journal;
    private final JdbcTransactionRunner runner;
    private final JdbcWorkflowRecords guards;
    private final JdbcScopeAuthorityWitnesses witnesses;
    private final CurrentAccess access;

    private B09Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections independentProof) {
        command = dataSource(workflow.runtime());
        proof = dataSource(independentProof.runtime());
        var namedObserver = dataSource(workflow.observer());
        workflowOwner = new JdbcTemplate(dataSource(workflow.migration()));
        proofOwner = new JdbcTemplate(dataSource(independentProof.migration()));
        workflowObserver = new JdbcTemplate(namedObserver);
        proofObserver = new JdbcTemplate(dataSource(independentProof.observer()));
        commandJdbc = new JdbcTemplate(command);
        journal = new JdbcScopeAuthorityJournal(proof, namedObserver);
        runner = new JdbcTransactionRunner(command);
        guards = new JdbcWorkflowRecords(command);
        witnesses = new JdbcScopeAuthorityWitnesses(command);
        access = new CurrentAccess(journal, WRITER, List.of(POLICY, OTHER_POLICY));
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.scopeProofAndRestore(args, (workflow, proof, restore) -> {
                var fixture = new B09Postgresql(workflow, proof);
                fixture.provision(workflow, proof);
                fixture.checkExactGuard();
                fixture.checkLostPrepare();
                fixture.checkEarlyClosure();
                fixture.checkSameAttemptConcurrency();
                fixture.checkGap();
                fixture.checkLocalEffectivePointAndRestore(restore);
            });
        } catch (Exception | AssertionError failure) {
            if (failure instanceof AssertionError) {
                System.err.println("B09 oracle failed: " + failure.getMessage());
            } else {
                System.err.println("B09 fixture failed: " + failure.getClass().getSimpleName());
            }
            Arrays.stream(failure.getStackTrace()).limit(6).forEach(frame -> System.err.println("    at " + frame));
            System.exit(1);
        }
    }

    private void provision(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections independentProof) {
        var migration = new JdbcWorkflowMigrations(dataSource(workflow.migration()));
        migration.migrate();
        migration.migrateScopeWitnesses();
        migration.migrateScopeWitnesses();
        workflowOwner.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer");
        workflowOwner.execute("GRANT SELECT, INSERT ON requests.policy_guard TO b04_workflow");
        workflowOwner.execute("GRANT UPDATE (policy_version) ON requests.policy_guard TO b04_workflow");
        workflowOwner.execute("GRANT INSERT ON requests.scope_authority_witness TO b04_workflow");
        workflowOwner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer");
        runner.run(() -> {
            guards.insert(new PolicyGuard(SCOPE, 1));
            guards.insert(new PolicyGuard(OTHER, 1));
            return null;
        });
        var provisioning = new JdbcAuthorityProvisioning(dataSource(independentProof.migration()));
        provisioning.migrate();
        provisioning.certifyGenesis(POLICY.genesis());
        provisioning.certifyWriter(WRITER);
        provisioning.certifyWorkflow(WRITER, JdbcWorkflowIdentity.inspect(command));
        provisioning.migrateScopeJournal();
        provisioning.migrateScopeJournal();
        provisioning.certifyGenesis(OTHER_POLICY.genesis());
        provisioning.initializeScopeJournal(OTHER_POLICY.genesis());
        proofOwner.execute("GRANT USAGE ON SCHEMA request_authority TO b04_proof, b04_observer");
        proofOwner.execute("""
                GRANT SELECT ON request_authority.scope_genesis, request_authority.writer_epoch,
                    request_authority.workflow_certificate, request_authority.scope_journal_head,
                    request_authority.scope_journal_attempt, request_authority.schema_migration TO b04_proof, b04_observer
                """);
        proofOwner.execute("GRANT INSERT ON request_authority.scope_journal_attempt TO b04_proof");
        proofOwner.execute("GRANT INSERT ON request_authority.scope_journal_delta TO b04_proof");
        proofOwner.execute("GRANT UPDATE (sequence, state) ON request_authority.scope_journal_attempt TO b04_proof");
        proofOwner.execute("GRANT UPDATE (last_sequence) ON request_authority.scope_journal_head TO b04_proof");
        require(List.of(1, 2, 3, 4).equals(workflowObserver.queryForList(
                "SELECT version FROM requests.schema_migration ORDER BY version", Integer.class)), "V004 upgrade");
        require(List.of(1, 2, 3).equals(proofObserver.queryForList(
                "SELECT version FROM request_authority.schema_migration ORDER BY version", Integer.class)), "V003 upgrade");
        require(access.requireCreate(ACTOR, TYPE).equals(ADMISSION), "clean journal opens certified genesis");
        new JdbcSyntheticAuthority(proof).requireCurrent(ADMISSION);
        expectSql("42501", () -> proofObserver.execute("SELECT exact_delta FROM request_authority.scope_journal_delta"));
        expectSql("42501", () -> new JdbcTemplate(proof).execute("SELECT exact_delta FROM request_authority.scope_journal_delta"));
        expectSql("42501", () -> new JdbcTemplate(proof).execute("DELETE FROM request_authority.scope_journal_attempt"));
        expectSql("42501", () -> workflowObserver.execute("DELETE FROM requests.scope_authority_witness"));
        System.out.println("PASS additive migrations/reapply, empty scope readiness, immutable genesis and protected role grants");
    }

    private void checkExactGuard() {
        var scope = new PolicyGuard(SCOPE, 1);
        expect(IllegalStateException.class, () -> witnesses.begin(ADMISSION, scope, DELTA));
        runner.run(() -> {
            guards.lockShared(SCOPE).orElseThrow();
            var other = guards.lockExclusive(OTHER).orElseThrow();
            expect(IllegalStateException.class, () -> witnesses.begin(ADMISSION, scope, DELTA));
            expect(AuthorityUnavailableException.class, () -> witnesses.begin(ADMISSION, other, DELTA));
            guards.lockExclusive(SCOPE).orElseThrow();
            var wrongOwner = new JdbcScopeAuthorityWitnesses(workflowObserver.getDataSource());
            expect(IllegalStateException.class, () -> wrongOwner.begin(ADMISSION, scope, DELTA));
            return null;
        });
        System.out.println("PASS witness rejects missing, shared, other-scope and wrong-owner transaction guards");
    }

    private void checkLostPrepare() {
        var effects = new AtomicInteger();
        ScopeAuthorityJournal dropReply = new ScopeAuthorityJournal() {
            @Override
            public Entry register(LocalAttempt attempt) {
                return journal.register(attempt);
            }

            @Override
            public Entry prepare(Attempt attempt, Delta delta) {
                journal.prepare(attempt, delta);
                throw new AuthorityUnavailableException();
            }
        };
        expect(AuthorityUnavailableException.class,
                () -> mutation(dropReply).run(ADMISSION, DELTA, guard -> effects.incrementAndGet()));
        require(effects.get() == 0 && witnessCount() == 0 && policyVersion(SCOPE) == 1,
                "lost prepare reply must stop before local effect and witness");
        var pending = journal.findUnresolved(ADMISSION).orElseThrow();
        require(pending.state() == State.PENDING && pending.sequence().orElseThrow() == 1, "first ordered preparation");
        require(journal.find(ADMISSION, pending.attempt().id()).orElseThrow().equals(pending), "named recovery match");
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(ACTOR, TYPE));
        journal.requireCurrent(OTHER_ADMISSION);
        expect(AuthorityUnavailableException.class,
                () -> mutation(journal).run(ADMISSION, DELTA, guard -> effects.incrementAndGet()));
        require(effects.get() == 0, "pending blocks a second mutation");
        require(journal.prepare(pending.attempt(), DELTA).equals(pending), "exact replay retains pending number");
        expect(AuthorityUnavailableException.class, () -> journal.prepare(pending.attempt(), new Delta(new byte[]{2})));
        expect(AuthorityUnavailableException.class, () -> journal.prepare(recoordinate(pending, OTHER_ADMISSION), DELTA));
        var wrongGeneration = new Admission(POLICY.genesis(), new WriterEpoch(WRITER.writerId(), 8));
        expect(AuthorityUnavailableException.class, () -> journal.prepare(recoordinate(pending, wrongGeneration), DELTA));
        byte[] exact = proofOwner.queryForObject("""
                SELECT exact_delta FROM request_authority.scope_journal_delta WHERE attempt_id = ?
                """, byte[].class, pending.attempt().id());
        require(Arrays.equals(exact, DELTA.bytes()), "exact protected bytes persisted");
        var closed = journal.abort(ADMISSION, pending.attempt().id());
        require(closed.state() == State.ABORTED && closed.sequence().orElseThrow() == 1, "numbered abort burns sequence");
        require(journal.prepare(pending.attempt(), DELTA).equals(closed), "late prepare returns numbered terminal entry");
        require(journal.abort(ADMISSION, pending.attempt().id()).equals(closed), "terminal apply is idempotent");
        require(journal.findUnresolved(ADMISSION).isEmpty(), "closed attempt no longer unresolved");
        require(access.requireCreate(ACTOR, TYPE).equals(ADMISSION), "complete aborted prefix restores genesis access");
        System.out.println("PASS lost prepare stops effects, exact replay, conflict denial, protected bytes and numbered closure");
    }

    private void checkEarlyClosure() {
        ScopeAuthorityJournal delayPrepare = new ScopeAuthorityJournal() {
            @Override
            public Entry register(LocalAttempt local) {
                var registered = journal.register(local);
                require(journal.register(local).equals(registered) && registered.state() == State.REGISTERED,
                        "same registration returns durable unnumbered entry");
                expect(AuthorityUnavailableException.class, () -> journal.abort(ADMISSION, local.attempt().id()));
                expect(AuthorityUnavailableException.class, () -> access.requireCreate(ACTOR, TYPE));
                return registered;
            }

            @Override
            public Entry prepare(Attempt attempt, Delta delta) {
                throw new AuthorityUnavailableException();
            }
        };
        expect(AuthorityUnavailableException.class, () -> mutation(delayPrepare).run(ADMISSION, DELTA, guard -> {
            throw new AssertionError("delayed prepare must stop before the effect");
        }));
        var registered = journal.findUnresolved(ADMISSION).orElseThrow();
        require(registered.state() == State.REGISTERED && registered.sequence().isEmpty(), "discover delayed preparation");
        var closed = journal.abort(ADMISSION, registered.attempt().id());
        require(closed.state() == State.ABORTED && closed.sequence().isEmpty(), "terminal barrier before allocation");
        require(journal.prepare(registered.attempt(), DELTA).equals(closed), "late prepare cannot allocate after closure");
        require(head(SCOPE) == 1 && countPayloads() == 1 && witnessCount() == 0, "early closure is payload-free");
        System.out.println("PASS in-progress XID cannot abort, registered fence survives rollback, closure wins delayed prepare");
    }

    private void checkSameAttemptConcurrency() throws Exception {
        var ready = new CountDownLatch(1);
        var prepared = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var commandFuture = pool.submit(() -> expect(Rollback.class, () -> runner.run(() -> {
                var guard = guards.lockExclusive(SCOPE).orElseThrow();
                journal.register(witnesses.begin(ADMISSION, guard, DELTA));
                ready.countDown();
                await(prepared);
                throw new Rollback();
            })));
            try {
                await(ready);
                var attempt = journal.findUnresolved(ADMISSION).orElseThrow().attempt();
                var start = new CountDownLatch(1);
                var first = pool.submit(() -> { await(start); return journal.prepare(attempt, DELTA); });
                var second = pool.submit(() -> { await(start); return journal.prepare(attempt, DELTA); });
                start.countDown();
                var entry = first.get(5, TimeUnit.SECONDS);
                require(entry.equals(second.get(5, TimeUnit.SECONDS)) && entry.sequence().orElseThrow() == 2,
                        "concurrent exact prepare calls must share a sequence");
                require(head(SCOPE) == 2 && countPayloads() == 2, "same attempt consumes one slot");
            } finally {
                prepared.countDown();
            }
            commandFuture.get(5, TimeUnit.SECONDS);
        }
        journal.abort(ADMISSION, journal.findUnresolved(ADMISSION).orElseThrow().attempt().id());
        System.out.println("PASS concurrent proof connections prepare one stable attempt and one protected sequence");
    }

    private void checkGap() {
        proofOwner.update("UPDATE request_authority.scope_journal_head SET last_sequence = 2 WHERE organization = ?",
                OTHER.value());
        expect(AuthorityUnavailableException.class, () -> journal.requireCurrent(OTHER_ADMISSION));
        expect(AuthorityUnavailableException.class, () -> mutation(journal).run(OTHER_ADMISSION, DELTA, guard -> {
            throw new AssertionError("gappy scope attempted an effect");
        }));
        require(head(OTHER) == 2 && journal.findUnresolved(OTHER_ADMISSION).isEmpty(), "no attempt crosses a gap");
        require(access.requireCreate(ACTOR, TYPE).equals(ADMISSION), "different scope remains available");
        System.out.println("PASS independently observed high-water with missing prefix fences the affected scope");
    }

    private void checkLocalEffectivePointAndRestore(QualifyPostgresql.ScopeRestore restore) throws Exception {
        expect(Rollback.class, () -> runner.run(() -> {
            var guard = guards.lockExclusive(SCOPE).orElseThrow();
            var local = witnesses.begin(ADMISSION, guard, DELTA);
            journal.register(local);
            var pending = journal.prepare(local.attempt(), DELTA);
            updateGuard(guard);
            witnesses.append(pending);
            require(commandJdbc.queryForObject("SELECT count(*) FROM requests.policy_guard WHERE policy_version = 2",
                    Long.class) == 1, "effect visible inside local command");
            require(policyVersion(SCOPE) == 1 && witnessCount() == 0, "observer sees neither uncommitted effect nor witness");
            require("in progress".equals(status(workflowObserver, local.attempt())), "attempt points to active command XID");
            throw new Rollback();
        }));
        var rolledBack = journal.findUnresolved(ADMISSION).orElseThrow();
        require(rolledBack.sequence().orElseThrow() == 3 && policyVersion(SCOPE) == 1 && witnessCount() == 0,
                "rollback removes effect and witness while independent PENDING remains");
        runner.run(() -> {
            guards.lockExclusive(SCOPE).orElseThrow();
            expect(IllegalStateException.class, () -> witnesses.append(rolledBack));
            return null;
        });
        journal.abort(ADMISSION, rolledBack.attempt().id());
        System.out.println("PASS owned Spring transaction rolls back effect and witness together, later transaction denied");

        DataSource restored = dataSource(restore.snapshot());
        var committed = mutation(journal).run(ADMISSION, DELTA, this::updateGuard);
        require(committed.result() == 1 && committed.prepared().sequence().orElseThrow() == 4,
                "local commit uses exact ordered preparation");
        var attempt = committed.prepared().attempt();
        require(policyVersion(SCOPE) == 2 && witnessCount() == 1 && witnessMatches(attempt, 4),
                "commit contains exact scope, attempt, writer, full XID, version and digest");
        require("committed".equals(status(workflowObserver, attempt)), "witness XID is committing workflow transaction");
        var restoredObserver = new JdbcTemplate(restored);
        require(restoredObserver.queryForObject("SELECT count(*) FROM requests.scope_authority_witness", Long.class) == 0,
                "physical restored observer has no witness");
        require(restoredObserver.queryForObject("""
                SELECT policy_version FROM requests.policy_guard WHERE organization = ?
                """, Long.class, SCOPE.value()) == 1, "physical restored observer retains prior version");
        var restoredProof = new JdbcScopeAuthorityJournal(proof, restored);
        expect(AuthorityUnavailableException.class, () -> restoredProof.abort(ADMISSION, attempt.id()));
        expect(AuthorityUnavailableException.class, () -> journal.abort(ADMISSION, attempt.id()));
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(ACTOR, TYPE));
        require(journal.findUnresolved(ADMISSION).orElseThrow().equals(committed.prepared()) && countPayloads() == 4,
                "committed or rewound absence cannot close protected pending attempt");
        System.out.println("PASS exact local witness commits with effect; committed XID and actual restored miss keep scope fenced");
    }

    private ScopeAuthorityMutation mutation(ScopeAuthorityJournal preparation) {
        return new ScopeAuthorityMutation(runner, guards, preparation, witnesses);
    }

    private int updateGuard(PolicyGuard guard) {
        return commandJdbc.update("UPDATE requests.policy_guard SET policy_version = ? WHERE organization = ?",
                guard.policyVersion() + 1, guard.organization().value());
    }

    private long policyVersion(OrganizationId org) {
        return workflowObserver.queryForObject("SELECT policy_version FROM requests.policy_guard WHERE organization = ?",
                Long.class, org.value());
    }

    private long witnessCount() {
        return workflowObserver.queryForObject("SELECT count(*) FROM requests.scope_authority_witness", Long.class);
    }

    private boolean witnessMatches(Attempt attempt, long sequence) {
        return workflowObserver.queryForObject("""
                SELECT count(*) FROM requests.scope_authority_witness
                WHERE organization = ? AND sequence = ? AND attempt_id = ? AND writer_id = ? AND generation = ?
                    AND before_version = ? AND after_version = ? AND delta_digest = ?
                    AND workflow_transaction = CAST(? AS xid8)
                """, Long.class, SCOPE.value(), sequence, attempt.id(), WRITER.writerId(), WRITER.generation(),
                attempt.beforeVersion(), attempt.afterVersion(), attempt.deltaDigest(), attempt.workflowTransaction()) == 1;
    }

    private long head(OrganizationId org) {
        return proofObserver.queryForObject("""
                SELECT last_sequence FROM request_authority.scope_journal_head WHERE organization = ?
                """, Long.class, org.value());
    }

    private long countPayloads() {
        return proofOwner.queryForObject("SELECT count(*) FROM request_authority.scope_journal_delta", Long.class);
    }

    private static String status(JdbcTemplate workflow, Attempt attempt) {
        return workflow.queryForObject("SELECT pg_xact_status(CAST(? AS xid8))", String.class, attempt.workflowTransaction());
    }

    private static Attempt recoordinate(Entry entry, Admission admission) {
        var original = entry.attempt();
        return new Attempt(original.id(), admission, original.beforeVersion(), original.afterVersion(),
                original.deltaDigest(), original.workflowTransaction());
    }

    private static DataSource dataSource(QualifyPostgresql.SqlConnection connection) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return connection.open(); }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(5, TimeUnit.SECONDS), "timed out awaiting fixture event");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted fixture event", failure);
        }
    }

    private static <T extends RuntimeException> T expect(Class<T> type, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            require(type.isInstance(failure), "unexpected exception type " + failure.getClass().getSimpleName());
            return type.cast(failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void expectSql(String state, Runnable work) {
        var failure = expect(DataAccessException.class, work);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return;
            }
        }
        throw new AssertionError("expected SQL state " + state);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Rollback extends RuntimeException { }
}
