import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcPublicationProvisioning;
import com.company.requests.adapter.jdbc.JdbcPublicationWitnessMigration;
import com.company.requests.adapter.jdbc.JdbcPublishGate;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PublicationJournal.Attempt;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.TransactionBusyException;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Native B16 oracle. Fault controls, fixture grants and seeds stay outside the application. */
public final class B16Postgresql {
    private static final OrganizationId FIRST = new OrganizationId("fixture-a");
    private static final OrganizationId SECOND = new OrganizationId("fixture-b");
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 16);
    private static final long TWO_TO_53 = 9_007_199_254_740_992L;
    private static final Instant NOW = Instant.parse("2026-11-22T20:00:00Z");
    private static final String NAMESPACE = "initial-requests";

    private final DataSource command;
    private final DataSource prepare;
    private final DataSource resolve;
    private final DataSource observer;
    private final JdbcTemplate workflowOwner;
    private final JdbcTemplate proofOwner;
    private final JdbcTemplate sql;
    private final JdbcTransactionRunner runner;
    private final JdbcWorkflowRecords guards;
    private final JdbcReceiptStore receipts;
    private final JdbcPublishGate gate;
    private final JdbcPublicationJournal journal;
    private final QualifyPostgresql.Restart restartProof;

    private B16Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection resolver) {
        command = source(workflow.runtime());
        prepare = source(proof.runtime());
        resolve = source(resolver);
        observer = source(workflow.observer());
        workflowOwner = new JdbcTemplate(source(workflow.migration()));
        proofOwner = new JdbcTemplate(source(proof.migration()));
        sql = new JdbcTemplate(command);
        runner = new JdbcTransactionRunner(command);
        guards = new JdbcWorkflowRecords(command);
        receipts = new JdbcReceiptStore(command);
        gate = new JdbcPublishGate(command);
        journal = new JdbcPublicationJournal(prepare, resolve, observer);
        restartProof = proof.restart();
        provision(source(proof.observer()));
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.publicationProofAndRestore(args, (workflow, proof, restore, unused, resolver) -> {
                var fixture = new B16Postgresql(workflow, proof, resolver);
                fixture.globalGate();
                fixture.lateAndLostReplies();
                fixture.exactAndConflictingAttempts();
                fixture.originalEvidence(restore);
                fixture.precisionAndExhaustion();
            });
        } catch (Exception | AssertionError failure) {
            System.err.println(failure instanceof AssertionError ? "B16 oracle failed: " + failure.getMessage()
                    : "B16 fixture failed: " + failure.getClass().getSimpleName());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    System.err.println("JDBC SQLSTATE " + sql.getSQLState());
                }
            }
            Arrays.stream(failure.getStackTrace()).limit(14).forEach(frame -> System.err.println("    at " + frame));
            System.exit(1);
        }
    }

    private void provision(DataSource proofObserver) {
        new JdbcWorkflowMigrations(workflowOwner.getDataSource()).migrate();
        var witnessSchema = new JdbcPublicationWitnessMigration(workflowOwner.getDataSource());
        witnessSchema.migrate();
        witnessSchema.migrate();
        workflowOwner.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer");
        workflowOwner.execute("GRANT SELECT, UPDATE (policy_version) ON requests.policy_guard TO b04_workflow");
        workflowOwner.execute("""
                GRANT SELECT, UPDATE (state, revision, last_sequence, publication_namespace, publication_id)
                ON requests.request TO b04_workflow
                """);
        workflowOwner.execute("""
                GRANT INSERT ON requests.transition_fact, requests.publication_witness TO b04_workflow
                """);
        workflowOwner.execute("GRANT SELECT, INSERT ON requests.operation_receipt TO b04_workflow");
        workflowOwner.execute("""
                GRANT SELECT ON requests.publication_target_evidence, requests.publication_commit_evidence
                TO b04_observer
                """);

        var authority = new JdbcAuthorityProvisioning(proofOwner.getDataSource());
        authority.migrate();
        for (var organization : List.of(FIRST, SECOND)) {
            authority.certifyGenesis(new SyntheticScopePolicy(organization, 1, List.of()).genesis());
            workflowOwner.update("INSERT INTO requests.policy_guard VALUES (?, 1)", organization.value());
        }
        authority.certifyWriter(WRITER);
        authority.certifyWorkflow(WRITER, JdbcWorkflowIdentity.inspect(command));
        var publication = new JdbcPublicationProvisioning(proofOwner.getDataSource());
        publication.migrate();
        publication.migrate();
        proofOwner.execute("GRANT USAGE ON SCHEMA request_authority TO b04_proof, b11_resolver, b04_observer");
        proofOwner.execute("""
                GRANT SELECT ON request_authority.writer_epoch, request_authority.workflow_certificate,
                    request_authority.publication_namespace, request_authority.publication_attempt
                TO b04_proof, b11_resolver, b04_observer
                """);
        proofOwner.execute("""
                GRANT INSERT (attempt_id, namespace, generation, organization, request_type, ref, backend_pid,
                    workflow_transaction, receipt_identity, result_revision)
                ON request_authority.publication_attempt TO b04_proof
                """);
        proofOwner.execute("GRANT EXECUTE ON FUNCTION request_authority.allocate_publication(uuid) TO b04_proof");
        proofOwner.execute("GRANT EXECUTE ON FUNCTION request_authority.finish_publication(uuid, text) TO b11_resolver");

        require(head() == 0, "new namespace starts at zero");
        var writer = new JdbcTemplate(prepare);
        var resolver = new JdbcTemplate(resolve);
        var witnessObserver = new JdbcTemplate(observer);
        denied(() -> writer.update("UPDATE request_authority.publication_namespace SET last_id = 0"));
        denied(() -> writer.update("UPDATE request_authority.publication_attempt SET state = 'ABORTED'"));
        denied(() -> writer.queryForObject("SELECT request_authority.finish_publication(?, 'ABORTED')",
                Boolean.class, UUID.randomUUID()));
        denied(() -> resolver.queryForObject("SELECT request_authority.allocate_publication(?)",
                Long.class, UUID.randomUUID()));
        denied(() -> new JdbcTemplate(proofObserver).queryForObject("SELECT request_authority.allocate_publication(?)",
                Long.class, UUID.randomUUID()));
        denied(() -> witnessObserver.execute("SELECT body FROM requests.request_body"));
        denied(() -> witnessObserver.execute("SELECT name FROM requests.request"));
        denied(() -> witnessObserver.execute("SELECT token_id FROM requests.operation_receipt"));
        denied(() -> witnessObserver.execute("DELETE FROM requests.publication_witness"));
        System.out.println("PASS additive migrations/reapply, separated preparation and terminal roles, narrow original observer");
    }

    private void globalGate() throws Exception {
        Target first = seed(1, FIRST, RequestType.HR_TICKET);
        Target second = seed(2, SECOND, RequestType.BUG_REPORT);
        var guard = new PolicyGuard(first.organization(), 1);
        expect(IllegalStateException.class, () -> gate.acquire(guard, WRITER, first.ref(), first.key()));
        runner.run(() -> {
            guards.lockShared(FIRST).orElseThrow();
            expect(IllegalStateException.class, () -> gate.acquire(guard, WRITER, first.ref(), first.key()));
            var exclusive = guards.lockExclusive(FIRST).orElseThrow();
            expect(AuthorityUnavailableException.class, () -> gate.acquire(exclusive, WRITER, second.ref(), second.key()));
            expect(AuthorityUnavailableException.class, () -> gate.acquire(exclusive, WRITER, first.ref(), second.key()));
            return null;
        });

        var pending = new AtomicReference<Entry>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            expect(Rollback.class, () -> inGate(first, attempt -> {
                pending.set(reserve(attempt, 1));
                expect(IllegalStateException.class, () -> gate.acquire(guard, WRITER, first.ref(), first.key()));
                var competitor = threads.submit(() -> inGate(second, other -> reserve(other, 2)));
                try {
                    competitor.get(3, TimeUnit.SECONDS);
                    throw new AssertionError("contender acquired namespace before rollback");
                } catch (java.util.concurrent.ExecutionException expected) {
                    require(expected.getCause() instanceof TransactionBusyException,
                            "contender waited for namespace while other organization held it");
                } catch (Exception failure) {
                    throw new AssertionError("contender wait did not finish", failure);
                }
                require(head() == 1, "contender did not get ahead of owned transaction");
                expect(AuthorityUnavailableException.class, () -> journal.abort(attempt));
                throw new Rollback();
            }));
        }
        Entry committed = commit(second, 2, true);
        require(journal.confirm(committed.attempt()).state() == State.COMMITTED, "second confirms after original commit");
        require(journal.abort(pending.get().attempt()).allocation().equals(pending.get().allocation()),
                "first rollback retains the burned publication");
        require(head() == 2, "ordered shared high-water");
        System.out.println("PASS gate waited across organizations and request types, rollback burned 1, following commit got 2");
    }

    private void lateAndLostReplies() throws Exception {
        Target beforeReserve = seed(3, FIRST, RequestType.INTERNAL_REQUEST);
        var registered = new AtomicReference<Attempt>();
        var delayed = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var later = threads.submit(() -> {
                require(delayed.await(5, TimeUnit.SECONDS), "delayed reserve released");
                return journal.reserve(registered.get());
            });
            try {
                expect(Rollback.class, () -> inGate(beforeReserve, attempt -> {
                    registered.set(attempt);
                    require(journal.register(attempt).state() == State.REGISTERED, "attempt durably registered");
                    throw new Rollback();
                }));
                var prepOnly = new JdbcPublicationJournal(prepare, prepare, observer);
                expect(AuthorityUnavailableException.class, () -> prepOnly.abort(registered.get()));
                Entry closure = journal.abort(registered.get());
                require(closure.state() == State.ABORTED && closure.allocation().isEmpty(),
                        "rollback closed attempt before assigning an ID");
                delayed.countDown();
                require(later.get(5, TimeUnit.SECONDS).equals(closure), "delayed request cannot reopen closure");
                require(head() == 2, "unallocated closure did not consume a number");
            } finally {
                delayed.countDown();
            }
        }

        Target lostReply = seed(4, SECOND, RequestType.HR_TICKET);
        var forgotten = new AtomicReference<Attempt>();
        var requestSent = new CountDownLatch(1);
        var committedAtProof = new CountDownLatch(1);
        var deliver = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var delayedResponse = threads.submit(() -> {
                await(requestSent, "reserve request arrived");
                Entry response = journal.reserve(forgotten.get());
                committedAtProof.countDown();
                await(deliver, "reserve response released");
                return response;
            });
            try {
                expect(Rollback.class, () -> inGate(lostReply, attempt -> {
                    forgotten.set(attempt);
                    journal.register(attempt);
                    requestSent.countDown();
                    await(committedAtProof, "independent reserve committed");
                    require(!delayedResponse.isDone(), "local command has no reservation reply");
                    throw new Rollback();
                }));
                restartProof.run();
                Attempt attempt = forgotten.get();
                Entry pending = journal.find(attempt).orElseThrow();
                require(pending.state() == State.PENDING && number(pending) == 3 && head() == 3,
                        "independent restart preserves lost-reply reservation");
                expect(AuthorityUnavailableException.class, () -> journal.confirm(attempt));
                Entry closed = journal.abort(attempt);
                require(closed.state() == State.ABORTED && number(closed) == 3,
                        "rollback proof closes a lost reply and burns its ID");
                require(journal.abort(attempt).equals(closed) && journal.reserve(attempt).equals(closed),
                        "exact late retries return the terminal closure");
                deliver.countDown();
                Entry late = delayedResponse.get(10, TimeUnit.SECONDS);
                require(late.equals(pending), "delayed reply carries the original provisional decision");
                inGate(lostReply, newer -> {
                    expect(IllegalStateException.class, () -> gate.appendWitness(late, receipt(lostReply, late)));
                    return null;
                });
                require(journal.activeBinding(WRITER, lostReply.organization(), lostReply.type(), lostReply.ref()).isEmpty(),
                        "aborted target may obtain a fresh higher assignment");
            } finally {
                requestSent.countDown();
                deliver.countDown();
            }
        }
        System.out.println("PASS unnumbered closure beat delayed request, lost reply survived proof restart, late response could not witness");
    }

    private void exactAndConflictingAttempts() {
        Target target = seed(5, FIRST, RequestType.HR_TICKET);
        var prior = new AtomicReference<Entry>();
        expect(Rollback.class, () -> inGate(target, attempt -> {
            var registered = journal.register(attempt);
            require(journal.register(attempt).equals(registered), "repeated registration exact");
            var pending = journal.reserve(attempt);
            require(number(pending) == 4 && journal.reserve(attempt).equals(pending), "repeated reserve exact");
            prior.set(pending);
            for (Attempt changed : List.of(
                    new Attempt(attempt.id(), new WriterEpoch(UUID.randomUUID(), 15), attempt.organization(),
                            attempt.type(), attempt.ref(), attempt.backendPid(), attempt.workflowTransaction(),
                            attempt.receiptIdentity(), attempt.resultRevision()),
                    new Attempt(attempt.id(), WRITER, SECOND, attempt.type(), attempt.ref(), attempt.backendPid(),
                            attempt.workflowTransaction(), attempt.receiptIdentity(), attempt.resultRevision()),
                    new Attempt(attempt.id(), WRITER, attempt.organization(), RequestType.BUG_REPORT, attempt.ref(),
                            attempt.backendPid(), attempt.workflowTransaction(), attempt.receiptIdentity(), attempt.resultRevision()),
                    new Attempt(attempt.id(), WRITER, attempt.organization(), attempt.type(), new RequestRef("a-mismatch"),
                            attempt.backendPid(), attempt.workflowTransaction(), attempt.receiptIdentity(), attempt.resultRevision()),
                    new Attempt(attempt.id(), WRITER, attempt.organization(), attempt.type(), attempt.ref(),
                            attempt.backendPid() + 1, attempt.workflowTransaction(), attempt.receiptIdentity(), attempt.resultRevision()),
                    new Attempt(attempt.id(), WRITER, attempt.organization(), attempt.type(), attempt.ref(),
                            attempt.backendPid(), attempt.workflowTransaction(), "f".repeat(64), attempt.resultRevision()),
                    new Attempt(attempt.id(), WRITER, attempt.organization(), attempt.type(), attempt.ref(),
                            attempt.backendPid(), attempt.workflowTransaction(), attempt.receiptIdentity(), attempt.resultRevision() + 1))) {
                expect(AuthorityUnavailableException.class, () -> journal.register(changed));
                expect(AuthorityUnavailableException.class, () -> journal.reserve(changed));
            }
            expect(AuthorityUnavailableException.class, () -> journal.activeBinding(WRITER, SECOND, target.type(), target.ref()));
            expect(AuthorityUnavailableException.class, () -> journal.confirm(attempt));
            require(head() == 4, "mismatches consumed no publication IDs");
            throw new Rollback();
        }));
        var denied = new AtomicReference<Attempt>();
        expect(Rollback.class, () -> inGate(target, attempt -> {
            denied.set(attempt);
            journal.register(attempt);
            expect(AuthorityUnavailableException.class, () -> journal.reserve(attempt));
            throw new Rollback();
        }));
        require(head() == 4 && journal.abort(denied.get()).allocation().isEmpty(),
                "conflicting ref left high-water intact and closed unnumbered");
        journal.abort(prior.get().attempt());
        Entry newer = commit(target, 5, true);
        Entry confirmed = journal.confirm(newer.attempt());
        require(journal.confirm(newer.attempt()).equals(confirmed) && journal.reserve(newer.attempt()).equals(confirmed),
                "permanent committed binding supports exact repeats");
        expect(AuthorityUnavailableException.class, () -> journal.abort(newer.attempt()));
        require(journal.activeBinding(WRITER, target.organization(), target.type(), target.ref()).orElseThrow().equals(confirmed),
                "scoped ref lookup retains original confirmed association");
        System.out.println("PASS exact replay, stale generation and coordinate mismatches, pending ref fence, abort then higher ref binding");
    }

    private void originalEvidence(QualifyPostgresql.ScopeRestore restore) throws Exception {
        Target target = seed(6, SECOND, RequestType.BUG_REPORT);
        Target disputed = seed(7, FIRST, RequestType.INTERNAL_REQUEST);
        var rewind = source(restore.snapshot());
        Entry pending = commit(target, 6, true);
        var rewoundProof = new JdbcPublicationJournal(prepare, resolve, rewind);
        var restored = new JdbcTemplate(rewind);
        require("ACCEPTED".equals(restored.queryForObject("""
                SELECT state FROM requests.publication_target_evidence WHERE ref = ?
                """, String.class, target.ref().value())), "physical restore retains pre-commit target");
        require(restored.queryForObject("SELECT count(*) FROM requests.publication_commit_evidence", Long.class)
                < new JdbcTemplate(observer).queryForObject("SELECT count(*) FROM requests.publication_commit_evidence", Long.class),
                "rewound observer lacks the post-snapshot original proof");
        expect(AuthorityUnavailableException.class, () -> rewoundProof.confirm(pending.attempt()));
        expect(AuthorityUnavailableException.class, () -> rewoundProof.abort(pending.attempt()));
        require(journal.find(pending.attempt()).orElseThrow().state() == State.PENDING, "rewind leaves original result pending");
        require(journal.confirm(pending.attempt()).state() == State.COMMITTED, "certified original proof confirms association");

        Entry missing = commit(disputed, 7, false);
        expect(AuthorityUnavailableException.class, () -> journal.confirm(missing.attempt()));
        expect(AuthorityUnavailableException.class, () -> journal.abort(missing.attempt()));
        Attempt attempt = missing.attempt();
        workflowOwner.update("""
                INSERT INTO requests.publication_witness (attempt_id, writer_id, generation, organization,
                    request_type, ref, namespace, publication_id, receipt_issuer, receipt_principal, receipt_token_id,
                    result_revision, workflow_transaction)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS xid8))
                """, attempt.id(), WRITER.writerId(), WRITER.generation(), attempt.organization().value(),
                attempt.type().name(), attempt.ref().value(), NAMESPACE, number(missing), disputed.key().issuer(),
                disputed.key().principal(), disputed.key().tokenId(), attempt.resultRevision(), attempt.workflowTransaction());
        expect(AuthorityUnavailableException.class, () -> journal.confirm(attempt));
        require(journal.find(attempt).orElseThrow().equals(missing), "an out-of-transaction witness cannot confirm the effect");
        System.out.println("PASS same-system-ID restored absence fenced both decisions, authentic original confirmed, missing/late witness stayed pending");
    }

    private void precisionAndExhaustion() {
        Target highA = seed(8, FIRST, RequestType.HR_TICKET);
        Target highB = seed(9, SECOND, RequestType.BUG_REPORT);
        Target max = seed(10, SECOND, RequestType.INTERNAL_REQUEST);
        Target exhausted = seed(11, FIRST, RequestType.BUG_REPORT);
        setHead(TWO_TO_53);
        Entry a = commit(highA, TWO_TO_53 + 1, true);
        Entry b = commit(highB, TWO_TO_53 + 2, true);
        require(Long.toString(number(journal.confirm(b.attempt()))).equals("9007199254740994"), "exact second high ID");
        require(Long.toString(number(journal.confirm(a.attempt()))).equals("9007199254740993"), "exact first high ID");
        require(head() == TWO_TO_53 + 2, "confirmation order cannot change numeric commit order");
        setHead(Long.MAX_VALUE - 1);
        Entry edge = commit(max, Long.MAX_VALUE, true);
        require(Long.toString(number(journal.confirm(edge.attempt()))).equals("9223372036854775807"), "exact signed maximum");
        var attempted = new AtomicReference<Attempt>();
        expect(Rollback.class, () -> inGate(exhausted, attempt -> {
            attempted.set(attempt);
            journal.register(attempt);
            expect(AuthorityUnavailableException.class, () -> journal.reserve(attempt));
            throw new Rollback();
        }));
        require(head() == Long.MAX_VALUE && journal.abort(attempted.get()).allocation().isEmpty(), "no wrap at exhaustion");
        System.out.println("PASS exact ordered IDs 9007199254740993, 9007199254740994 and 9223372036854775807, maximum cannot wrap");
    }

    private <T> T inGate(Target target, Function<Attempt, T> change) {
        return runner.run(() -> {
            var guard = guards.lockExclusive(target.organization()).orElseThrow();
            return change.apply(gate.acquire(guard, WRITER, target.ref(), target.key()));
        });
    }

    private Entry reserve(Attempt attempt, long expected) {
        require(journal.register(attempt).state() == State.REGISTERED, "initial attempt registration");
        Entry pending = journal.reserve(attempt);
        require(pending.state() == State.PENDING && number(pending) == expected, "ordered publication number");
        return pending;
    }

    private Entry commit(Target target, long expected, boolean appendWitness) {
        return inGate(target, attempt -> {
            Entry pending = reserve(attempt, expected);
            addFact(sql, target, 4, "PUBLISH", "ACCEPTED", "PUBLISHED");
            require(sql.update("""
                    UPDATE requests.request SET state = 'PUBLISHED', revision = 4, last_sequence = 4,
                        publication_namespace = ?, publication_id = ?
                    WHERE organization = ? AND ref = ? AND state = 'ACCEPTED'
                    """, NAMESPACE, number(pending), target.organization().value(), target.ref().value()) == 1,
                    "fixture publication updated exact target");
            var receipt = receipt(target, pending);
            receipts.insert(receipt);
            if (appendWitness) {
                gate.appendWitness(pending, receipt);
            }
            return pending;
        });
    }

    private static OperationReceipt receipt(Target target, Entry pending) {
        return new OperationReceipt(target.key(), new ReceiptIntentDigest(1, "a".repeat(64)),
                WRITER.generation(), NOW.plusSeconds(60), Action.PUBLISH, target.ref(), pending.attempt().resultRevision(),
                RequestState.PUBLISHED, pending.allocation(), 200);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            require(latch.await(10, TimeUnit.SECONDS), message);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, failure);
        }
    }

    private Target seed(int ordinal, OrganizationId organization, RequestType type) {
        var ref = new RequestRef("fixture-reference-" + ordinal);
        var target = new Target(organization, type, ref, new ReceiptKey("fixture-issuer", organization,
                "fixture-principal", "fixture-receipt-" + ordinal));
        new JdbcTransactionRunner(workflowOwner.getDataSource()).run(() -> {
            workflowOwner.update("""
                    INSERT INTO requests.request (ref, organization, request_type, originating_person_id, name,
                        created_epoch, created_ordinal, state, revision, last_sequence)
                    VALUES (?, ?, ?, 'fixture-person', 'fixture request', 16, ?, 'ACCEPTED', 3, 3)
                    """, ref.value(), organization.value(), type.name(), ordinal);
            addFact(workflowOwner, target, 1, "CREATE", null, "CREATED");
            addFact(workflowOwner, target, 2, "VERIFY", "CREATED", "VERIFIED");
            addFact(workflowOwner, target, 3, "ACCEPT", "VERIFIED", "ACCEPTED");
            return null;
        });
        return target;
    }

    private static void addFact(JdbcTemplate sql, Target target, int sequence, String action, String before, String after) {
        sql.update("""
                INSERT INTO requests.transition_fact (ref, organization, sequence, revision, action, from_state,
                    to_state, recorded_epoch_second, recorded_nano, actor_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'fixture-actor')
                """, target.ref().value(), target.organization().value(), sequence, sequence, action, before, after,
                NOW.getEpochSecond());
    }

    private long head() {
        return proofOwner.queryForObject("SELECT last_id FROM request_authority.publication_namespace", Long.class);
    }

    private void setHead(long target) {
        require(proofOwner.update("UPDATE request_authority.publication_namespace SET last_id = ? WHERE last_id < ?",
                target, target) == 1, "owner fixture seeded high-water forward");
    }

    private static long number(Entry entry) {
        return entry.allocation().orElseThrow().value();
    }

    private static DataSource source(QualifyPostgresql.SqlConnection open) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return open.open();
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                throw new java.sql.SQLFeatureNotSupportedException();
            }
        };
    }

    private static void denied(Runnable operation) {
        try {
            operation.run();
        } catch (DataAccessException failure) {
            require(failure.getMostSpecificCause() instanceof SQLException sql && "42501".equals(sql.getSQLState()),
                    "expected least-privilege SQL denial");
            return;
        }
        throw new AssertionError("restricted fixture role unexpectedly allowed an operation");
    }

    private static void expect(Class<? extends RuntimeException> expected, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw failure;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Target(OrganizationId organization, RequestType type, RequestRef ref, ReceiptKey key) {
    }

    private static final class Rollback extends RuntimeException {
    }
}
