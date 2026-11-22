import com.company.requests.adapter.integration.jdbc.JdbcDispatchSchedulingMigration;
import com.company.requests.adapter.integration.jdbc.JdbcOutboxWorkerMigration;
import com.company.requests.adapter.integration.jdbc.JdbcSyntheticDeliveryMigration;
import com.company.requests.adapter.jdbc.JdbcOutboxMigration;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.OutboundObligation;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration.Route;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.SyntheticDelivery.Contract;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns the B25 publication, database privileges, synthetic endpoint and worker topology. */
public final class B25Postgresql implements AutoCloseable {
    static final String SLOW = "b25-slow", OUTAGE = "b25-outage", FAST = "b25-fast", FILLER = "b25-filler";
    static final String RETRY = "b25-retry", THROTTLE = "b25-throttle", UNSAFE = "b25-unsafe";
    static final String KEY = "destination = ? AND tenant = ? AND effect_id = ?";
    static final RequestType TYPE = RequestType.HR_TICKET;
    static final Map<String, Contract> CONTRACTS = Map.of(SLOW, Contract.SINGLE_EFFECT,
            OUTAGE, Contract.UNSAFE, FAST, Contract.SINGLE_EFFECT, FILLER, Contract.SINGLE_EFFECT,
            RETRY, Contract.UNSAFE, THROTTLE, Contract.SINGLE_EFFECT, UNSAFE, Contract.UNSAFE);

    final B17Postgresql base;
    final JdbcTemplate sql;
    final B25RemoteDatabase remote;
    final B25Profile profile;
    private final Map<String, B08MvcSession> mvc = new HashMap<>();

    private B25Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections authority,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins release,
                          B10FixtureSources sources) throws Exception {
        base = new B17Postgresql(workflow, authority, projector, resolver, createProof, release, sources,
                connection -> connection);
        sql = base.sql;
        remote = new B25RemoteDatabase(authority.migration());
        provision(new JdbcTemplate(B08MvcSession.dataSource(authority.migration())));
        profile = B25Profile.prepare(workflow, authority, base, remote.sendPassword);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.dispatchWorker(args, (workflow, proof, projector, resolver, create, release, workers) -> {
                B25Pressure pressure;
                try (var sources = new B10FixtureSources();
                     var fixture = new B25Postgresql(workflow, proof, projector, resolver, create, release, sources)) {
                    var run = Path.of(args[2]).toRealPath().resolve(args[3]).toRealPath();
                    pressure = B25Pressure.prepare(fixture, run);
                }
                pressure.exercise();
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                var detail = switch (cause) {
                    case SQLException db -> " SQLSTATE " + db.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B25 " + cause.getClass().getSimpleName() + detail);
                java.util.Arrays.stream(cause.getStackTrace()).limit(10)
                        .forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    B08MvcSession mvc(String destination) {
        return mvc.computeIfAbsent(destination, route -> new B08MvcSession(settings(route)));
    }

    private Settings settings(String destination) {
        return profile.parentSettings(base.settings, destination);
    }

    static List<Route> routes() {
        return CONTRACTS.keySet().stream().map(route -> new Route(route, B10Postgresql.ASSIGNEE, TYPE)).toList();
    }

    EffectKey publish(String destination) throws Exception {
        var session = mvc(destination);
        var ref = base.accepted(session, TYPE);
        var bearer = base.publisherBearer;
        var result = B17Postgresql.call(session, "/requests/" + ref.value() + "/actions/publish", bearer,
                B17Postgresql.issue(session, bearer),
                Map.of("expectedRevision", B17Postgresql.observation(session, ref, "publish", bearer)));
        require(result.status() == 503, "Publish did not stop at its proof boundary");
        var original = base.binding(TYPE, ref);
        base.confirm(original);
        base.baseline.confirm(base.scopeWitness(ref));
        return new EffectKey(destination, original.attempt().organization(), OutboundObligation.effectIdentity(
                original.attempt().receiptIdentity(), OutboundObligation.PUBLISH_REFERENCE_SLOT));
    }

    @Override
    public void close() {
        mvc.values().forEach(B08MvcSession::close);
    }

    static Object[] args(EffectKey key) {
        return new Object[]{key.destination(), key.tenant().value(), key.effectId()};
    }

    static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private void provision(JdbcTemplate authority) {
        var owner = sql.getDataSource();
        new JdbcWorkflowMigrations(owner).migrateDispatchGrants();
        new JdbcOutboxMigration(owner).migrate();
        JdbcOutboxWorkerMigration.migrate(owner);
        JdbcSyntheticDeliveryMigration.workflow(owner);
        JdbcDispatchSchedulingMigration.migrate(owner);
        JdbcDispatchSchedulingMigration.migrate(owner);
        sql.execute("""
                GRANT INSERT (destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                    ON requests.outbox TO b04_workflow;
                GRANT USAGE ON SCHEMA requests TO b23_worker_a, b23_worker_b;
                GRANT SELECT ON requests.outbox, requests.outbox_attempt, requests.scope_authority_projection,
                    requests.scope_grant_override, requests.publication_confirmation, requests.outbox_delivery_recovery,
                    requests.outbox_dispatch_lane TO b23_worker_a, b23_worker_b;
                GRANT INSERT (destination, tenant), UPDATE (resume_at, last_claimed_at)
                    ON requests.outbox_dispatch_lane TO b23_worker_a, b23_worker_b;
                GRANT USAGE ON SEQUENCE requests.outbox_dispatch_lane_lock_key_seq TO b23_worker_a, b23_worker_b;
                GRANT SELECT (organization, policy_version) ON requests.policy_guard TO b23_worker_a, b23_worker_b;
                GRANT SELECT (organization, ref, request_type, revision, state, publication_namespace, publication_id)
                    ON requests.request TO b23_worker_a, b23_worker_b;
                GRANT EXECUTE ON FUNCTION requests.lock_dispatch_guard(text) TO b23_worker_a, b23_worker_b;
                GRANT UPDATE (status, last_attempt_no, deferred_until, retry_uncertain, delivery_endpoint)
                    ON requests.outbox TO b23_worker_a, b23_worker_b;
                GRANT INSERT (destination, tenant, effect_id, attempt_no, claim_token, writer_id, generation,
                    claimed_at, lease_expires_at, state, scheduling_slot) ON requests.outbox_attempt TO
                    b23_worker_a, b23_worker_b;
                GRANT UPDATE (state, policy_version, publication_attempt, armed_at, finished_at, confirmation_ref,
                    delivery_observation, remote_effect, remote_attempt_no, recovery_token,
                    scheduling_invocation, invocation_quiesced_at)
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
        base.enable(B10Postgresql.OWNER, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY,
                Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY, Grant.DISPATCH_PUBLISH_REFERENCE);
        base.enable(B10Postgresql.ASSIGNEE, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME,
                Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH,
                Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID,
                Grant.DISPATCH_PUBLISH_REFERENCE);
    }
}
