import com.company.requests.adapter.integration.jdbc.JdbcOutboxWorkerMigration;
import com.company.requests.adapter.jdbc.JdbcOutboxAppender;
import com.company.requests.adapter.jdbc.JdbcOutboxMigration;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration.Route;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.PublishOutboxTrigger;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.DispatchCoordinator.Result;
import com.company.requests.integration.DispatchStore.ArmState;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.DispatchStore.Receipt;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Qualified local Publish to durable dispatch using two independent PostgreSQL worker logins. */
public final class B23Postgresql {
    private static final String ALPHA = "fixture-alpha";
    private static final String BETA = "fixture-beta";
    private static final String UNAPPROVED = "fixture-unapproved";
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final String KEY = "destination = ? AND tenant = ? AND effect_id = ?";

    private final B17Postgresql base;
    private final JdbcTemplate sql;
    private final JdbcTemplate observed;
    private final JdbcTemplate authority;
    private final WriterEpoch writer;
    private final Settings settings;
    private final Worker a;
    private final Worker b;

    private B23Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins release,
                          QualifyPostgresql.DispatchLogins dispatch, B10FixtureSources sources) throws Exception {
        base = new B17Postgresql(workflow, proof, projector, resolver, createProof, release, sources,
                connection -> connection);
        sql = base.sql;
        authority = new JdbcTemplate(B08MvcSession.dataSource(proof.migration()));
        observed = new JdbcTemplate(B08MvcSession.dataSource(workflow.observer()));
        writer = base.settings.create().writer();
        settings = new Settings(base.settings.create(), base.settings.preparation(), base.settings.originalObserver(),
                base.settings.projection(), base.settings.resolver(), base.settings.operators(),
                base.settings.observationKeys(), true, Optional.of(new PublishOutboxTrigger(TYPE, ALPHA)));
        a = new Worker(dispatch.workerA(), dispatch.proofReader(), sources);
        b = new Worker(dispatch.workerB(), dispatch.proofReader(), sources);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.dispatchWorker(args, (workflow, proof, projector, resolver, create, release, dispatch) -> {
                try (var sources = new B10FixtureSources()) {
                    new B23Postgresql(workflow, proof, projector, resolver, create, release, dispatch, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                var detail = switch (cause) {
                    case SQLException db -> " SQLSTATE " + db.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B23 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(10).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        var owner = sql.getDataSource();
        var grants = new JdbcWorkflowMigrations(owner);
        grants.migrateDispatchGrants();
        grants.migrateDispatchGrants();
        new JdbcOutboxMigration(owner).migrate();
        sql.execute("""
                GRANT INSERT (destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                ON requests.outbox TO b04_workflow
                """);
        base.enable(B10Postgresql.OWNER, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY,
                Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY, Grant.DISPATCH_PUBLISH_REFERENCE);
        base.enable(B10Postgresql.ASSIGNEE, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME,
                Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH,
                Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID,
                Grant.DISPATCH_PUBLISH_REFERENCE);

        try (var mvc = new B08MvcSession(settings)) {
            var pending = publish(mvc);
            var retained = copy(pending.effect(), BETA, pending.effect().effectId());
            var before = List.of(envelope(sql, pending.effect()), envelope(sql, retained));
            JdbcOutboxWorkerMigration.migrate(owner);
            JdbcOutboxWorkerMigration.migrate(owner);
            require(before.equals(List.of(envelope(sql, pending.effect()), envelope(sql, retained))),
                    "worker upgrade changed immutable retained or current envelopes");
            authorizeRuntime();
            expectIllegal(() -> SyntheticDispatchConfiguration.worker(settings, settings.create().command(),
                    a.authorityReader, List.of(), Duration.ofSeconds(15), Duration.ofSeconds(1), ignored -> null));
            expectIllegal(() -> JdbcTransactionRunner.forWorker(a.source).run(() -> {
                var saved = envelope(observed, pending.effect());
                new JdbcOutboxAppender(a.source).append(new OutboundObligation(ALPHA, pending.effect().tenant(),
                        pending.effect().effectId(), pending.ref(), pending.entry().attempt().resultRevision(),
                        "PUBLISH", 2, "published-request-reference", java.time.Instant.ofEpochSecond(saved.seconds(),
                                saved.nanos())));
                return null;
            }));
            expectIllegal(() -> JdbcTransactionRunner.forWorker(a.source).run(() ->
                    new JdbcWorkflowRecords(a.source).lockExclusive(pending.effect().tenant())));
            System.out.println("PASS idempotent upgrade kept v1/v2 envelopes and enforced worker and command role separation");

            proofOrdering(pending, retained);
            raceClaimAndArm(mvc);
            equalEffectsAtTwoDestinations(mvc);
            expireUnarmed(mvc);
            expireArmed(mvc);
            withholdCommitReply(mvc);
            epochAndCancellation(mvc);
            exactOriginalAndDestination(mvc);
            predecessorGate(mvc);
            priorRevocation(mvc);
        }
    }

    private void authorizeRuntime() {
        sql.execute("""
                GRANT USAGE ON SCHEMA requests TO b23_worker_a, b23_worker_b;
                GRANT SELECT ON requests.outbox, requests.outbox_attempt, requests.scope_authority_projection,
                    requests.scope_grant_override, requests.publication_confirmation
                    TO b23_worker_a, b23_worker_b;
                GRANT SELECT (organization, policy_version) ON requests.policy_guard TO b23_worker_a, b23_worker_b;
                GRANT SELECT (organization, ref, request_type, revision, state, publication_namespace, publication_id)
                    ON requests.request TO b23_worker_a, b23_worker_b;
                GRANT EXECUTE ON FUNCTION requests.lock_dispatch_guard(text) TO b23_worker_a, b23_worker_b;
                GRANT UPDATE (status, last_attempt_no, deferred_until)
                    ON requests.outbox TO b23_worker_a, b23_worker_b;
                GRANT INSERT (destination, tenant, effect_id, attempt_no, claim_token, writer_id, generation,
                    claimed_at, lease_expires_at, state)
                    ON requests.outbox_attempt TO b23_worker_a, b23_worker_b;
                GRANT UPDATE (state, policy_version, publication_attempt, armed_at, finished_at, confirmation_ref)
                    ON requests.outbox_attempt TO b23_worker_a, b23_worker_b;
                GRANT SELECT ON requests.outbox, requests.outbox_attempt TO b04_observer;
                """);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b23_proof_reader;
                GRANT SELECT ON request_authority.writer_epoch, request_authority.workflow_certificate,
                    request_authority.scope_genesis, request_authority.scope_journal_head,
                    request_authority.scope_journal_attempt, request_authority.scope_journal_confirmation,
                    request_authority.scope_journal_terminal_check, request_authority.publication_namespace,
                    request_authority.publication_attempt TO b23_proof_reader;
                """);
        for (String role : List.of("b23_worker_a", "b23_worker_b")) {
            require(Boolean.TRUE.equals(sql.queryForObject("""
                    SELECT NOT has_column_privilege(?, 'requests.policy_guard', 'policy_version', 'UPDATE')
                       AND NOT has_column_privilege(?, 'requests.request', 'name', 'SELECT')
                       AND NOT has_table_privilege(?, 'requests.request_body', 'SELECT')
                       AND NOT has_column_privilege(?, 'requests.outbox_attempt', 'claim_token', 'UPDATE')
                       AND NOT has_column_privilege(?, 'requests.outbox', 'effect_id', 'UPDATE')
                       AND has_column_privilege(?, 'requests.outbox', 'status', 'UPDATE')
                       AND NOT has_column_privilege('b04_workflow', 'requests.outbox', 'status', 'UPDATE')
                       AND NOT has_table_privilege('b04_workflow', 'requests.outbox_attempt', 'INSERT')
                    """, Boolean.class, role, role, role, role, role, role)), "dispatch roles have excess workflow grants");
        }
    }

    private void proofOrdering(Published pending, EffectKey retained) {
        var first = take(a, retained);
        expect(Result.DEFERRED, a.components.coordinator().dispatch(first));
        assertState(first, "READY", "DEFERRED");
        base.baseline.confirm(base.scopeWitness(pending.ref()));
        ready(retained);
        var second = take(b, retained);
        expect(Result.DEFERRED, b.components.coordinator().dispatch(second));
        assertState(second, "READY", "DEFERRED");
        base.confirm(pending.entry());
        ready(retained);
        var third = take(a, retained);
        expect(Result.CONFIRMED, a.components.coordinator().dispatch(third));
        assertState(third, "CONFIRMED", "CONFIRMED");
        var current = take(b, pending.effect());
        expect(Result.CONFIRMED, b.components.coordinator().dispatch(current));
        assertState(current, "CONFIRMED", "CONFIRMED");
        require(third.id().number() == 3 && a.recorded.size() == 1 && b.recorded.size() == 1,
                "unproved scope or publication admitted a recorder invocation");
        System.out.println("PASS retained READY → DEFERRED twice, then attempt 3 CONFIRMED after independent scope and original publication proof");
    }

    private void raceClaimAndArm(B08MvcSession mvc) throws Exception {
        var published = confirm(publish(mvc));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        a.trace.beforeNextClaimInsert(() -> {
            locked.countDown();
            await(release);
        });
        Claim winner;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var racing = threads.submit(() -> a.components.store().claim(writer));
            try {
                await(locked);
                require(b.components.store().claim(writer).isEmpty() && effectState(published.effect()).equals("READY"),
                        "second worker claimed a locked uncommitted READY row");
            } finally {
                release.countDown();
            }
            winner = racing.get(5, TimeUnit.SECONDS).orElseThrow();
        }
        require(winner.id().effect().equals(published.effect()) && winner.id().number() == 1,
                "unexpected claim winner coordinates");
        assertState(winner, "CLAIMED", "CLAIMED");
        var traceA = a.trace.last(B23WorkerTrace.Step.CLAIM);
        var traceB = b.trace.last(B23WorkerTrace.Step.POLL);
        require(traceA.pid() != traceB.pid() && traceA.pid() != published.entry().attempt().backendPid()
                        && traceB.pid() != published.entry().attempt().backendPid(),
                "worker and command shared one native PostgreSQL backend");
        require(traceA.user().equals("b23_worker_a") && traceB.user().equals("b23_worker_b"),
                "unexpected worker runtime logins");
        var wrong = new Claim(winner.id(), UUID.randomUUID(), writer);
        require(b.components.store().arm(wrong).state() == ArmState.STALE, "forged token armed the real claim");
        a.beforeRecord(invocation -> require(!b.components.store().finish(wrong,
                Optional.of(new Receipt(invocation.id(), UUID.randomUUID()))), "forged token finalized an armed claim"));
        expect(Result.CONFIRMED, a.components.coordinator().dispatch(winner));
        require(a.components.store().arm(winner).state() == ArmState.STALE
                        && countAttempts(published.effect()) == 1, "claimed effect armed twice");
        assertState(winner, "CONFIRMED", "CONFIRMED");
        System.out.printf("PASS exclusive native claim; command backend=%d, worker A=%d xid=%s, worker B=%d xid=%s%n",
                published.entry().attempt().backendPid(), traceA.pid(), traceA.xid(), traceB.pid(), traceB.xid());
    }

    private void equalEffectsAtTwoDestinations(B08MvcSession mvc) throws Exception {
        var publication = confirm(publish(mvc));
        var alternate = copy(publication.effect(), BETA, publication.effect().effectId());
        var left = take(a, alternate);
        var right = take(b, publication.effect());
        require(left.id().number() == right.id().number() && left.id().number() == 1
                        && left.id().effect().effectId().equals(right.id().effect().effectId())
                        && !left.id().equals(right.id()), "scoped duplicate-key input did not match the oracle");
        int leftCount = a.recorded.size();
        int rightCount = b.recorded.size();
        expect(Result.CONFIRMED, a.components.coordinator().dispatch(left));
        expect(Result.CONFIRMED, b.components.coordinator().dispatch(right));
        assertState(left, "CONFIRMED", "CONFIRMED");
        assertState(right, "CONFIRMED", "CONFIRMED");
        require(a.recorded.size() == leftCount + 1 && b.recorded.size() == rightCount + 1
                        && countAttempts(alternate) == 1 && countAttempts(publication.effect()) == 1,
                "duplicate unscoped identifiers merged attempts or invocations");
        System.out.println("PASS identical effect ID and attempt 1 yielded two independently scoped confirmed rows and invocations");
    }

    private void expireUnarmed(B08MvcSession mvc) throws Exception {
        var published = confirm(publish(mvc));
        var expired = take(a, published.effect());
        expire(expired.id());
        var current = take(b, published.effect());
        require(current.id().number() == 2 && attempt(expired.id()).attemptState().equals("EXPIRED_UNSENT"),
                "expired never-armed claim did not allocate the next attempt");
        require(a.components.store().arm(expired).state() == ArmState.STALE, "expired unarmed lease could arm");
        expect(Result.CONFIRMED, b.components.coordinator().dispatch(current));
        System.out.println("PASS expired unarmed attempt 1 became EXPIRED_UNSENT and attempt 2 could arm");
    }

    private void expireArmed(B08MvcSession mvc) throws Exception {
        var published = confirm(publish(mvc));
        var armed = take(a, published.effect());
        a.beforeRecord(invocation -> expire(invocation.id()));
        expect(Result.STALE, a.components.coordinator().dispatch(armed));
        assertState(armed, "ARMED", "ARMED");
        require(b.components.store().claim(writer).isEmpty(), "expired armed effect was reissued");
        assertState(armed, "UNKNOWN", "UNKNOWN");
        require(!a.components.store().finish(armed, Optional.of(new Receipt(armed.id(), UUID.randomUUID())))
                        && countAttempts(published.effect()) == 1,
                "expired armed attempt finalized or issued another attempt");
        System.out.println("PASS expired ARMED remained fenced, stale finalization lost, and sweep kept one UNKNOWN attempt");
    }

    private void withholdCommitReply(B08MvcSession mvc) throws Exception {
        var published = confirm(publish(mvc));
        var claim = take(a, published.effect());
        int count = a.recorded.size();
        a.trace.loseNextArmCommitReply();
        try {
            a.components.coordinator().dispatch(claim);
            throw new AssertionError("Ambiguous arm commit was observed as confirmed.");
        } catch (CommitOutcomeUnknownException expected) {
            require(a.recorded.size() == count, "ambiguous arm commit invoked the local recorder");
        }
        assertState(claim, "ARMED", "ARMED");
        expire(claim.id());
        require(b.components.store().claim(writer).isEmpty(), "second worker issued a possibly armed attempt");
        assertState(claim, "UNKNOWN", "UNKNOWN");
        System.out.println("PASS real ARMED commit with withheld acknowledgement caused zero recorder invocations and became UNKNOWN");
    }

    private void epochAndCancellation(B08MvcSession mvc) throws Exception {
        var published = confirm(publish(mvc));
        var claim = take(a, published.effect());
        int count = a.recorded.size();
        require(authority.update("UPDATE request_authority.writer_epoch SET writer_id = ?", UUID.randomUUID()) == 1,
                "fixture did not replace active writer identity");
        try {
            expect(Result.DEFERRED, a.components.coordinator().dispatch(claim));
        } finally {
            authority.update("UPDATE request_authority.writer_epoch SET writer_id = ?", writer.writerId());
        }
        assertState(claim, "READY", "DEFERRED");
        ready(published.effect());
        var cancelled = take(a, published.effect());
        new JdbcTransactionRunner(sql.getDataSource()).run(() -> {
            new JdbcWorkflowRecords(sql.getDataSource()).lockExclusive(cancelled.id().effect().tenant()).orElseThrow();
            require(sql.update("UPDATE requests.outbox SET cancelled = true WHERE " + KEY,
                    args(cancelled.id().effect())) == 1, "fixture did not cancel the exact outbox row");
            return null;
        });
        expect(Result.SUPPRESSED, a.components.coordinator().dispatch(cancelled));
        assertState(cancelled, "SUPPRESSED", "SUPPRESSED");
        require(a.recorded.size() == count, "wrong epoch or cancellation invoked the recorder");
        System.out.println("PASS changed independent epoch deferred, later guarded cancellation suppressed before ARMED");
    }

    private void exactOriginalAndDestination(B08MvcSession mvc) throws Exception {
        var publication = confirm(publish(mvc));
        var wrong = copy(publication.effect(), BETA, "0".repeat(64));
        var unapproved = copy(publication.effect(), UNAPPROVED, publication.effect().effectId());
        for (var key : List.of(wrong, unapproved)) {
            var claim = take(a, key);
            expect(Result.DEFERRED, a.components.coordinator().dispatch(claim));
            assertState(claim, "READY", "DEFERRED");
        }
        expect(Result.CONFIRMED, b.components.coordinator().dispatchNext());
        require(effectState(publication.effect()).equals("CONFIRMED"),
                "wrong original identity or destination affected the exact approved effect");
        System.out.println("PASS wrong original receipt-derived effect and unapproved destination deferred independently");
    }

    private void predecessorGate(B08MvcSession mvc) throws Exception {
        var successor = confirm(publish(mvc));
        var earlier = new EffectKey(ALPHA, successor.effect().tenant(), "1".repeat(64));
        // Publish is terminal in this slice. An owner-controlled retained row anchored on an actual earlier
        // transition exercises the ordered-reference gate without introducing another live trigger.
        sql.update("""
                INSERT INTO requests.outbox (destination, tenant, effect_id, request_ref, request_revision,
                    trigger_slot, operation, schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                VALUES (?, ?, ?, ?, 2, 'publish.reference', 'PUBLISH', 1, 'publish-request', 17, 123456789)
                """, earlier.destination(), earlier.tenant().value(), earlier.effectId(), successor.ref().value());
        sql.update("""
                INSERT INTO requests.outbox_attempt (destination, tenant, effect_id, attempt_no, claim_token, writer_id,
                    generation, claimed_at, lease_expires_at, state, policy_version, publication_attempt,
                    armed_at, finished_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, statement_timestamp() - interval '2 seconds',
                    statement_timestamp() - interval '1 second', 'UNKNOWN', 1, ?, statement_timestamp(),
                    statement_timestamp())
                """, earlier.destination(), earlier.tenant().value(), earlier.effectId(), UUID.randomUUID(),
                writer.writerId(), writer.generation(), successor.entry().attempt().id());
        require(sql.update("UPDATE requests.outbox SET status = 'UNKNOWN', last_attempt_no = 1 WHERE " + KEY,
                args(earlier)) == 1, "fixture failed to install the retained predecessor");
        expect(Result.IDLE, a.components.coordinator().dispatchNext());
        require(effectState(successor.effect()).equals("READY") && countAttempts(successor.effect()) == 0,
                "unresolved predecessor did not block the ordered successor");
        var unrelatedDestination = copy(successor.effect(), BETA, successor.effect().effectId());
        var independent = take(a, unrelatedDestination);
        expect(Result.CONFIRMED, a.components.coordinator().dispatch(independent));
        assertState(independent, "CONFIRMED", "CONFIRMED");
        require(sql.update("UPDATE requests.outbox SET status = 'CONFIRMED' WHERE " + KEY, args(earlier)) == 1,
                "fixture failed to stage the unproved parent state");
        expect(Result.IDLE, b.components.coordinator().dispatchNext());
        require(sql.update("""
                UPDATE requests.outbox_attempt SET state = 'CONFIRMED', confirmation_ref = gen_random_uuid()
                WHERE attempt_no = 1 AND
                """ + KEY, args(earlier)) == 1, "fixture failed to publish the scoped predecessor proof");
        var claim = take(b, successor.effect());
        expect(Result.CONFIRMED, b.components.coordinator().dispatch(claim));
        System.out.println("PASS same-stream successor stayed READY through UNKNOWN and unproved parent status while other destination advanced");
    }

    private void priorRevocation(B08MvcSession mvc) throws Exception {
        var published = confirm(publish(mvc));
        var claim = take(a, published.effect());
        var revoke = base.profile.updates().stageRevocation(B10Postgresql.OPERATOR,
                new GrantKey(B10Postgresql.ASSIGNEE, Grant.DISPATCH_PUBLISH_REFERENCE, Optional.of(TYPE)));
        base.baseline.confirm(revoke);
        int count = a.recorded.size();
        expect(Result.SUPPRESSED, a.components.coordinator().dispatch(claim));
        assertState(claim, "SUPPRESSED", "SUPPRESSED");
        require(a.recorded.size() == count && attempt(claim.id()).publicationAttempt() == null,
                "revoked route invoked or stored arm authorization");
        System.out.println("PASS independently ordered and confirmed dispatch permission withdrawal won before arm");
    }

    private Published publish(B08MvcSession mvc) throws Exception {
        var ref = base.accepted(mvc, TYPE);
        var bearer = base.publisherBearer;
        var result = B17Postgresql.call(mvc, "/requests/" + ref.value() + "/actions/publish", bearer,
                B17Postgresql.issue(mvc, bearer), Map.of("expectedRevision",
                        B17Postgresql.observation(mvc, ref, "publish", bearer)));
        require(result.status() == 503, "fixture Publish did not stop at its proof boundary");
        var original = base.binding(TYPE, ref);
        var tenant = original.attempt().organization();
        return new Published(ref, original, new EffectKey(ALPHA, tenant, OutboundObligation.effectIdentity(
                original.attempt().receiptIdentity(), OutboundObligation.PUBLISH_REFERENCE_SLOT)));
    }

    private Published confirm(Published publication) {
        base.confirm(publication.entry());
        base.baseline.confirm(base.scopeWitness(publication.ref()));
        return publication;
    }

    private EffectKey copy(EffectKey original, String destination, String effect) {
        require(sql.update("""
                INSERT INTO requests.outbox (destination, tenant, effect_id, request_ref, request_revision,
                    trigger_slot, operation, schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                SELECT ?, tenant, ?, request_ref, request_revision, trigger_slot, operation, 1,
                    'publish-request', 17, 123456789 FROM requests.outbox WHERE
                """ + KEY, destination, effect, original.destination(), original.tenant().value(),
                original.effectId()) == 1, "fixture did not install exact retained destination coordinates");
        return new EffectKey(destination, original.tenant(), effect);
    }

    private static Envelope envelope(JdbcTemplate reader, EffectKey key) {
        return reader.queryForObject("""
                SELECT schema_version, projection_ref, scheduled_epoch_second, scheduled_nano,
                    request_ref, request_revision, trigger_slot FROM requests.outbox WHERE
                """ + KEY, (row, index) -> new Envelope(row.getInt(1), row.getString(2), row.getLong(3),
                row.getInt(4), row.getString(5), row.getLong(6), row.getString(7)), args(key));
    }

    private Claim take(Worker worker, EffectKey key) {
        var claim = worker.components.store().claim(writer).orElseThrow(() -> new AssertionError("Missing exact claim."));
        require(claim.id().effect().equals(key), "worker claimed a different effect");
        return claim;
    }

    private void assertState(Claim claim, String parent, String attempt) {
        var state = attempt(claim.id());
        require(state.effectState().equals(parent) && state.attemptState().equals(attempt)
                        && state.token().equals(claim.token()) && state.writer().equals(claim.writer()),
                "persisted scoped state, token or epoch differs");
        if (attempt.equals("CONFIRMED")) {
            var worker = a.receipts.containsKey(claim.id()) ? a : b;
            var finish = worker.trace.last(B23WorkerTrace.Step.FINISH);
            require(state.confirmation().equals(worker.receipts.get(claim.id()))
                            && state.effectXid().equals(finish.xid()) && state.attemptXid().equals(finish.xid()),
                    "finalization did not commit the exact scoped recorder receipt in the worker transaction");
        }
    }

    private Snapshot attempt(AttemptId id) {
        var key = id.effect();
        return observed.queryForObject("""
                SELECT o.status, a.state, a.claim_token, a.writer_id, a.generation, a.publication_attempt,
                       o.xmin::text, a.xmin::text, a.confirmation_ref, a.policy_version
                FROM requests.outbox o JOIN requests.outbox_attempt a
                  ON o.destination = a.destination AND o.tenant = a.tenant AND o.effect_id = a.effect_id
                WHERE a.destination = ? AND a.tenant = ? AND a.effect_id = ? AND a.attempt_no = ?
                """, (row, index) -> new Snapshot(row.getString(1), row.getString(2), row.getObject(3, UUID.class),
                new WriterEpoch(row.getObject(4, UUID.class), row.getLong(5)), row.getObject(6, UUID.class),
                row.getString(7), row.getString(8), row.getObject(9, UUID.class), row.getLong(10)),
                key.destination(), key.tenant().value(), key.effectId(), id.number());
    }

    private String effectState(EffectKey key) {
        return observed.queryForObject("SELECT status FROM requests.outbox WHERE " + KEY, String.class, args(key));
    }

    private long countAttempts(EffectKey key) {
        return observed.queryForObject("SELECT count(*) FROM requests.outbox_attempt WHERE " + KEY,
                Long.class, args(key));
    }

    private void ready(EffectKey key) {
        require(sql.update("UPDATE requests.outbox SET deferred_until = statement_timestamp() WHERE " + KEY,
                args(key)) == 1, "fixture failed to advance the exact deferral clock");
    }

    private void expire(AttemptId id) {
        var key = id.effect();
        require(sql.update("""
                UPDATE requests.outbox_attempt SET claimed_at = statement_timestamp() - interval '2 seconds',
                   lease_expires_at = statement_timestamp() - interval '1 second'
                WHERE attempt_no = ? AND
                """ + KEY, id.number(), key.destination(), key.tenant().value(), key.effectId()) == 1,
                "fixture failed to expire the exact lease");
    }

    private static Object[] args(EffectKey key) {
        return new Object[]{key.destination(), key.tenant().value(), key.effectId()};
    }

    private static void expect(Result expected, Result actual) {
        require(expected == actual, "expected dispatch result " + expected + ", observed " + actual);
    }

    private static void expectIllegal(Runnable work) {
        try {
            work.run();
            throw new AssertionError("Expected rejection of mixed worker and command access.");
        } catch (IllegalStateException | IllegalArgumentException expected) {
            // The application must reject the call before using command-only SQL.
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(4, TimeUnit.SECONDS), "fixture claim barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture claim barrier interrupted", interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Published(RequestRef ref, Entry entry, EffectKey effect) { }

    private record Envelope(int version, String projection, long seconds, int nanos, String ref, long revision,
                            String slot) { }

    private record Snapshot(String effectState, String attemptState, UUID token, WriterEpoch writer,
                            UUID publicationAttempt, String effectXid, String attemptXid, UUID confirmation,
                            long policyVersion) { }

    private final class Worker {
        private final B23WorkerTrace trace = new B23WorkerTrace();
        private final List<Invocation> recorded = new ArrayList<>();
        private final Map<AttemptId, UUID> receipts = new HashMap<>();
        private final AtomicReference<Consumer<Invocation>> beforeRecord = new AtomicReference<>();
        private final DataSource authorityReader;
        private final DataSource source;
        private final SyntheticDispatchConfiguration.Components components;

        private Worker(QualifyPostgresql.SqlConnection worker, QualifyPostgresql.SqlConnection authority,
                       B10FixtureSources sources) throws SQLException {
            source = sources.source(worker, trace::observe);
            authorityReader = sources.source(authority);
            components = SyntheticDispatchConfiguration.worker(settings, source, authorityReader,
                    List.of(new Route(ALPHA, B10Postgresql.ASSIGNEE, TYPE), new Route(BETA, B10Postgresql.OWNER, TYPE)),
                    Duration.ofSeconds(15), Duration.ofMinutes(5), this::record);
        }

        private void beforeRecord(Consumer<Invocation> callback) {
            beforeRecord.set(callback);
        }

        private Receipt record(Invocation invocation) {
            var arm = trace.last(B23WorkerTrace.Step.ARM);
            var guard = trace.last(B23WorkerTrace.Step.GUARD);
            var state = attempt(invocation.id());
            var obligation = envelope(observed, invocation.id().effect());
            var original = base.binding(TYPE, invocation.ref());
            require(state.effectState().equals("ARMED") && state.attemptState().equals("ARMED")
                            && state.publicationAttempt().equals(original.attempt().id()) && state.policyVersion() > 0
                            && state.effectXid().equals(arm.xid()) && state.attemptXid().equals(arm.xid())
                            && arm.xid().equals(guard.xid()) && arm.pid() == guard.pid()
                            && obligation.ref().equals(invocation.ref().value())
                            && obligation.revision() == invocation.revision()
                            && !TransactionSynchronizationManager.isActualTransactionActive()
                            && !TransactionSynchronizationManager.hasResource(source)
                            && Boolean.TRUE.equals(sql.queryForObject("""
                                    SELECT state = 'idle' AND xact_start IS NULL FROM pg_stat_activity WHERE pid = ?
                                    """, Boolean.class, arm.pid())),
                    "recorder did not see a committed exact arm outside the native worker transaction");
            require(state.confirmation() == null, "recording began with a preexisting receipt");
            System.out.printf("PASS recorder observed committed ARMED backend=%d xid=%s attempt=%d; transaction closed%n",
                    arm.pid(), arm.xid(), invocation.id().number());
            recorded.add(invocation);
            var callback = beforeRecord.getAndSet(null);
            if (callback != null) {
                callback.accept(invocation);
            }
            UUID proof = UUID.randomUUID();
            receipts.put(invocation.id(), proof);
            return new Receipt(invocation.id(), proof);
        }
    }
}
