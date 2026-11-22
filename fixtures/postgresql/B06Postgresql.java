import com.company.requests.adapter.authority.B06ReservationFixture;
import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcSyntheticAuthority;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthenticatedActor.Kind;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.GuardedRelease;
import com.company.requests.application.GuardedRelease.Outcome;
import com.company.requests.application.IdentityAuthority.Reservation;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.ReleaseBuffer;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.SyntheticScopePolicy.Grant;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Opt-in PostgreSQL oracle for the B06 synthetic authority and release boundary. */
public final class B06Postgresql {
    private static final String ISSUER = "synthetic-issuer";
    private static final OrganizationId ORG = new OrganizationId("b06-scope");
    private static final OrganizationId OTHER = new OrganizationId("b06-other");
    private static final OrganizationId MISSING = new OrganizationId("b06-missing-proof");
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final RequestType SECOND_TYPE = RequestType.BUG_REPORT;
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 7);
    private static final AuthenticatedActor OWNER = human(ORG, "owner");
    private static final AuthenticatedActor KEY_ONLY = human(ORG, "key-only");
    private static final AuthenticatedActor CREATE_ONLY = human(ORG, "create-only");
    private static final AuthenticatedActor OTHER_ACTOR = human(OTHER, "other");
    private static final AuthenticatedActor MISSING_ACTOR = human(MISSING, "missing");
    private static final AuthenticatedActor SERVICE = new AuthenticatedActor(
            ISSUER, ORG, "unmapped-service", Kind.SERVICE, Optional.empty());

    private final DataSource command;
    private final JdbcTemplate workflowAdmin;
    private final JdbcTemplate workflowObserver;
    private final DataSource proof;
    private final JdbcTemplate proofAdmin;
    private final JdbcTemplate proofObserver;
    private final JdbcSyntheticAuthority authority;
    private final JdbcTransactionRunner runner;
    private final JdbcWorkflowRecords records;
    private final JdbcReceiptStore receipts;
    private final SyntheticScopePolicy primaryPolicy = new SyntheticScopePolicy(ORG, 1, List.of(
            new Grant(OWNER, true, Set.of(TYPE, SECOND_TYPE), Set.of(TYPE)),
            new Grant(KEY_ONLY, true, Set.of(), Set.of()),
            new Grant(CREATE_ONLY, false, Set.of(TYPE), Set.of()),
            new Grant(SERVICE, true, Set.of(TYPE), Set.of(TYPE))));
    private final SyntheticScopePolicy otherPolicy = singleScope(OTHER_ACTOR);
    private final SyntheticScopePolicy missingPolicy = singleScope(MISSING_ACTOR);
    private final CurrentAccess access;

    private B06Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections independentProof) {
        command = dataSource(workflow.runtime());
        workflowAdmin = new JdbcTemplate(dataSource(workflow.migration()));
        workflowObserver = new JdbcTemplate(dataSource(workflow.observer()));
        proof = dataSource(independentProof.runtime());
        proofAdmin = new JdbcTemplate(dataSource(independentProof.migration()));
        proofObserver = new JdbcTemplate(dataSource(independentProof.observer()));
        authority = new JdbcSyntheticAuthority(proof);
        runner = new JdbcTransactionRunner(command);
        records = new JdbcWorkflowRecords(command);
        receipts = new JdbcReceiptStore(command);
        access = new CurrentAccess(authority, WRITER, List.of(primaryPolicy, otherPolicy, missingPolicy));
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.workflowAndProof(args, (workflow, proof) -> {
                var check = new B06Postgresql(workflow, proof);
                check.provision(workflow, proof);
                check.checkPolicy();
                check.checkLocks();
                var admitted = check.access.requireCreate(OWNER, TYPE);
                check.checkIndependentBurn(workflow, proof, admitted);
                check.checkCollisionsAndExhaustion(admitted);
                check.checkGuardedRelease(admitted);
            });
        } catch (Exception | AssertionError failure) {
            report(failure);
            System.exit(1);
        }
    }

    private void provision(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections independentProof) {
        new JdbcWorkflowMigrations(dataSource(workflow.migration())).migrate();
        workflowAdmin.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer");
        workflowAdmin.execute("""
                GRANT SELECT, INSERT ON requests.policy_guard, requests.request, requests.request_body,
                    requests.transition_fact, requests.operation_receipt TO b04_workflow
                """);
        workflowAdmin.execute("GRANT UPDATE (policy_version) ON requests.policy_guard TO b04_workflow");
        workflowAdmin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer");
        runner.run(() -> {
            records.insert(new PolicyGuard(ORG, 1));
            records.insert(new PolicyGuard(OTHER, 1));
            records.insert(new PolicyGuard(MISSING, 1));
            return null;
        });

        var migration = new JdbcAuthorityProvisioning(dataSource(independentProof.migration()));
        migration.migrate();
        migration.migrate();
        proofAdmin.execute("GRANT USAGE ON SCHEMA request_authority TO b04_proof, b04_observer");
        proofAdmin.execute("""
                GRANT SELECT ON request_authority.writer_epoch, request_authority.scope_genesis,
                    request_authority.creation_counter, request_authority.creation_reservation TO b04_proof, b04_observer
                """);
        proofAdmin.execute("GRANT INSERT ON request_authority.creation_reservation TO b04_proof");
        proofAdmin.execute("GRANT UPDATE (last_ordinal) ON request_authority.creation_counter TO b04_proof");
        require(List.of(1, 2, 3).equals(workflowObserver.queryForList(
                "SELECT version FROM requests.schema_migration ORDER BY version", Integer.class)),
                "workflow retains consecutive migrations");
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(OWNER, TYPE));
        migration.certifyGenesis(primaryPolicy.genesis());
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(OWNER, TYPE));
        migration.certifyGenesis(otherPolicy.genesis());
        migration.certifyWriter(WRITER);
        migration.certifyGenesis(primaryPolicy.genesis());
        migration.certifyWriter(WRITER);
        expect(AuthorityUnavailableException.class,
                () -> migration.certifyWriter(new WriterEpoch(WRITER.writerId(), WRITER.generation() + 1)));
        expect(AuthorityUnavailableException.class, () -> migration.certifyGenesis(
                new SyntheticScopePolicy(ORG, 1, List.of()).genesis()));

        var proofRuntime = new JdbcTemplate(proof);
        expectSql("42501", () -> proofRuntime.execute("DELETE FROM request_authority.creation_reservation"));
        expectSql("42501", () -> proofRuntime.execute("UPDATE request_authority.scope_genesis SET policy_version = 99"));
        expectSql("42501", () -> proofRuntime.execute("UPDATE request_authority.writer_epoch SET generation = 99"));
        expectSql("42501", () -> proofObserver.execute("UPDATE request_authority.creation_counter SET last_ordinal = 99"));
        expectSql("42501", () -> new JdbcTemplate(command).execute("UPDATE requests.request SET name = 'unpermitted'"));
        require("b04_observer".equals(proofObserver.queryForObject("SELECT current_user", String.class)),
                "proof observer must remain separate and unprivileged");
        require("b04_observer".equals(workflowObserver.queryForObject("SELECT current_user", String.class)),
                "workflow observer must remain separate and unprivileged");
        System.out.println("PASS independent V001 migration/reapply, immutable certified writer/genesis and restricted roles");
    }

    private void checkPolicy() {
        require(access.requireKeyIssue(KEY_ONLY).writer().equals(WRITER), "explicit key-only grant");
        require(access.requireCreate(CREATE_ONLY, TYPE).writer().equals(WRITER), "explicit Create-only grant");
        require(access.requireKeyIssue(SERVICE).writer().equals(WRITER), "explicit service key-only access");
        expect(AccessDeniedException.class, () -> access.requireKeyIssue(CREATE_ONLY));
        expect(AccessDeniedException.class, () -> access.requireCreate(KEY_ONLY, TYPE));
        expect(AccessDeniedException.class, () -> access.requireCreate(OWNER, RequestType.INTERNAL_REQUEST));
        expect(AccessDeniedException.class, () -> access.requireCreate(SERVICE, TYPE));
        expect(AccessDeniedException.class, () -> access.requireCreate(
                new AuthenticatedActor(ISSUER, ORG, OWNER.principal(), Kind.HUMAN, Optional.of(new PersonId("different"))), TYPE));
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(MISSING_ACTOR, TYPE));
        expect(AuthorityUnavailableException.class, () -> new CurrentAccess(authority,
                new WriterEpoch(WRITER.writerId(), WRITER.generation() - 1), List.of(primaryPolicy))
                .requireCreate(OWNER, TYPE));
        expect(AuthorityUnavailableException.class, () -> new CurrentAccess(authority,
                new WriterEpoch(UUID.randomUUID(), WRITER.generation()), List.of(primaryPolicy)).requireCreate(OWNER, TYPE));
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(OWNER, TYPE, new PolicyGuard(ORG, 99)));
        expect(AuthorityUnavailableException.class, () -> access.requireCreate(OWNER, TYPE, new PolicyGuard(OTHER, 1)));
        expect(AuthorityUnavailableException.class, () -> new CurrentAccess(authority, WRITER,
                List.of(new SyntheticScopePolicy(ORG, 1, List.of(new Grant(OWNER, true, Set.of(TYPE), Set.of(TYPE))))))
                .requireCreate(OWNER, TYPE));
        System.out.println("PASS separate key/Create grants, unmapped service denial, cross-scope/missing/mismatched proof and stale writer");
    }

    private void checkLocks() throws Exception {
        expect(IllegalStateException.class, () -> records.lockShared(ORG));
        expect(IllegalStateException.class, () -> records.lockExclusive(ORG));
        runner.run(() -> {
            expect(IllegalStateException.class, () -> new JdbcWorkflowRecords(proof).lockShared(ORG));
            return null;
        });
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (boolean shared : List.of(false, true)) {
                var held = new CountDownLatch(1);
                var finish = new CountDownLatch(1);
                var holder = pool.submit(() -> runner.run(() -> {
                    var row = shared ? records.lockShared(ORG) : records.lockExclusive(ORG);
                    require(row.isPresent(), "lock holder must find the certified organization");
                    held.countDown();
                    await(finish);
                    return null;
                }));
                try {
                    await(held);
                    require(runner.run(() -> records.lockExclusive(OTHER)).isPresent(), "different scope must proceed");
                    if (shared) {
                        require(runner.run(() -> records.lockShared(ORG)).isPresent(), "shared holders must coexist");
                    } else {
                        expectSql("55P03", () -> runner.run(() -> records.lockShared(ORG)));
                    }
                    expectSql("55P03", () -> runner.run(() -> records.lockExclusive(ORG)));
                } finally {
                    finish.countDown();
                }
                holder.get(5, TimeUnit.SECONDS);
            }
        }
        require(runner.run(() -> records.lockExclusive(ORG)).isPresent(), "exclusive lock must be released");
        System.out.println("PASS shared/exclusive conflict matrix, distinct-organization progress and B05 exact ownership");
    }

    private void checkIndependentBurn(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proofConnections,
                                      Admission admitted) throws Exception {
        UUID attempt = UUID.randomUUID();
        var reserved = new Reservation[1];
        expect(ForcedRollback.class, () -> runner.run(() -> {
            records.lockExclusive(ORG).orElseThrow();
            reserved[0] = authority.reserve(admitted, attempt);
            var rolledBack = receipt(OWNER, reserved[0], "aborted");
            writeCreate(OWNER, TYPE, reserved[0], rolledBack);
            require(reservationObserved(attempt, reserved[0]),
                    "observer must see the exact independent ref and tuple during the workflow transaction");
            checkWorkflowCounts(0);
            throw new ForcedRollback();
        }));
        checkWorkflowCounts(0);
        var unavailable = new ReleaseBuffer(64);
        var guarded = guarded(Duration.ofMillis(2400));
        var provisional = receipt(OWNER, reserved[0], "aborted");
        require(guarded.handoffCreate(OWNER, TYPE, () -> true, () -> Optional.of(provisional),
                ignored -> { throw new AssertionError("uncommitted result reached prepare"); }, unavailable) == Outcome.WITHHELD,
                "a reservation or fabricated receipt with no committed target must remain concealed");
        require(unavailable.take().isEmpty(), "provisional reservation escaped to release buffer");

        workflow.restart().run();
        checkWorkflowCounts(0);
        require(authority.reserve(admitted, attempt).equals(reserved[0]) && reservationObserved(attempt, reserved[0]),
                "proof reservation changed across workflow restart");
        proofConnections.restart().run();
        var restartedAuthority = new JdbcSyntheticAuthority(proof);
        require(restartedAuthority.reserve(admitted, attempt).equals(reserved[0]) && reservationObserved(attempt, reserved[0]),
                "proof reservation changed across proof restart");
        expect(AuthorityUnavailableException.class, () -> authority.reserve(access.requireCreate(OTHER_ACTOR, TYPE), attempt));

        UUID subsequent = UUID.randomUUID();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = threads.submit(() -> {
                await(start);
                return restartedAuthority.reserve(admitted, subsequent);
            });
            var second = threads.submit(() -> {
                await(start);
                return authority.reserve(admitted, subsequent);
            });
            start.countDown();
            var one = first.get(5, TimeUnit.SECONDS);
            var two = second.get(5, TimeUnit.SECONDS);
            require(one.equals(two) && reservationObserved(subsequent, one), "same attempt must be exactly once");
            require(!one.ref().equals(reserved[0].ref())
                    && one.creationOrder().ordinal() == reserved[0].creationOrder().ordinal() + 1,
                    "new execution must never reuse the burned reservation");
            byte[] bytes = Base64.getUrlDecoder().decode(one.ref().value());
            require(one.ref().value().length() == 32 && bytes.length == 24 && one.creationOrder().epoch() == WRITER.generation(),
                    "real allocator must issue 192-bit base64url and the current epoch");
        }
        System.out.println("PASS observed independent reservation during workflow rollback, both restarts, same-attempt race and fresh burn");
    }

    private void checkCollisionsAndExhaustion(Admission admitted) {
        var calls = new AtomicInteger();
        var first = B06ReservationFixture.withEntropy(proof, () -> entropy(1));
        var seed = first.reserve(admitted, UUID.randomUUID());
        var retry = B06ReservationFixture.withEntropy(proof, () -> entropy(calls.incrementAndGet() == 1 ? 1 : 2));
        var successful = retry.reserve(admitted, UUID.randomUUID());
        require(calls.get() == 2 && !seed.ref().equals(successful.ref())
                && successful.creationOrder().ordinal() == seed.creationOrder().ordinal() + 1,
                "random collisions must retry without consuming another ordinal");

        calls.set(0);
        var colliding = B06ReservationFixture.withEntropy(proof, () -> {
            calls.incrementAndGet();
            return entropy(1);
        });
        UUID denied = UUID.randomUUID();
        expect(AuthorityUnavailableException.class, () -> colliding.reserve(admitted, denied));
        require(calls.get() == 8 && reservationCount(denied) == 0 && counter() == successful.creationOrder().ordinal(),
                "collision exhaustion must close after eight draws and preserve ordinal");
        expect(AuthorityUnavailableException.class,
                () -> B06ReservationFixture.withEntropy(proof, () -> new byte[23]).reserve(admitted, denied));
        require(reservationCount(denied) == 0 && counter() == successful.creationOrder().ordinal(), "bad entropy changed proof");

        long prior = counter();
        proofAdmin.update("UPDATE request_authority.creation_counter SET last_ordinal = ?", Long.MAX_VALUE - 1);
        UUID last = UUID.randomUUID();
        try {
            var finalReservation = authority.reserve(admitted, last);
            require(finalReservation.creationOrder().ordinal() == Long.MAX_VALUE, "last positive ordinal must be usable");
            require(finalReservation.equals(authority.reserve(admitted, last)), "last attempt must remain retrievable");
            expect(AuthorityUnavailableException.class, () -> authority.reserve(admitted, denied));
            require(reservationCount(denied) == 0 && counter() == Long.MAX_VALUE, "ordinal exhaustion must not wrap");
        } finally {
            // Test-only fault setup is restored by the separately authenticated scratch owner.
            proofAdmin.update("DELETE FROM request_authority.creation_reservation WHERE attempt_id = ?", last);
            proofAdmin.update("UPDATE request_authority.creation_counter SET last_ordinal = ?", prior);
        }
        System.out.println("PASS retry after collision, eight-draw stop, invalid entropy and signed bigint exhaustion without wrap");
    }

    private void checkGuardedRelease(Admission admitted) throws Exception {
        var committed = commitCreate(OWNER, TYPE, admitted, "visible");
        var concealed = commitCreate(OWNER, SECOND_TYPE, admitted, "concealed");
        var createOnly = commitCreate(CREATE_ONLY, TYPE, access.requireCreate(CREATE_ONLY, TYPE), "create-only");
        checkWorkflowCounts(3);
        var guard = guarded(Duration.ofMillis(2400));
        var buffer = new ReleaseBuffer(64);
        var expiry = new AtomicBoolean(true);
        require(guard.handoffCreate(OWNER, TYPE, expiry::get, () -> receipts.find(committed.key()), receipt -> {
            expiry.set(false);
            return payload(receipt);
        }, buffer) == Outcome.FRESHNESS_LOST && buffer.take().isEmpty(), "expiry after prepare must withhold");
        require(guard.handoffCreate(OWNER, TYPE, () -> false,
                () -> { throw new AssertionError("expired call attempted receipt lookup"); }, B06Postgresql::payload, buffer)
                == Outcome.FRESHNESS_LOST, "expired call must stop before lookup");
        expect(AuthorityUnavailableException.class,
                () -> guard.handoffCreate(OWNER, TYPE, () -> true, () -> receipts.find(committed.key()), receipt -> {
                    proofAdmin.execute("REVOKE SELECT ON request_authority.scope_genesis FROM b04_proof");
                    return payload(receipt);
                }, buffer));
        try {
            expect(AuthorityUnavailableException.class, () -> guard.handoffCreate(OWNER, TYPE, () -> true,
                    () -> { throw new AssertionError("unproved call attempted receipt lookup"); }, B06Postgresql::payload, buffer));
            require(buffer.take().isEmpty(), "lost proof must not enqueue prepared bytes");
        } finally {
            proofAdmin.execute("GRANT SELECT ON request_authority.scope_genesis TO b04_proof");
        }
        require(guard.handoffCreate(OWNER, TYPE, () -> true, () -> receipts.find(concealed.key()),
                B06Postgresql::unexpectedPrepare, buffer) == Outcome.WITHHELD, "original type requires its own result grant");
        require(guard.handoffCreate(CREATE_ONLY, TYPE, () -> true, () -> receipts.find(createOnly.key()),
                B06Postgresql::unexpectedPrepare, buffer) == Outcome.WITHHELD, "Create grant does not grant result release");
        require(guard.handoffCreate(OWNER, TYPE, () -> true, () -> receipts.find(createOnly.key()),
                B06Postgresql::unexpectedPrepare, buffer) == Outcome.WITHHELD, "other principal's receipt must be concealed");
        expect(AccessDeniedException.class, () -> guard.handoffCreate(OWNER, RequestType.INTERNAL_REQUEST, () -> true,
                () -> { throw new AssertionError("unauthorized submitted type attempted receipt lookup"); },
                B06Postgresql::unexpectedPrepare, buffer));
        require(guard.handoffCreate(OWNER, TYPE, () -> true, () -> receipts.find(committed.key()),
                B06Postgresql::payload, new ReleaseBuffer(8)) == Outcome.BUFFER_UNAVAILABLE, "capacity must gate release");
        require(guarded(Duration.ofNanos(1)).handoffCreate(OWNER, TYPE, () -> true,
                () -> { throw new AssertionError("expired release budget attempted lookup"); },
                B06Postgresql::unexpectedPrepare, buffer) == Outcome.BUDGET_EXCEEDED, "spent release budget must close");
        expect(RuntimeException.class, () -> guard.handoffCreate(OWNER, TYPE, () -> true,
                () -> receipts.find(committed.key()), receipt -> {
                    Integer ownedBackend = new JdbcTemplate(command).queryForObject("SELECT pg_backend_pid()", Integer.class);
                    require(Boolean.TRUE.equals(workflowAdmin.queryForObject(
                            "SELECT pg_terminate_backend(?, 1000)", Boolean.class, ownedBackend)),
                            "fault must terminate only the active scratch release backend");
                    return payload(receipt);
                }, buffer));
        require(buffer.take().isEmpty(), "release backend lost its transaction before enqueue");
        var uncertain = new ReleaseBuffer(64);
        var lostCommitReply = new TransactionRunner() {
            @Override
            public <T> T run(Supplier<T> work) {
                runner.run(() -> {
                    T outcome = work.get();
                    require(outcome == Outcome.HANDED_OFF && uncertain.take().isEmpty(),
                            "the staged handoff must be invisible before transaction completion");
                    return outcome;
                });
                throw new CommitOutcomeUnknownException(new IllegalStateException("Injected commit reply loss"));
            }
        };
        var uncertainRelease = new GuardedRelease(lostCommitReply, records, records, access, Duration.ofMillis(2400));
        expect(CommitOutcomeUnknownException.class, () -> uncertainRelease.handoffCreate(OWNER, TYPE, () -> true,
                () -> receipts.find(committed.key()), B06Postgresql::payload, uncertain));
        require(uncertain.take().isEmpty(), "an unknown release transaction outcome must discard the staged slot");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            require(guard.handoffCreate(OWNER, TYPE, () -> true, () -> receipts.find(committed.key()), receipt -> {
                try {
                    threads.submit(() -> expectSql("55P03", () -> runner.run(() -> records.lockExclusive(ORG))))
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("The release phase must retain the shared guard through preparation.", failure);
                }
                return payload(receipt);
            }, buffer) == Outcome.HANDED_OFF, "ready committed result must hand off exactly under shared guard");
        }
        require(runner.run(() -> records.lockExclusive(ORG)).isPresent(), "guard must unlock before buffer drain");
        require(Arrays.equals(payload(committed), buffer.take().orElseThrow()) && buffer.take().isEmpty(),
                "only the authorized caller may drain the prepared payload once");
        require(guard.handoffCreate(OWNER, TYPE, () -> true, () -> receipts.find(committed.key()),
                B06Postgresql::payload, buffer) == Outcome.BUFFER_UNAVAILABLE, "consumed buffer must never be reused");
        checkWorkflowCounts(3);
        System.out.println("PASS locked committed handoff, postprepare expiry/proof loss, original/submitted access, no provisional/ref leaks and bounded buffer");
    }

    private OperationReceipt commitCreate(AuthenticatedActor actor, RequestType type, Admission admission, String key) {
        return runner.run(() -> {
            var row = records.lockExclusive(actor.organization()).orElseThrow();
            access.requireCreate(actor, type, row);
            var allocated = authority.reserve(admission, UUID.randomUUID());
            var receipt = receipt(actor, allocated, key);
            writeCreate(actor, type, allocated, receipt);
            return receipt;
        });
    }

    private void writeCreate(AuthenticatedActor actor, RequestType type, Reservation allocated, OperationReceipt receipt) {
        var created = Lifecycle.create(new Lifecycle.Creation(allocated.ref(), type, actor.organization(), "fixture",
                        new RequestBody("fixture body"), allocated.creationOrder(), actor.person().orElseThrow()),
                new TransitionFact.Occurrence(Instant.EPOCH, actor.principal()));
        records.insert(created.request());
        records.insertBody(actor.organization(), allocated.ref(), created.body());
        records.appendFact(actor.organization(), created.transition());
        receipts.insert(receipt);
    }

    private GuardedRelease guarded(Duration budget) {
        return new GuardedRelease(runner, records, records, access, budget);
    }

    private void checkWorkflowCounts(long expected) {
        for (String table : List.of("request", "request_body", "transition_fact", "operation_receipt")) {
            require(Long.valueOf(expected).equals(workflowObserver.queryForObject(
                    "SELECT count(*) FROM requests." + table, Long.class)), "unexpected committed workflow count: " + table);
        }
    }

    private long reservationCount(UUID id) {
        return proofObserver.queryForObject(
                "SELECT count(*) FROM request_authority.creation_reservation WHERE attempt_id = ?", Long.class, id);
    }

    private boolean reservationObserved(UUID id, Reservation reservation) {
        return Long.valueOf(1).equals(proofObserver.queryForObject("""
                SELECT count(*) FROM request_authority.creation_reservation
                WHERE attempt_id = ? AND request_ref = ? AND created_epoch = ? AND created_ordinal = ?
                """, Long.class, id, reservation.ref().value(), reservation.creationOrder().epoch(),
                reservation.creationOrder().ordinal()));
    }

    private long counter() {
        return proofObserver.queryForObject("SELECT last_ordinal FROM request_authority.creation_counter", Long.class);
    }

    private static AuthenticatedActor human(OrganizationId org, String name) {
        return new AuthenticatedActor(ISSUER, org, name, Kind.HUMAN, Optional.of(new PersonId("person-" + name)));
    }

    private static SyntheticScopePolicy singleScope(AuthenticatedActor actor) {
        return new SyntheticScopePolicy(actor.organization(), 1, List.of(new Grant(actor, true, Set.of(TYPE), Set.of(TYPE))));
    }

    private static OperationReceipt receipt(AuthenticatedActor actor, Reservation reservation, String token) {
        return new OperationReceipt(new ReceiptKey(actor.issuer(), actor.organization(), actor.principal(), token),
                new ReceiptIntentDigest(1, "0a"), WRITER.generation(), Instant.EPOCH.plusSeconds(10),
                Action.CREATE, reservation.ref(), 1, RequestState.CREATED, Optional.empty(), 201);
    }

    private static byte[] payload(OperationReceipt receipt) {
        return receipt.resultRef().value().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] unexpectedPrepare(OperationReceipt receipt) {
        throw new AssertionError("an inaccessible result reached prepare");
    }

    private static byte[] entropy(int marker) {
        byte[] bytes = new byte[24];
        bytes[0] = (byte) marker;
        return bytes;
    }

    private static DataSource dataSource(QualifyPostgresql.SqlConnection connection) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return connection.open();
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Fixture connection has a preconfigured role.");
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(5, TimeUnit.SECONDS), "timed out at concurrency barrier");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted at concurrency barrier", failure);
        }
    }

    private static void expectSql(String state, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("expected SQLSTATE " + state);
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
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void report(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String detail = switch (cause) {
                case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                case AssertionError assertion -> " " + assertion.getMessage();
                default -> "";
            };
            System.err.println("B06 failed: " + cause.getClass().getName() + detail);
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().contains("B06") || frame.getClassName().contains("QualifyPostgresql")) {
                    System.err.println("  at " + frame);
                }
            }
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            report(suppressed);
        }
    }

    private static final class ForcedRollback extends RuntimeException { }
}
