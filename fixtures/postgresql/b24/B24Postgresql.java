import com.company.requests.adapter.integration.jdbc.JdbcOutboxWorkerMigration;
import com.company.requests.adapter.integration.jdbc.JdbcSyntheticConnector.Send;
import com.company.requests.adapter.integration.jdbc.JdbcSyntheticConnector.View;
import com.company.requests.adapter.integration.jdbc.JdbcSyntheticDeliveryMigration;
import com.company.requests.adapter.jdbc.JdbcOutboxMigration;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration.DeliveryComponents;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration.Route;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.PublishOutboxTrigger;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.DeliveryReconciler;
import com.company.requests.integration.DispatchCoordinator;
import com.company.requests.integration.DispatchCoordinator.Result;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.DispatchStore.Receipt;
import com.company.requests.integration.SyntheticDelivery.Action;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Contract;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.Exclusion;
import com.company.requests.integration.SyntheticDelivery.Observation;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native E6 outbound fixture. All obligations are ordinary guarded Publish effects. */
public final class B24Postgresql {
    private static final String SINGLE = "fixture-single";
    private static final String FENCED = "fixture-fenced";
    private static final String UNSAFE = "fixture-unsafe";
    private static final String KEY = "destination = ? AND tenant = ? AND effect_id = ?";
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final AuthenticatedActor ROUTE = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor OPERATOR = B10Postgresql.OWNER;

    private final B17Postgresql base;
    private final JdbcTemplate sql;
    private final JdbcTemplate remote;
    private final Settings settings;
    private final WriterEpoch writer;
    private final DataSource sourceA;
    private final DataSource sourceB;
    private final DataSource proof;
    private final DataSource remoteSource;
    private final DeliveryComponents a;
    private final DeliveryComponents b;

    private B24Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections authority,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins release,
                          QualifyPostgresql.DispatchLogins workers, B10FixtureSources sources) throws Exception {
        base = new B17Postgresql(workflow, authority, projector, resolver, createProof, release, sources,
                connection -> connection);
        sql = base.sql;
        writer = base.settings.create().writer();
        settings = new Settings(base.settings.create(), base.settings.preparation(),
                B08MvcSession.dataSource(workflow.observer()),
                base.settings.projection(), base.settings.resolver(), base.settings.operators(),
                base.settings.observationKeys(), true, Optional.of(new PublishOutboxTrigger(TYPE, SINGLE)));
        var ledger = new B24RemoteDatabase(authority.migration());
        remote = ledger.observer;
        remoteSource = ledger.runtime;
        sourceA = B08MvcSession.dataSource(workers.workerA());
        sourceB = B08MvcSession.dataSource(workers.workerB());
        proof = B08MvcSession.dataSource(workers.proofReader());
        provision(new JdbcTemplate(B08MvcSession.dataSource(authority.migration())));
        a = worker(sourceA);
        b = worker(sourceB);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.dispatchWorker(args, (workflow, proof, projector, resolver, create, release, dispatch) -> {
                B24Postgresql fixture;
                Invocation pending;
                try (var sources = new B10FixtureSources()) {
                    fixture = new B24Postgresql(workflow, proof, projector, resolver, create, release, dispatch, sources);
                    try (var mvc = new B08MvcSession(fixture.settings)) {
                        fixture.exercise(mvc);
                        pending = fixture.acceptForRestart(mvc);
                    }
                }
                fixture.verifyAfterRestart(pending, proof.restart());
                System.out.println("PASS B24 native E6 outbound controls and persisted proof");
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                var detail = switch (cause) {
                    case SQLException db -> " SQLSTATE " + db.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B24 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(10).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void provision(JdbcTemplate authority) {
        var owner = sql.getDataSource();
        new JdbcWorkflowMigrations(owner).migrateDispatchGrants();
        new JdbcOutboxMigration(owner).migrate();
        JdbcOutboxWorkerMigration.migrate(owner);
        JdbcSyntheticDeliveryMigration.workflow(owner);
        JdbcSyntheticDeliveryMigration.workflow(owner);
        sql.execute("""
                GRANT INSERT (destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                    ON requests.outbox TO b04_workflow;
                GRANT USAGE ON SCHEMA requests TO b23_worker_a, b23_worker_b;
                GRANT SELECT ON requests.outbox, requests.outbox_attempt, requests.scope_authority_projection,
                    requests.scope_grant_override, requests.publication_confirmation, requests.outbox_delivery_recovery
                    TO b23_worker_a, b23_worker_b;
                GRANT SELECT (organization, policy_version) ON requests.policy_guard TO b23_worker_a, b23_worker_b;
                GRANT SELECT (organization, ref, request_type, revision, state, publication_namespace, publication_id)
                    ON requests.request TO b23_worker_a, b23_worker_b;
                GRANT EXECUTE ON FUNCTION requests.lock_dispatch_guard(text) TO b23_worker_a, b23_worker_b;
                GRANT UPDATE (status, last_attempt_no, deferred_until, retry_uncertain, delivery_endpoint)
                    ON requests.outbox TO b23_worker_a, b23_worker_b;
                GRANT INSERT (destination, tenant, effect_id, attempt_no, claim_token, writer_id, generation,
                    claimed_at, lease_expires_at, state) ON requests.outbox_attempt TO b23_worker_a, b23_worker_b;
                GRANT UPDATE (state, policy_version, publication_attempt, armed_at, finished_at, confirmation_ref,
                    delivery_observation, remote_effect, remote_attempt_no, recovery_token)
                    ON requests.outbox_attempt TO b23_worker_a, b23_worker_b;
                GRANT INSERT (recovery_id, destination, tenant, effect_id, attempt_no, operator_issuer,
                    operator_principal, writer_id, generation, policy_version, publication_attempt, action),
                    UPDATE (disposition, observation_ref, finished_at)
                    ON requests.outbox_delivery_recovery TO b23_worker_a, b23_worker_b;
                """);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b23_proof_reader;
                GRANT SELECT ON request_authority.writer_epoch, request_authority.workflow_certificate,
                    request_authority.scope_genesis, request_authority.scope_journal_head,
                    request_authority.scope_journal_attempt, request_authority.scope_journal_confirmation,
                    request_authority.scope_journal_terminal_check, request_authority.publication_namespace,
                    request_authority.publication_attempt TO b23_proof_reader;
                """);
        base.enable(OPERATOR, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY,
                Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY, Grant.DISPATCH_PUBLISH_REFERENCE);
        base.enable(ROUTE, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME,
                Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH,
                Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID,
                Grant.DISPATCH_PUBLISH_REFERENCE);
    }

    private DeliveryComponents worker(DataSource source) {
        return SyntheticDispatchConfiguration.syntheticWorker(settings, source, proof, remoteSource,
                List.of(new Route(SINGLE, ROUTE, TYPE), new Route(FENCED, ROUTE, TYPE), new Route(UNSAFE, ROUTE, TYPE)),
                Duration.ofSeconds(30), Duration.ofMillis(200),
                Map.of(SINGLE, Contract.SINGLE_EFFECT, FENCED, Contract.FENCEABLE, UNSAFE, Contract.UNSAFE));
    }

    private void exercise(B08MvcSession mvc) throws Exception {
        scopes(mvc);
        lostAndLookupQuality(mvc);
        rejectionAndQuarantine(mvc);
        terminalProofWithUnarmedGap(mvc, Send.THROTTLE, Observation.TRANSIENT, Result.RETRYABLE);
        terminalProofWithUnarmedGap(mvc, Send.REJECT, Observation.PERMANENT, Result.FAILED_PERMANENT);
        terminalProofWithArmedGap(mvc);
        delayed(mvc, SINGLE, true);
        delayed(mvc, SINGLE, false);
        delayed(mvc, FENCED, true);
        delayed(mvc, FENCED, false);
        fencePendingAndSuppress(mvc);
        authorityCuts(mvc);
        retryBindings(mvc);
        require(a.coordinator().dispatchNext() == Result.IDLE, "unexpected claimable remainder");
        recorderCompatibility(mvc);
    }

    private void scopes(B08MvcSession mvc) throws Exception {
        var first = publish(mvc, SINGLE);
        var second = copy(first, FENCED);
        var expected = new HashSet<>(List.of(first, second));
        for (int count = 0; count < 2; count++) {
            var claim = a.store().claim(writer).orElseThrow(() -> new AssertionError("missing scoped claim"));
            require(expected.remove(claim.id().effect()), "worker claimed a duplicate or unrelated destination");
            require(claim.id().number() == 1, "scope fixture attempted a replacement");
            expect(Result.CONFIRMED, a.coordinator().dispatch(claim));
            confirm(claim.id(), 1);
        }
        require(!mapping(new AttemptId(first, 1)).equals(mapping(new AttemptId(second, 1))),
                "equal effect IDs and attempt numbers collapsed distinct destinations");
        var otherTenant = new EffectKey(first.destination(), new OrganizationId("b24-other"), first.effectId());
        var invocation = invocation(new AttemptId(first, 1));
        var other = new Invocation(new AttemptId(otherTenant, 1), invocation.ref(), invocation.revision());
        var evidence = a.connector().send(other).orElseThrow();
        require(evidence.effect().isPresent() && countEffects(otherTenant) == 1
                && !mapping(invocation.id()).equals(evidence.effect().orElseThrow().reference()),
                "equal effect IDs and attempt numbers collapsed distinct tenants");
        System.out.println("PASS equal effect ID/attempt 1 in two destinations, plus independent tenant scope");
    }

    private Invocation acceptForRestart(B08MvcSession mvc) throws Exception {
        var claim = claim(a, publish(mvc, SINGLE));
        expect(Result.ACCEPTED_PENDING, sender(a, Send.ACCEPT).dispatch(claim));
        state(claim.id(), "ACCEPTED_PENDING", "ACCEPTED_PENDING", 0, 1);
        require(b.coordinator().dispatchNext() == Result.IDLE, "async acceptance became eligible for repeat");
        return invocation(claim.id());
    }

    private void verifyAfterRestart(Invocation admitted, QualifyPostgresql.Restart restart) throws Exception {
        restart.run();
        var reconstructed = worker(sourceB);
        recovered(reconstructed.reconciler(), admitted.id(), Action.RETRY);
        state(admitted.id(), "ACCEPTED_PENDING", "ACCEPTED_PENDING", 0, 1);
        reconstructed.connector().complete(admitted);
        recovered(reconstructed.reconciler(), admitted.id(), Action.RECONCILE);
        confirm(admitted.id(), 1);
        require(reconstructed.coordinator().dispatchNext() == Result.IDLE, "unexpected claimable remainder after restart");
        System.out.println("PASS accepted job survived PostgreSQL restart and a reconstructed worker; confirmation followed completion");
    }

    private void lostAndLookupQuality(B08MvcSession mvc) throws Exception {
        var applied = claim(a, publish(mvc, SINGLE));
        expect(Result.UNKNOWN, sender(a, Send.LOSE_REPLY_AFTER_APPLY).dispatch(applied));
        state(applied.id(), "UNKNOWN", "UNKNOWN", 1, 1);
        var wrong = new AuthenticatedActor(OPERATOR.issuer(), new OrganizationId("b24-other"),
                OPERATOR.principal(), OPERATOR.kind(), OPERATOR.person());
        require(b.reconciler().recover(wrong, applied.id(), Action.RETRY) == DeliveryReconciler.Result.WITHHELD,
                "cross-tenant operator reached reconciliation");
        require(b.reconciler().recover(B10Postgresql.OPERATOR, applied.id(), Action.RETRY)
                        == DeliveryReconciler.Result.WITHHELD,
                "operator without current dispatch grant reached reconciliation");
        var exact = invocation(applied.id());
        var correct = b.connector().lookup(exact, false).orElseThrow();
        for (var quality : List.of(View.STALE, View.PARTIAL)) {
            require(b.connector().lookup(exact, false, quality).orElseThrow().effect().isPresent(),
                    "fault control did not present a tempting old or incomplete confirmation");
            recovered(reconciler(b, (invocation, fence) -> b.connector().lookup(invocation, fence, quality)),
                    applied.id(), Action.RETRY);
            state(applied.id(), "QUARANTINED", "QUARANTINED", 1, 1);
            require(b.coordinator().dispatchNext() == Result.IDLE, "unqualified lookup made a repeat eligible");
        }
        var wrongProjection = new Invocation(exact.id(), exact.ref(), exact.revision() + 1);
        var otherKey = new EffectKey(FENCED, exact.id().effect().tenant(), exact.id().effect().effectId());
        var wrongDestination = new Invocation(new AttemptId(otherKey, 1), exact.ref(), exact.revision());
        for (var mismatch : List.of(wrongProjection, wrongDestination)) {
            var invalid = new Evidence(mismatch, correct.endpoint(), correct.reference(), correct.observation(), correct.exclusion(),
                    correct.effect());
            recovered(reconciler(b, (invocation, fence) -> Optional.of(invalid)), applied.id(), Action.RECONCILE);
            state(applied.id(), "QUARANTINED", "QUARANTINED", 1, 1);
        }
        var foreignEndpoint = new Evidence(exact, UUID.randomUUID(), correct.reference(), correct.observation(),
                correct.exclusion(), correct.effect());
        recovered(reconciler(b, (invocation, fence) -> Optional.of(foreignEndpoint)), applied.id(), Action.RECONCILE);
        state(applied.id(), "QUARANTINED", "QUARANTINED", 1, 1);
        recovered(b.reconciler(), applied.id(), Action.RECONCILE);
        confirm(applied.id(), 1);

        var absent = claim(a, publish(mvc, UNSAFE));
        expect(Result.UNKNOWN, sender(a, Send.LOSE_REPLY_BEFORE_APPLY).dispatch(absent));
        state(absent.id(), "UNKNOWN", "UNKNOWN", 0, 1);
        recovered(b.reconciler(), absent.id(), Action.FENCE_AND_RETRY);
        recovered(b.reconciler(), absent.id(), Action.SUPPRESS);
        state(absent.id(), "QUARANTINED", "QUARANTINED", 0, 1);
        require(b.coordinator().dispatchNext() == Result.IDLE, "empty unfenced lookup permitted a repeat");
        System.out.println("PASS lost applied reply recovered; stale, partial, wrong scope and unfenced absence stayed quarantined");
    }

    private void rejectionAndQuarantine(B08MvcSession mvc) throws Exception {
        var transientFailure = claim(a, publish(mvc, UNSAFE));
        expect(Result.RETRYABLE, sender(a, Send.THROTTLE).dispatch(transientFailure));
        state(transientFailure.id(), "RETRYABLE", "RETRYABLE", 0, 1);
        require(Boolean.TRUE.equals(sql.queryForObject("SELECT deferred_until IS NOT NULL FROM requests.outbox WHERE "
                + KEY, Boolean.class, args(transientFailure.id().effect()))), "safe transient lacked persisted deferral");
        releaseDeferral(transientFailure.id().effect());
        var replacement = claim(b, transientFailure.id().effect());
        expect(Result.CONFIRMED, b.coordinator().dispatch(replacement));
        confirm(replacement.id(), 2);
        var permanentFailure = claim(a, publish(mvc, UNSAFE));
        expect(Result.FAILED_PERMANENT, sender(a, Send.REJECT).dispatch(permanentFailure));
        state(permanentFailure.id(), "FAILED_PERMANENT", "FAILED_PERMANENT", 0, 1);
        System.out.println("PASS proven no-effect throttle offered same-key repeat; permanent rejection terminal");
    }

    private void terminalProofWithUnarmedGap(B08MvcSession mvc, Send send, Observation observation, Result expected)
            throws Exception {
        var original = claim(a, publish(mvc, UNSAFE));
        expire(original.id());
        var terminal = claim(b, original.id().effect());
        require(terminal.id().number() == 2 && localState(original.id()).equals("EXPIRED_UNSENT"),
                "fixture did not produce a never-armed ordinal");
        expect(expected, sender(b, send).dispatch(terminal));
        assertGapProof(terminal.id(), List.of(2L), observation);
        state(terminal.id(), expected.name(), expected.name(), 0, 2);
        if (expected == Result.RETRYABLE) {
            releaseDeferral(original.id().effect());
            var replacement = claim(a, original.id().effect());
            expect(Result.CONFIRMED, a.coordinator().dispatch(replacement));
            confirm(replacement.id(), 3);
        } else {
            expect(Result.IDLE, a.coordinator().dispatchNext());
        }
        System.out.println("PASS " + observation + " no-apply closed the actually armed lineage across a never-armed ordinal");
    }

    private void terminalProofWithArmedGap(B08MvcSession mvc) throws Exception {
        var original = claim(a, publish(mvc, SINGLE));
        expect(Result.UNKNOWN, sender(a, Send.LOSE_REPLY_BEFORE_APPLY).dispatch(original));
        recovered(b.reconciler(), original.id(), Action.RETRY);
        releaseDeferral(original.id().effect());
        var transientFailure = claim(b, original.id().effect());
        require(transientFailure.id().number() == 2, "fixture did not issue the authorized same-key replacement");
        // The original repeat used valid single-effect evidence. Omit that guarantee from the replacement's report.
        var noGuarantee = coordinator(b, invocation -> b.connector().send(invocation, Send.THROTTLE)
                .map(evidence -> new Evidence(invocation, evidence.endpoint(), evidence.reference(),
                        evidence.observation(), Exclusion.NONE, evidence.effect(), evidence.coverage(),
                        evidence.noApplication())));
        expect(Result.UNKNOWN, noGuarantee.dispatch(transientFailure));
        assertGapProof(transientFailure.id(), List.of(1L, 2L), Observation.TRANSIENT);
        state(transientFailure.id(), "UNKNOWN", "UNKNOWN", 0, 2);
        expect(Result.IDLE, a.coordinator().dispatchNext());
        a.connector().send(invocation(original.id()), Send.LOSE_REPLY_AFTER_APPLY);
        require(remoteState(original.id()).equals("APPLIED"), "old authorized invocation did not arrive");
        recovered(a.reconciler(), transientFailure.id(), Action.RECONCILE);
        confirm(transientFailure.id(), 2);
        System.out.println("PASS terminal no-apply for the replacement held a genuinely armed unobserved predecessor");
    }

    private void assertGapProof(AttemptId id, List<Long> armed, Observation observation) {
        var key = id.effect();
        require(sql.queryForList("SELECT attempt_no FROM requests.outbox_attempt WHERE armed_at IS NOT NULL AND "
                + KEY + " ORDER BY attempt_no", Long.class, args(key)).equals(armed), "unexpected durable arm history");
        var proof = sql.queryForObject("SELECT delivery_observation FROM requests.outbox_attempt WHERE attempt_no = ? AND "
                + KEY, UUID.class, id.number(), key.destination(), key.tenant().value(), key.effectId());
        require(Boolean.TRUE.equals(remote.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM synthetic_delivery.delivery_observation
                    WHERE proof_id = ? AND attempt_no = ? AND observation = ?
                        AND fenced_through = 0 AND terminal_attempts = ARRAY[?::bigint] AND
                """ + KEY + ")", Boolean.class, proof, id.number(), observation.name(), id.number(), key.destination(),
                key.tenant().value(), key.effectId())), "current no-apply lacked exact scoped terminal proof");
    }

    private void delayed(B08MvcSession mvc, String destination, boolean oldFirst) throws Exception {
        var old = claim(a, publish(mvc, destination));
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var blocked = coordinator(a, invocation -> {
            checkOutsideTransaction();
            require(localState(invocation.id()).equals("ARMED") && countEffects(invocation.id().effect()) == 0,
                    "old call crossed the remote cut without committed ARMED evidence");
            paused.countDown();
            try {
                await(resume);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return a.connector().send(invocation, Send.LOSE_REPLY_AFTER_APPLY);
        });
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldResult = tasks.submit(() -> blocked.dispatch(old));
            try {
                await(paused);
                expire(old.id());
                boolean oldBeforeFence = oldFirst && destination.equals(FENCED);
                if (oldBeforeFence) {
                    resume.countDown();
                    expect(Result.STALE, oldResult.get(10, TimeUnit.SECONDS));
                }
                var action = destination.equals(FENCED) ? Action.FENCE_AND_RETRY : Action.RETRY;
                recovered(b.reconciler(), old.id(), action);
                if (oldBeforeFence) {
                    confirm(old.id(), 1);
                    require(b.coordinator().dispatchNext() == Result.IDLE, "new arrival repeated a confirmed old effect");
                } else {
                    state(old.id(), "RETRYABLE", "RETRYABLE", 0, 1);
                    if (oldFirst) {
                        resume.countDown();
                        expect(Result.STALE, oldResult.get(10, TimeUnit.SECONDS));
                    }
                    releaseDeferral(old.id().effect());
                    var replacement = claim(b, old.id().effect());
                    expect(Result.CONFIRMED, b.coordinator().dispatch(replacement));
                    resume.countDown();
                    expect(Result.STALE, oldResult.get(10, TimeUnit.SECONDS));
                    confirm(replacement.id(), 2);
                    if (oldFirst) {
                        require(remoteState(replacement.id()).equals("DEDUPED")
                                && remoteState(old.id()).equals("APPLIED"),
                                "replacement did not dedupe against the actual older application");
                    } else {
                        require(remoteState(old.id()).equals(destination.equals(FENCED) ? "FENCED" : "DEDUPED"),
                                "late original did not traverse the decisive fence or stable-key gate");
                    }
                }
            } finally {
                resume.countDown();
            }
        }
        require(countEffects(old.id().effect()) == 1, "delayed old call produced a duplicate remote effect");
        System.out.println("PASS delayed sender versus " + destination + " gate, old first=" + oldFirst);
    }

    private void fencePendingAndSuppress(B08MvcSession mvc) throws Exception {
        var claim = claim(a, publish(mvc, FENCED));
        expect(Result.ACCEPTED_PENDING, sender(a, Send.ACCEPT).dispatch(claim));
        var invocation = invocation(claim.id());
        recovered(b.reconciler(), claim.id(), Action.FENCE_AND_SUPPRESS);
        a.connector().complete(invocation);
        state(claim.id(), "SUPPRESSED", "SUPPRESSED", 0, 1);
        require(remoteState(claim.id()).equals("FENCED"), "fence failed to exclude in-flight async completion");
        System.out.println("PASS authoritative post-fence absence suppressed one already accepted job without late apply");
    }

    private void authorityCuts(B08MvcSession mvc) throws Exception {
        var first = claim(a, publish(mvc, SINGLE));
        var revokedWhileSending = coordinator(a, invocation -> {
            var receipt = a.connector().send(invocation);
            revoke(ROUTE);
            return receipt;
        });
        expect(Result.UNKNOWN, revokedWhileSending.dispatch(first));
        state(first.id(), "UNKNOWN", "UNKNOWN", 1, 1);
        require(b.reconciler().recover(OPERATOR, first.id(), Action.RECONCILE) == DeliveryReconciler.Result.WITHHELD,
                "operator reconciled after the route lost current scoped authority");
        grant(ROUTE);
        var revokedWhileLooking = reconciler(b, (invocation, fence) -> {
            var evidence = b.connector().lookup(invocation, fence);
            revoke(OPERATOR);
            return evidence;
        });
        require(revokedWhileLooking.recover(OPERATOR, first.id(), Action.RECONCILE) == DeliveryReconciler.Result.WITHHELD,
                "revocation between operator proof and finalization did not win");
        state(first.id(), "UNKNOWN", "UNKNOWN", 1, 1);
        require(sql.queryForObject("SELECT count(*) FROM requests.outbox_delivery_recovery WHERE " + KEY
                + " AND disposition = 'WITHHELD' AND observation_ref IS NULL", Long.class, args(first.id().effect())) == 1,
                "withheld operator outcome was not safely audited");
        grant(OPERATOR);
        recovered(b.reconciler(), first.id(), Action.RECONCILE);
        confirm(first.id(), 1);

        var unsent = claim(a, publish(mvc, SINGLE));
        revoke(ROUTE);
        expect(Result.SUPPRESSED, a.coordinator().dispatch(unsent));
        state(unsent.id(), "SUPPRESSED", "SUPPRESSED", 0, 1);
        grant(ROUTE);
        System.out.println("PASS current route and operator revocations held proof after I/O; prior route revocation blocked send");
    }

    private void retryBindings(B08MvcSession mvc) throws Exception {
        var old = claim(a, publish(mvc, SINGLE));
        expect(Result.UNKNOWN, sender(a, Send.LOSE_REPLY_BEFORE_APPLY).dispatch(old));
        recovered(b.reconciler(), old.id(), Action.RETRY);
        var effect = old.id().effect();
        releaseDeferral(effect);
        var originalEndpoint = remote.queryForObject("SELECT identity FROM synthetic_delivery.endpoint", UUID.class);
        require(remote.update("UPDATE synthetic_delivery.endpoint SET identity = ?", UUID.randomUUID()) == 1,
                "fixture could not simulate a changed endpoint");
        try {
            var differentEndpoint = worker(sourceB);
            expect(Result.IDLE, differentEndpoint.coordinator().dispatchNext());
            require(differentEndpoint.reconciler().recover(OPERATOR, old.id(), Action.RETRY)
                    == DeliveryReconciler.Result.WITHHELD, "a newly configured endpoint adopted the old retry");
        } finally {
            remote.update("UPDATE synthetic_delivery.endpoint SET identity = ?", originalEndpoint);
        }
        var original = invocation(old.id());
        a.connector().send(original, Send.LOSE_REPLY_AFTER_APPLY);
        var replacement = claim(b, effect);
        revoke(ROUTE);
        expect(Result.DEFERRED, b.coordinator().dispatch(replacement));
        state(replacement.id(), "RETRYABLE", "DEFERRED", 1, 2);
        grant(ROUTE);
        recovered(b.reconciler(), old.id(), Action.RECONCILE);
        confirm(old.id(), 2);
        System.out.println("PASS proved repeat stayed with its original endpoint; revoked replacement could not erase old uncertainty");
    }

    private void recorderCompatibility(B08MvcSession mvc) throws Exception {
        var id = new AttemptId(publish(mvc, SINGLE), 1);
        var proofId = UUID.randomUUID();
        var legacy = SyntheticDispatchConfiguration.worker(settings, sourceB, proof, List.of(new Route(SINGLE, ROUTE, TYPE)),
                Duration.ofSeconds(30), Duration.ofMillis(200), invocation -> {
                    checkOutsideTransaction();
                    require(id.equals(invocation.id()), "legacy recorder received the wrong invocation");
                    return new Receipt(id, proofId);
                });
        expect(Result.CONFIRMED, legacy.coordinator().dispatchNext());
        state(id, "CONFIRMED", "CONFIRMED", 0, 1);
        var key = id.effect();
        var actual = sql.queryForObject("SELECT confirmation_ref FROM requests.outbox_attempt WHERE attempt_no = ? AND "
                + KEY, UUID.class, id.number(), key.destination(), key.tenant().value(), key.effectId());
        require(proofId.equals(actual) && mapping(id) == null, "worker upgrade changed the legacy recorder receipt");
        System.out.println("PASS existing B23 recorder route retains its original completion contract on the additive schema");
    }

    private EffectKey publish(B08MvcSession mvc, String destination) throws Exception {
        var ref = base.accepted(mvc, TYPE);
        var bearer = base.publisherBearer;
        var result = B17Postgresql.call(mvc, "/requests/" + ref.value() + "/actions/publish", bearer,
                B17Postgresql.issue(mvc, bearer), Map.of("expectedRevision",
                        B17Postgresql.observation(mvc, ref, "publish", bearer)));
        require(result.status() == 503, "fixture Publish did not stop at its proof boundary");
        var original = base.binding(TYPE, ref);
        base.confirm(original);
        base.baseline.confirm(base.scopeWitness(ref));
        var current = new EffectKey(SINGLE, original.attempt().organization(), OutboundObligation.effectIdentity(
                original.attempt().receiptIdentity(), OutboundObligation.PUBLISH_REFERENCE_SLOT));
        if (destination.equals(SINGLE)) {
            return current;
        }
        var copy = copy(current, destination);
        require(sql.update("DELETE FROM requests.outbox WHERE " + KEY, args(current)) == 1,
                "fixture did not isolate the chosen destination envelope");
        return copy;
    }

    private EffectKey copy(EffectKey key, String destination) {
        var exact = new EffectKey(destination, key.tenant(), key.effectId());
        require(sql.update("""
                INSERT INTO requests.outbox (destination, tenant, effect_id, request_ref, request_revision,
                    trigger_slot, operation, schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                SELECT ?, tenant, effect_id, request_ref, request_revision, trigger_slot, operation, 1,
                    'publish-request', scheduled_epoch_second, scheduled_nano FROM requests.outbox WHERE
                """ + KEY, destination, key.destination(), key.tenant().value(), key.effectId()) == 1,
                "fixture did not install the exact retained destination");
        return exact;
    }

    private DispatchCoordinator sender(DeliveryComponents worker, Send scenario) {
        return coordinator(worker, invocation -> worker.connector().send(invocation, scenario));
    }

    private DispatchCoordinator coordinator(DeliveryComponents worker, Function<Invocation, Optional<Evidence>> send) {
        return new DispatchCoordinator(worker.store(), writer, connector(send, worker.connector()::lookup));
    }

    private DeliveryReconciler reconciler(DeliveryComponents worker,
                                          BiFunction<Invocation, Boolean, Optional<Evidence>> lookup) {
        return new DeliveryReconciler(worker.store(), writer, connector(worker.connector()::send, lookup));
    }

    private static Connector connector(Function<Invocation, Optional<Evidence>> send,
                                       BiFunction<Invocation, Boolean, Optional<Evidence>> lookup) {
        return new Connector() {
            @Override
            public Optional<Evidence> send(Invocation invocation) {
                checkOutsideTransaction();
                return send.apply(invocation);
            }

            @Override
            public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
                checkOutsideTransaction();
                return lookup.apply(invocation, fence);
            }
        };
    }

    private Claim claim(DeliveryComponents worker, EffectKey effect) {
        var claim = worker.store().claim(writer).orElseThrow(() -> new AssertionError("missing scoped claim"));
        require(claim.id().effect().equals(effect), "claimed a different scoped obligation");
        return claim;
    }

    private void expire(AttemptId wanted) {
        var key = wanted.effect();
        require(sql.update("""
                UPDATE requests.outbox_attempt SET claimed_at = statement_timestamp() - interval '2 seconds',
                    lease_expires_at = statement_timestamp() - interval '1 second' WHERE attempt_no = ? AND
                """ + KEY, wanted.number(), key.destination(), key.tenant().value(), key.effectId()) == 1,
                "fixture did not expire the intended armed lease");
    }

    private void releaseDeferral(EffectKey key) {
        require(sql.update("UPDATE requests.outbox SET deferred_until = NULL WHERE " + KEY, args(key)) == 1,
                "fixture did not release the intended deferral");
    }

    private void state(AttemptId id, String parent, String attempt, long effects, long localAttempts) {
        require(parent.equals(sql.queryForObject("SELECT status FROM requests.outbox WHERE " + KEY, String.class,
                        args(id.effect()))) && attempt.equals(localState(id)) && countEffects(id.effect()) == effects
                        && sql.queryForObject("SELECT count(*) FROM requests.outbox_attempt WHERE " + KEY,
                                Long.class, args(id.effect())) == localAttempts,
                "local parent/attempt or remote count differs from " + parent + "/" + attempt + "/" + effects
                        + " with " + localAttempts + " local attempts");
    }

    private void confirm(AttemptId id, long localAttempts) {
        state(id, "CONFIRMED", "CONFIRMED", 1, localAttempts);
        var key = id.effect();
        var proof = sql.queryForObject("SELECT confirmation_ref FROM requests.outbox_attempt WHERE attempt_no = ? AND "
                + KEY, UUID.class, id.number(), key.destination(), key.tenant().value(), key.effectId());
        require(Boolean.TRUE.equals(remote.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM synthetic_delivery.delivery_observation
                    WHERE proof_id = ? AND attempt_no = ? AND observation = 'CONFIRMED' AND effect_ref = ? AND
                """ + KEY + ")", Boolean.class, proof, id.number(), mapping(id), key.destination(),
                key.tenant().value(), key.effectId())), "local confirmation lacked exact persisted remote proof");
    }

    private UUID mapping(AttemptId id) {
        var key = id.effect();
        return sql.queryForObject("SELECT remote_effect FROM requests.outbox_attempt WHERE attempt_no = ? AND " + KEY,
                UUID.class, id.number(), key.destination(), key.tenant().value(), key.effectId());
    }

    private Invocation invocation(AttemptId id) {
        return sql.queryForObject("SELECT request_ref, request_revision FROM requests.outbox WHERE " + KEY,
                (row, index) -> new Invocation(id, new com.company.requests.domain.RequestRef(row.getString(1)),
                        row.getLong(2)), args(id.effect()));
    }

    private long countEffects(EffectKey key) {
        return remote.queryForObject("""
                SELECT count(*) FROM synthetic_delivery.delivery_attempt WHERE effect_ref IS NOT NULL AND
                """ + KEY, Long.class, args(key));
    }

    private String localState(AttemptId id) {
        return attemptState(sql, "requests.outbox_attempt", id);
    }

    private String remoteState(AttemptId id) {
        return attemptState(remote, "synthetic_delivery.delivery_attempt", id);
    }

    private static String attemptState(JdbcTemplate reader, String table, AttemptId id) {
        var key = id.effect();
        return reader.queryForObject("SELECT state FROM " + table + " WHERE attempt_no = ? AND " + KEY, String.class,
                id.number(), key.destination(), key.tenant().value(), key.effectId());
    }

    private void grant(AuthenticatedActor actor) {
        base.enable(actor, TYPE, Grant.DISPATCH_PUBLISH_REFERENCE);
    }

    private void revoke(AuthenticatedActor actor) {
        base.baseline.confirm(base.profile.updates().stageRevocation(B10Postgresql.OPERATOR,
                new GrantKey(actor, Grant.DISPATCH_PUBLISH_REFERENCE, Optional.of(TYPE))));
    }

    private static void recovered(DeliveryReconciler reconciler, AttemptId id, Action action) {
        require(reconciler.recover(OPERATOR, id, action) == DeliveryReconciler.Result.RECORDED,
                "authorized recovery did not record its safe disposition");
    }

    private static void checkOutsideTransaction() {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isSynchronizationActive(), "remote call inherited an open transaction");
    }

    private static Object[] args(EffectKey key) {
        return new Object[]{key.destination(), key.tenant().value(), key.effectId()};
    }

    private static void expect(Result expected, Result observed) {
        require(expected == observed, "expected " + expected + ", observed " + observed);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(10, TimeUnit.SECONDS), "timed out waiting at an owned delay gate");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
