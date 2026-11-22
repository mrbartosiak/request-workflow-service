import com.company.requests.adapter.jdbc.JdbcOutboxAppender;
import com.company.requests.adapter.jdbc.JdbcOutboxMigration;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.PublishOutboxTrigger;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Disposable PostgreSQL command-connection and retained-envelope checks. */
public final class B22Postgresql {
    private static final RequestType ENABLED_TYPE = RequestType.HR_TICKET;
    private static final RequestType OTHER_TYPE = RequestType.BUG_REPORT;
    private static final String DESTINATION = "fixture-publish";
    private static final String SECOND_DESTINATION = "alternate-publish";
    private static final String APPEND_SQL = "INSERT INTO requests.outbox";
    private static final Instant RETAINED_SCHEDULE = Instant.ofEpochSecond(17, 123456789);
    private static final JsonMapper JSON = new JsonMapper();

    private final B17Postgresql base;
    private final JdbcTemplate sql;
    private final JdbcTemplate authority;
    private final DataSource command;
    private final List<AppendAnchor> appends = new ArrayList<>();
    private boolean failAfterAppend;

    private B22Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        base = new B17Postgresql(workflow, proof, projector, resolver, createProof, logins, sources, this::observe);
        sql = base.sql;
        authority = new JdbcTemplate(B08MvcSession.dataSource(proof.migration()));
        command = base.settings.create().command();
    }

    public static void main(String[] args) {
        try {
            // Use the existing Publish-only launcher and its owned b17-* run directory checks.
            QualifyPostgresql.publishCommands(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B22Postgresql(workflow, proof, projector, resolver, createProof, logins, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B22 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise() throws Exception {
        enablePublishGrants();
        var disabledRef = publishWithoutTrigger(base.settings, ENABLED_TYPE);
        require(sql.queryForObject("SELECT to_regclass('requests.outbox') IS NULL", Boolean.class),
                "default Publish unexpectedly required the outbox schema");
        System.out.println("PASS default-off Publish commits with no outbox schema");

        var migration = new JdbcOutboxMigration(sql.getDataSource());
        migration.migrate();
        migration.migrate();
        sql.execute("""
                GRANT INSERT (destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                ON requests.outbox TO b04_workflow
                """);
        require(sql.queryForObject("""
                SELECT has_column_privilege('b04_workflow', 'requests.outbox', 'destination', 'INSERT')
                    AND NOT has_column_privilege('b04_workflow', 'requests.outbox', 'status', 'INSERT')
                    AND NOT has_table_privilege('b04_workflow', 'requests.outbox', 'SELECT,UPDATE,DELETE')
                """, Boolean.class), "workflow runtime should only have the outbox insert columns");
        require(count("outbox") == 0 && request(disabledRef).state().equals("PUBLISHED"),
                "owner migration rewrote a preexisting publication");
        System.out.println("PASS owner migration is repeatable on an existing publication and runtime access is append-only");

        var enabled = withDestination(DESTINATION);
        publishWithoutTrigger(enabled, OTHER_TYPE);
        require(count("outbox") == 0 && appends.isEmpty(), "unconfigured request type triggered an append");
        System.out.println("PASS enabled trigger excludes non-Publish commands and unconfigured request types");

        OutboundObligation committed;
        Intent intent;
        B08MvcSession.Reply result;
        try (var mvc = new B08MvcSession(enabled)) {
            var ref = base.accepted(mvc, ENABLED_TYPE);
            intent = new Intent(ref, B17Postgresql.issue(mvc, base.publisherBearer),
                    B17Postgresql.observation(mvc, ref, "publish", base.publisherBearer));
            var before = snapshot(ref);
            failAfterAppend = true;
            try {
                expectProblem(submit(mvc, intent), 500, "internal_error");
            } finally {
                failAfterAppend = false;
            }
            var rolledBack = base.binding(ENABLED_TYPE, ref);
            require(appends.size() == 1 && appends.getFirst().matches(rolledBack) && before.equals(snapshot(ref))
                    && rolledBack.state() == State.PENDING && rolledBack.allocation().isPresent(),
                    "fault after append changed local outbox, receipt, history, witnesses or request state");
            System.out.println("PASS real append affected one row on the original Publish backend and XID before the fault");
            System.out.println("PASS post-append rollback deltas outbox=0 receipt=0 fact=0 witness=0 scopeWitness=0 visibility=0; request unchanged");

            expectProblem(submit(mvc, intent), 503, "authority_unavailable");
            require(appends.size() == 1 && before.equals(snapshot(ref)), "unproved abort allowed another append");
            resolveAbort(rolledBack);
            expectProblem(submit(mvc, intent), 503, "authority_unavailable");
            var pending = base.binding(ENABLED_TYPE, ref);
            require(pending.state() == State.PENDING
                    && pending.allocation().orElseThrow().value() > rolledBack.allocation().orElseThrow().value()
                    && appends.size() == 2 && appends.getLast().matches(pending)
                    && appends.getLast().effectId().equals(appends.getFirst().effectId()),
                    "proven abort did not preserve effect identity while burning the allocation");
            require(snapshot(ref).equals(before.published(pending)), "committed Publish did not append exactly one of each local row");
            require(sameTransaction(ref), "outbox, request, fact, receipt and witness have different write XIDs");
            committed = read(DESTINATION, pending.attempt().organization(), appends.getLast().effectId());
            require(committed.ref().equals(ref) && committed.revision() == pending.attempt().resultRevision()
                    && committed.operation().equals("PUBLISH") && committed.schemaVersion() == 2
                    && committed.triggerSlot().equals("publish.reference")
                    && committed.projectionRef().equals("published-request-reference"),
                    "incorrect persisted current envelope metadata");
            System.out.println("PASS one committed outbox, receipt, transition, publication witness and applicable scope witness share the Publish XID");
            finish(pending, ENABLED_TYPE, ref);
            result = submit(mvc, intent);
            require(result.status() == 200, "fully confirmed original Publish was not released");
        }

        var beforeReplay = snapshot(intent.ref());
        try (var restarted = new B08MvcSession(withDestination(SECOND_DESTINATION))) {
            var replay = submit(restarted, intent);
            require(result.equals(replay) && appends.size() == 2 && beforeReplay.equals(snapshot(intent.ref()))
                    && committed.equals(read(committed.destination(), committed.tenant(), committed.effectId())),
                    "exact replay after restart and trigger reconfiguration changed the original effect");
        }
        System.out.println("PASS same-key replay after confirmation, restart and destination change preserved one immutable obligation");

        rejectOutsideCommand(committed);
        readRetainedAndCheckScope(committed, migration);
    }

    private void enablePublishGrants() {
        for (var type : List.of(ENABLED_TYPE, OTHER_TYPE)) {
            base.enable(B10Postgresql.OWNER, type, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY,
                    Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY);
            base.enable(B10Postgresql.ASSIGNEE, type, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME,
                    Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH,
                    Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID);
        }
    }

    private Settings withDestination(String destination) {
        var supported = base.settings;
        return new Settings(supported.create(), supported.preparation(), supported.originalObserver(), supported.projection(),
                supported.resolver(), supported.operators(), supported.observationKeys(), true,
                Optional.of(new PublishOutboxTrigger(ENABLED_TYPE, destination)));
    }

    private RequestRef publishWithoutTrigger(Settings settings, RequestType type) throws Exception {
        try (var mvc = new B08MvcSession(settings)) {
            var ref = base.accepted(mvc, type);
            var intent = new Intent(ref, B17Postgresql.issue(mvc, base.publisherBearer),
                    B17Postgresql.observation(mvc, ref, "publish", base.publisherBearer));
            expectProblem(submit(mvc, intent), 503, type == ENABLED_TYPE ? "authority_unavailable" : "outcome_unknown");
            finish(base.binding(type, ref), type, ref);
            require(submit(mvc, intent).status() == 200, "independent untriggered Publish did not confirm");
            return ref;
        }
    }

    private void finish(Entry entry, RequestType type, RequestRef ref) {
        base.confirm(entry);
        if (type == ENABLED_TYPE) {
            base.baseline.confirm(base.scopeWitness(ref));
        }
    }

    private void resolveAbort(Entry rolledBack) {
        var scopeAttempt = authority.queryForObject("""
                SELECT attempt_id FROM request_authority.scope_journal_attempt
                WHERE organization = ? AND state = 'PENDING' AND workflow_transaction = CAST(? AS xid8)
                """, UUID.class, rolledBack.attempt().organization().value(), rolledBack.attempt().workflowTransaction());
        base.baseline.abort(scopeAttempt);
        var settings = base.settings;
        base.profile.publicationResolver().orElseThrow().abort(
                new Admission(settings.effectivePolicy().genesis(), settings.create().writer()), rolledBack.attempt());
    }

    private boolean sameTransaction(RequestRef ref) {
        return sql.queryForObject("""
                SELECT o.xmin = r.xmin AND o.xmin = f.xmin AND o.xmin = k.xmin AND o.xmin = w.xmin
                    AND o.xmin = s.xmin AND o.xmin = v.xmin AND o.xmin = g.xmin
                    AND o.xmin::text::numeric = mod(w.workflow_transaction::text::numeric, 4294967296)
                FROM requests.outbox o
                JOIN requests.request r ON r.organization = o.tenant AND r.ref = o.request_ref
                JOIN requests.transition_fact f ON f.ref = r.ref AND f.revision = o.request_revision
                JOIN requests.operation_receipt k ON k.result_ref = r.ref AND k.result_action = 'PUBLISH'
                    AND k.result_revision = o.request_revision
                JOIN requests.publication_witness w ON w.ref = r.ref AND w.result_revision = o.request_revision
                JOIN requests.scope_authority_witness s ON s.workflow_transaction = w.workflow_transaction
                JOIN requests.request_visibility_fact v ON v.organization = r.organization AND v.ref = r.ref
                JOIN requests.policy_guard g ON g.organization = r.organization
                WHERE r.ref = ? AND o.status = 'READY'
                """, Boolean.class, ref.value());
    }

    private void rejectOutsideCommand(OutboundObligation obligation) {
        var appender = new JdbcOutboxAppender(command);
        expectUnowned(() -> appender.append(obligation));
        expectUnowned(() -> new TransactionTemplate(new JdbcTransactionManager(command))
                .executeWithoutResult(ignored -> appender.append(obligation)));
        expectUnowned(() -> JdbcTransactionRunner.forCreate(command).run(() -> {
            appender.append(obligation);
            return null;
        }));
        expectUnowned(() -> JdbcTransactionRunner.forCreate(command).run(() -> {
            new JdbcOutboxAppender(sql.getDataSource()).append(obligation);
            return null;
        }));
        require(count("outbox") == 1, "an unowned, unguarded or cross-source append wrote an obligation");
        System.out.println("PASS appender refuses autocommit, ambient transaction, missing scoped gate and other data source");
    }

    private void readRetainedAndCheckScope(OutboundObligation original, JdbcOutboxMigration migration) {
        insertFixture(SECOND_DESTINATION, original.tenant(), original.effectId(), original.ref(), 1, "publish-request");
        migration.migrate();
        var retained = read(SECOND_DESTINATION, original.tenant(), original.effectId());
        require(retained.schemaVersion() == 1 && retained.triggerSlot().equals(original.triggerSlot())
                && retained.ref().equals(original.ref()) && retained.scheduledAt().equals(RETAINED_SCHEDULE),
                "retained v1 envelope did not round-trip with its exact schedule and trigger slot");

        var otherTenant = new OrganizationId("outbox-fixture-other-tenant");
        var otherRef = new RequestRef("outbox-fixture-other-ref");
        expectSqlState("23503", () -> insertFixture(DESTINATION, otherTenant, original.effectId(), original.ref(), 1,
                "publish-request"));
        seedOtherTenant(original, otherTenant, otherRef);
        insertFixture(DESTINATION, otherTenant, original.effectId(), otherRef, 1, "publish-request");
        require(read(DESTINATION, otherTenant, original.effectId()).ref().equals(otherRef)
                && original.equals(read(DESTINATION, original.tenant(), original.effectId())) && count("outbox") == 3,
                "same effect ID collided across isolated destination or tenant scopes");
        expectSqlState("23505", () -> insertFixture(DESTINATION, otherTenant, original.effectId(), otherRef, 1,
                "publish-request"));
        expectSqlState("23505", () -> insertFixture(DESTINATION, otherTenant, "3".repeat(64), otherRef, 1,
                "publish-request"));
        expectSqlState("23514", () -> insertFixture("rejected", otherTenant, original.effectId(), otherRef, 2,
                "Exact synthetic body"));
        expectSqlState("23514", () -> insertFixture("rejected", otherTenant, original.effectId(), otherRef, 99,
                "publish-request"));
        require(sql.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'requests' AND table_name = 'outbox' ORDER BY ordinal_position
                """, String.class).equals(List.of("destination", "tenant", "effect_id", "trigger_slot", "request_ref",
                "request_revision", "operation", "schema_version", "projection_ref", "scheduled_epoch_second",
                "scheduled_nano", "status")), "outbox contains fields outside the approved projection envelope");
        System.out.println("PASS retained v1 projection and nanosecond schedule survive migration and typed reading");
        System.out.println("PASS full scoped uniqueness, cross-tenant reference rejection and strict database projection allowlist");
    }

    private void seedOtherTenant(OutboundObligation original, OrganizationId tenant, RequestRef ref) {
        new JdbcTransactionRunner(sql.getDataSource()).run(() -> {
            sql.update("INSERT INTO requests.policy_guard (organization, policy_version) VALUES (?, 1)", tenant.value());
            sql.update("""
                    INSERT INTO requests.request (ref, organization, request_type, originating_person_id, name,
                        created_epoch, created_ordinal, state, revision, last_sequence, publication_namespace, publication_id)
                    SELECT ?, ?, request_type, originating_person_id, 'synthetic', created_epoch, created_ordinal + 1000,
                        state, revision, last_sequence, publication_namespace, publication_id + 1000
                    FROM requests.request WHERE ref = ?
                    """, ref.value(), tenant.value(), original.ref().value());
            sql.update("""
                    INSERT INTO requests.transition_fact (ref, organization, sequence, revision, action, from_state,
                        to_state, recorded_epoch_second, recorded_nano, actor_ref)
                    SELECT ?, ?, sequence, revision, action, from_state, to_state, recorded_epoch_second,
                        recorded_nano, actor_ref FROM requests.transition_fact WHERE ref = ? ORDER BY sequence
                    """, ref.value(), tenant.value(), original.ref().value());
            return null;
        });
    }

    private void insertFixture(String destination, OrganizationId tenant, String effectId, RequestRef ref,
                               int version, String projection) {
        sql.update("""
                INSERT INTO requests.outbox (destination, tenant, effect_id, trigger_slot, request_ref, request_revision,
                    operation, schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                VALUES (?, ?, ?, 'publish.reference', ?, 4, 'PUBLISH', ?, ?, ?, ?)
                """, destination, tenant.value(), effectId, ref.value(), version, projection,
                RETAINED_SCHEDULE.getEpochSecond(), RETAINED_SCHEDULE.getNano());
    }

    private OutboundObligation read(String destination, OrganizationId tenant, String effectId) {
        return sql.queryForObject("""
                SELECT destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano
                FROM requests.outbox WHERE destination = ? AND tenant = ? AND effect_id = ?
                """, (row, index) -> new OutboundObligation(row.getString(1), new OrganizationId(row.getString(2)),
                row.getString(3), row.getString(4), new RequestRef(row.getString(5)), row.getLong(6), row.getString(7),
                row.getInt(8), row.getString(9), Instant.ofEpochSecond(row.getLong(10), row.getInt(11))),
                destination, tenant.value(), effectId);
    }

    private B08MvcSession.Reply submit(B08MvcSession mvc, Intent intent) throws Exception {
        return B17Postgresql.call(mvc, "/requests/" + intent.ref().value() + "/actions/publish", base.publisherBearer,
                intent.key(), Map.of("expectedRevision", intent.observation()));
    }

    private RequestState request(RequestRef ref) {
        return sql.queryForObject("""
                SELECT state, revision, last_sequence, publication_id FROM requests.request WHERE ref = ?
                """, (row, index) -> new RequestState(row.getString(1), row.getLong(2), row.getLong(3),
                row.getObject(4, Long.class)), ref.value());
    }

    private Snapshot snapshot(RequestRef ref) {
        long policyVersion = sql.queryForObject("""
                SELECT g.policy_version FROM requests.policy_guard g JOIN requests.request r USING (organization)
                WHERE r.ref = ?
                """, Long.class, ref.value());
        return new Snapshot(count("outbox"), count("operation_receipt"), count("transition_fact"),
                count("publication_witness"), count("scope_authority_witness"), count("request_visibility_fact"),
                policyVersion, request(ref));
    }

    private long count(String table) {
        return sql.queryForObject("SELECT count(*) FROM requests." + table, Long.class);
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String statement
                            && statement.stripLeading().startsWith(APPEND_SQL)) {
                        return observeAppend(connection, (PreparedStatement) result);
                    }
                    return result;
                });
    }

    private PreparedStatement observeAppend(Connection connection, PreparedStatement statement) {
        String[] effectId = {null};
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    Object result = invoke(statement, method, args);
                    if ((method.getName().equals("setString") || method.getName().equals("setObject"))
                            && args[0].equals(3) && args[1] instanceof String boundEffect) {
                        effectId[0] = boundEffect;
                    }
                    if (method.getName().equals("executeUpdate")) {
                        require(result.equals(1) && !connection.getAutoCommit() && effectId[0] != null,
                                "append did not write exactly one row in its command transaction");
                        try (var probe = connection.createStatement(); var row = probe.executeQuery(
                                "SELECT pg_backend_pid(), pg_current_xact_id()::text")) {
                            require(row.next(), "append transaction identity missing");
                            appends.add(new AppendAnchor(row.getInt(1), row.getString(2), effectId[0]));
                        }
                        if (failAfterAppend) {
                            throw new SQLException("Synthetic fault after successful append", "22000");
                        }
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void expectProblem(B08MvcSession.Reply reply, int status, String code) {
        require(reply.status() == status, "expected " + status + " " + code + ", got " + reply.status());
        var object = JSON.readTree(reply.body());
        require(object.get("type").asString().equals("urn:requests:problem:" + code) && !object.has("publicationId"),
                "wrong or protected problem fields for " + code);
    }

    private static void expectUnowned(Runnable append) {
        try {
            append.run();
            throw new AssertionError("unowned appender call was admitted");
        } catch (IllegalStateException expected) {
            // Rejected before the statement can acquire or use an independent connection.
        }
    }

    private static void expectSqlState(String state, Runnable write) {
        try {
            write.run();
            throw new AssertionError("expected PostgreSQL to reject outbox row with " + state);
        } catch (DataAccessException failure) {
            require(failure.getMostSpecificCause() instanceof SQLException sql && state.equals(sql.getSQLState()),
                    "unexpected database rejection for outbox row, expected " + state);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private record Intent(RequestRef ref, String key, String observation) { }

    private record AppendAnchor(int backend, String xid, String effectId) {
        boolean matches(Entry entry) {
            return backend == entry.attempt().backendPid() && xid.equals(entry.attempt().workflowTransaction());
        }
    }

    private record RequestState(String state, long revision, long sequence, Long publicationId) { }

    private record Snapshot(long obligations, long receipts, long facts, long publicationWitnesses,
                            long scopeWitnesses, long visibilityFacts, long policyVersion, RequestState request) {
        Snapshot published(Entry pending) {
            return new Snapshot(obligations + 1, receipts + 1, facts + 1, publicationWitnesses + 1, scopeWitnesses + 1,
                    visibilityFacts + 1, policyVersion + 1,
                    new RequestState("PUBLISHED", request.revision() + 1, request.sequence() + 1,
                            pending.allocation().orElseThrow().value()));
        }
    }
}
