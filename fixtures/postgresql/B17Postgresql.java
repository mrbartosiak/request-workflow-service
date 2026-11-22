import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcPublicationProvisioning;
import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.authority.JdbcReleaseEvidence.Input;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcPublicationWitnessMigration;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.EffectiveScopeUpdates.Permission;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Native Publish oracle. Database administration and fault controls are fixture-owned. */
public final class B17Postgresql {
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor PUBLISHER = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor OPERATOR = B10Postgresql.OPERATOR;
    private static final RequestType SCOPED = RequestType.HR_TICKET;
    private static final RequestType UNSCOPED = RequestType.BUG_REPORT;
    private static final long TWO_TO_53 = 9_007_199_254_740_992L;
    private static final String PROJECTION_COLUMNS = """
            (attempt_id, writer_id, generation, organization, request_type, ref, namespace,
             publication_id, result_revision, workflow_transaction, receipt_identity)
            """;
    private static final JsonMapper JSON = new JsonMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-11-22T21:00:00Z"));
    private final AtomicInteger preGateChecks = new AtomicInteger();
    final String ownerBearer = bearer();
    final String publisherBearer = bearer();
    private final String publisherAlias = bearer();
    private final DataSource proofOwner;
    private final JdbcTemplate authority;
    final JdbcTemplate sql;
    final B10Postgresql baseline;
    final SyntheticEffectiveAccessConfiguration.Settings settings;
    final SyntheticEffectiveAccessConfiguration.Components profile;
    private final Admission scope;
    private final JdbcPublicationJournal journal;
    private final JdbcWorkflowRecords guards;
    private final JdbcTransactionRunner transactions;

    private B17Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                          B10FixtureSources sources) throws SQLException {
        this(workflow, proof, projector, resolver, createProof, logins, sources, UnaryOperator.identity());
    }

    B17Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                   QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                   QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                   B10FixtureSources sources, UnaryOperator<Connection> observeCommand) throws SQLException {
        proofOwner = B08MvcSession.dataSource(proof.migration());
        authority = new JdbcTemplate(proofOwner);
        var workflowOwner = B08MvcSession.dataSource(workflow.migration());
        sql = new JdbcTemplate(workflowOwner);
        var policy = B10Postgresql.policy().withAssigneeOnlyOnPublish(Set.of(SCOPED));
        baseline = new B10Postgresql(workflow, proof, projector, resolver, createProof, sources,
                connection -> observeCommand.apply(observe(connection)), policy);
        provision(workflowOwner);
        var base = baseline.settings();
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", ownerBearer, OWNER),
                new SyntheticActorResolver.Binding("publisher", publisherBearer, PUBLISHER),
                new SyntheticActorResolver.Binding("publisher-alias", publisherAlias, PUBLISHER)));
        var replay = new SyntheticCreateConfiguration.GuardedReplay(OriginalReceiptProof.unavailable(),
                JdbcReleaseEvidence.preparation(B08MvcSession.dataSource(logins.preparation())),
                JdbcReleaseEvidence.handoffs(B08MvcSession.dataSource(logins.handoff())));
        var create = new SyntheticCreateConfiguration.Settings(base.create().command(), base.create().proof(),
                policy, base.create().writer(), actors, clock::get,
                new OperationSecrets(1, Map.of(1, secret()), 1, Map.of(1, secret())), Optional.of(replay));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(create, base.preparation(), base.originalObserver(),
                base.projection(), base.resolver(), base.operators(),
                Optional.of(new ObservationCodec.Keys(7, Map.of(7, secret()))), true);
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
        scope = new Admission(settings.effectivePolicy().genesis(), create.writer());
        journal = new JdbcPublicationJournal(base.preparation(), base.resolver(), base.originalObserver());
        guards = new JdbcWorkflowRecords(create.command());
        transactions = JdbcTransactionRunner.forCreate(create.command());
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.publishCommands(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B17Postgresql(workflow, proof, projector, resolver, createProof, logins, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B17 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void provision(DataSource workflowOwner) {
        var workflow = new JdbcWorkflowMigrations(workflowOwner);
        workflow.migrateTerminalRequestActions();
        workflow.migratePublishGrants();
        workflow.migratePublishGrants();
        var publication = new JdbcPublicationWitnessMigration(workflowOwner);
        publication.migrateConfirmations();
        publication.migrateConfirmations();
        sql.execute("""
                GRANT UPDATE (state, revision, last_sequence, publication_namespace, publication_id)
                    ON requests.request TO b04_workflow;
                GRANT UPDATE (body) ON requests.request_body TO b04_workflow;
                GRANT INSERT ON requests.transition_reason, requests.publication_witness TO b04_workflow;
                GRANT SELECT ON requests.publication_confirmation TO b04_workflow, b11_projection;
                GRANT SELECT ON requests.publication_target_evidence TO b04_observer;
                GRANT SELECT ON requests.publication_commit_evidence TO b04_observer, b11_projection;
                """);
        projectionGrant(true);
        var provisioning = new JdbcAuthorityProvisioning(proofOwner);
        provisioning.migrateReleaseEvidence();
        provisioning.authorizeReleaseEvidence("b12_prepare", OWNER.organization(), Input.PREPARATION);
        provisioning.authorizeReleaseEvidence("b12_handoff", OWNER.organization(), Input.HANDOFF);
        var journalSchema = new JdbcPublicationProvisioning(proofOwner);
        journalSchema.migrateResolver();
        journalSchema.migrateResolver();
        journalSchema.authorizeResolver("b11_resolver", OWNER.organization(), true, true);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b12_prepare, b12_handoff;
                GRANT EXECUTE ON FUNCTION request_authority.prepare_release(uuid, text, bytea, text, bigint, bigint,
                    bigint, uuid, bigint, text) TO b12_prepare;
                GRANT EXECUTE ON FUNCTION request_authority.record_release_evidence(uuid, text, bytea, text)
                    TO b12_handoff;
                GRANT SELECT ON request_authority.writer_epoch, request_authority.workflow_certificate,
                    request_authority.publication_namespace, request_authority.publication_attempt
                    TO b04_proof, b11_resolver;
                GRANT INSERT (attempt_id, namespace, generation, organization, request_type, ref, backend_pid,
                    workflow_transaction, receipt_identity, result_revision)
                    ON request_authority.publication_attempt TO b04_proof;
                GRANT EXECUTE ON FUNCTION request_authority.allocate_publication(uuid) TO b04_proof;
                GRANT EXECUTE ON FUNCTION request_authority.may_resolve_publication(uuid, text),
                    request_authority.finish_audited_publication(uuid, text) TO b11_resolver;
                """);
        require(Boolean.TRUE.equals(authority.queryForObject("""
                SELECT NOT has_function_privilege('b04_proof',
                    'request_authority.finish_audited_publication(uuid,text)', 'EXECUTE')
                    AND NOT has_function_privilege('b11_resolver',
                    'request_authority.finish_publication(uuid,text)', 'EXECUTE')
                """, Boolean.class)), "publication terminal role separation");
        authority.update("UPDATE request_authority.publication_namespace SET last_id = ?", TWO_TO_53);
    }

    private void exercise() throws Exception {
        for (RequestType type : List.of(SCOPED, UNSCOPED)) {
            enable(OWNER, type, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY,
                    Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY);
            enable(PUBLISHER, type, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME,
                    Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH,
                    Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID);
        }

        RequestRef first;
        RequestRef second;
        RequestRef rollback;
        RequestRef last;
        String previousBody;
        Intent firstIntent;
        try (var mvc = new B08MvcSession(settings)) {
            first = accepted(mvc, SCOPED);
            second = accepted(mvc, SCOPED);
            rollback = accepted(mvc, SCOPED);
            last = accepted(mvc, UNSCOPED);
            var key = issue(mvc, publisherBearer);
            var denied = call(mvc, route(first, "publish"), publisherBearer, key, Map.of("expectedRevision", "invalid"));
            problem(denied, 403, "action_denied");
            require(state(first).equals("ACCEPTED") && attempts() == 0, "publish independently gated execution");
            enable(PUBLISHER, SCOPED, Grant.EXECUTE_PUBLISH);
            enable(PUBLISHER, UNSCOPED, Grant.EXECUTE_PUBLISH);

            String stale = observation(mvc, first, "publish", publisherBearer);
            clock.set(clock.get().plusSeconds(901));
            problem(call(mvc, route(first, "publish"), publisherBearer, key, Map.of("expectedRevision", stale)),
                    409, "stale_observation");
            require(attempts() == 0, "stale context reserved a number");
            System.out.println("PASS independent execute grant and fresh observation precede reservation");

            firstIntent = intent(mvc, first);
            previousBody = confirmInOrder(mvc, firstIntent, false, TWO_TO_53 + 1);
            var secondIntent = intent(mvc, second);
            confirmInOrder(mvc, secondIntent, true, TWO_TO_53 + 2);
            require(preGateChecks.get() == 2, "scope preparation did not precede namespace acquisition");
        }

        clock.set(clock.get().plusSeconds(901));
        try (var restarted = new B08MvcSession(settings)) {
            var replay = submit(restarted, firstIntent, publisherAlias);
            require(replay.status() == 200 && replay.body().equals(previousBody) && attempts() == 2,
                    "response-loss replay changed the original effect after restart");
            var alternate = new Intent(first, firstIntent.key(), firstIntent.observation() + "x");
            problem(submit(restarted, alternate, publisherAlias), 409, "key_conflict");
            publicationFieldGrant(restarted, firstIntent);
            burnAfterRollback(restarted, rollback);
            exactLimit(restarted, last);
        }
        System.out.println("PASS exact response-loss replay after restart, alias login and observation expiry");
    }

    private String confirmInOrder(B08MvcSession mvc, Intent intent, boolean scopeFirst, long expectedId) throws Exception {
        problem(submit(mvc, intent, publisherBearer), 503, "authority_unavailable");
        var entry = binding(SCOPED, intent.ref());
        require(entry.state() == State.PENDING && entry.allocation().orElseThrow().value() == expectedId,
                "durable original reservation identity");
        var originalScope = scopeWitness(intent.ref());
        exactCommandRows(intent.ref(), entry);
        require(dispatchBlocked(intent.ref()), "early dispatch admitted with both confirmations pending");
        long auditCount = 1;
        if (scopeFirst) {
            baseline.confirm(originalScope);
            problem(submit(mvc, intent, publisherBearer), 503, "outcome_unknown");
            problem(submit(mvc, new Intent(intent.ref(), issue(mvc, publisherBearer), intent.observation()),
                    publisherBearer), 503, "authority_unavailable");
            require(dispatchBlocked(intent.ref()), "scope-only confirmation admitted dispatch");
            require(fieldId(intent.ref()) == 0, "scope-only confirmation disclosed the publication field");
            projectionGrant(false);
            try {
                try {
                    confirm(entry);
                    throw new AssertionError("publication projection fault was not observed");
                } catch (AuthorityUnavailableException expected) {
                    require(journal.find(entry.attempt()).orElseThrow().state() == State.COMMITTED
                                    && audits(entry, "COMMITTED") == 1 && confirmations(entry) == 0,
                            "independent confirmation did not survive the failed local projection");
                }
                problem(submit(mvc, intent, publisherBearer), 503, "outcome_unknown");
                require(dispatchBlocked(intent.ref()) && fieldId(intent.ref()) == 0,
                        "journal confirmation alone admitted a result before the guarded projection");
            } finally {
                projectionGrant(true);
            }
            auditCount++;
            confirm(entry);
        } else {
            confirm(entry);
            problem(submit(mvc, intent, publisherBearer), 503, "authority_unavailable");
            require(dispatchBlocked(intent.ref()), "publication-only confirmation admitted dispatch");
            baseline.confirm(originalScope);
        }
        var released = submit(mvc, intent, publisherBearer);
        result(released, intent.ref(), "publish", "PUBLISHED", "4");
        var wire = JSON.readTree(released.body()).get("publicationId");
        require(wire.isString() && wire.asString().equals(Long.toString(expectedId))
                        && released.cache().equals("no-store"),
                "publication ID is not the exact positive signed-64-bit JSON string");
        require(dispatchId(intent.ref()) == expectedId && fieldId(intent.ref()) == expectedId,
                "both confirmations did not admit the exact publication");
        exactCommandRows(intent.ref(), entry);
        require(audits(entry, "COMMITTED") == auditCount && confirmations(entry) == 1,
                "missing exact audited confirmation");
        confirm(entry);
        require(audits(entry, "COMMITTED") == auditCount + 1 && confirmations(entry) == 1,
                "confirmation is not idempotent");
        System.out.println(scopeFirst ? "PASS scope first with outcome_unknown and dispatch held until publication proof"
                : "PASS publication first with current scope and dispatch held until scope proof");
        return released.body();
    }

    private void publicationFieldGrant(B08MvcSession mvc, Intent intent) throws Exception {
        var field = new GrantKey(PUBLISHER, Grant.FIELD_READ_PUBLICATION_ID, Optional.of(SCOPED));
        baseline.confirm(profile.updates().stageRevocation(OPERATOR, field));
        problem(submit(mvc, intent, publisherAlias), 404, "not_found");
        require(dispatchId(intent.ref()) == TWO_TO_53 + 1 && fieldId(intent.ref()) == 0 && attempts() == 2,
                "actor field revocation changed durable publication or the internal association");
        baseline.confirm(profile.updates().stageEnablement(OPERATOR, field));
        require(submit(mvc, intent, publisherAlias).status() == 200, "current independent field grant was not rechecked");
        var readResult = new GrantKey(PUBLISHER, Grant.RESULT_READ_PUBLISH, Optional.of(SCOPED));
        baseline.confirm(profile.updates().stageRevocation(OPERATOR, readResult));
        problem(submit(mvc, intent, publisherAlias), 404, "not_found");
        require(fieldId(intent.ref()) == TWO_TO_53 + 1, "result grant incorrectly governs the independent field");
        baseline.confirm(profile.updates().stageEnablement(OPERATOR, readResult));
        System.out.println("PASS independent publication field grant gates replay without changing the association");
    }

    private void burnAfterRollback(B08MvcSession mvc, RequestRef ref) throws Exception {
        var attempted = intent(mvc, ref);
        long facts = count("requests.transition_fact");
        long receipts = count("requests.operation_receipt");
        long witnesses = count("requests.publication_witness");
        long scopeWitnesses = count("requests.scope_authority_witness");
        long scopeFacts = count("requests.request_visibility_fact");
        sql.execute("REVOKE INSERT ON requests.publication_witness FROM b04_workflow");
        try {
            problem(submit(mvc, attempted, publisherBearer), 500, "internal_error");
        } finally {
            sql.execute("GRANT INSERT ON requests.publication_witness TO b04_workflow");
        }
        var rolledBack = binding(SCOPED, ref);
        require(rolledBack.state() == State.PENDING && rolledBack.allocation().orElseThrow().value() == TWO_TO_53 + 3
                        && state(ref).equals("ACCEPTED") && facts == count("requests.transition_fact")
                        && receipts == count("requests.operation_receipt")
                        && witnesses == count("requests.publication_witness")
                        && scopeWitnesses == count("requests.scope_authority_witness")
                        && scopeFacts == count("requests.request_visibility_fact") && dispatchBlocked(ref),
                "rollback left a local success effect or failed to burn the original number");
        problem(submit(mvc, attempted, publisherBearer), 503, "authority_unavailable");
        UUID rolledBackScope = authority.queryForObject("""
                SELECT attempt_id FROM request_authority.scope_journal_attempt
                WHERE organization = ? AND state = 'PENDING' AND workflow_transaction = CAST(? AS xid8)
                """, UUID.class, scope.genesis().organization().value(), rolledBack.attempt().workflowTransaction());
        baseline.abort(rolledBackScope);
        problem(submit(mvc, attempted, publisherBearer), 503, "authority_unavailable");
        require(attempts() == 3 && receipts == count("requests.operation_receipt"),
                "a new effect escaped the unresolved publication attempt");
        profile.publicationResolver().orElseThrow().abort(scope, rolledBack.attempt());
        var aborted = journal.find(rolledBack.attempt()).orElseThrow();
        require(aborted.state() == State.ABORTED && aborted.allocation().equals(rolledBack.allocation())
                        && audits(rolledBack, "ABORTED") == 1, "rollback proof failed to retain burned number");
        problem(submit(mvc, attempted, publisherBearer), 503, "authority_unavailable");
        var retried = binding(SCOPED, ref);
        require(retried.allocation().orElseThrow().value() == TWO_TO_53 + 4 && attempts() == 4,
                "proven rollback reallocated the burned ID");
        exactCommandRows(ref, retried);
        confirm(retried);
        baseline.confirm(scopeWitness(ref));
        result(submit(mvc, attempted, publisherBearer), ref, "publish", "PUBLISHED", "4");
        System.out.println("PASS reserved rollback burns one number, preserves all local state and fences until audited abort");
    }

    private void exactLimit(B08MvcSession mvc, RequestRef ref) throws Exception {
        authority.update("UPDATE request_authority.publication_namespace SET last_id = ?", Long.MAX_VALUE - 1);
        var last = intent(mvc, ref);
        problem(submit(mvc, last, publisherBearer), 503, "outcome_unknown");
        var entry = binding(UNSCOPED, ref);
        confirm(entry);
        var released = submit(mvc, last, publisherBearer);
        result(released, ref, "publish", "PUBLISHED", "4");
        var wire = JSON.readTree(released.body()).get("publicationId");
        require(wire.isString() && wire.asString().equals("9223372036854775807")
                && dispatchId(ref) == Long.MAX_VALUE, "maximum signed-64-bit wire result was rounded");
        System.out.println("PASS exact maximum signed-64-bit JSON string and increasing committed IDs");
    }

    private void exactCommandRows(RequestRef ref, Entry entry) {
        require(Boolean.TRUE.equals(sql.queryForObject("""
                SELECT count(*) = 1 FROM requests.publication_commit_evidence
                WHERE attempt_id = ? AND ref = ? AND publication_id = ?
                    AND workflow_transaction = CAST(? AS xid8)
                """, Boolean.class, entry.attempt().id(), ref.value(), entry.allocation().orElseThrow().value(),
                entry.attempt().workflowTransaction())), "request, fact, receipt and witness did not share one commit");
        require(sql.queryForObject("""
                SELECT count(*) FROM requests.transition_fact WHERE ref = ? AND action = 'PUBLISH'
                """, Long.class, ref.value()) == 1, "publication applied more than once");
    }

    PendingConfirmation scopeWitness(RequestRef ref) {
        return sql.queryForObject("""
                SELECT s.attempt_id, s.sequence, s.after_version
                FROM requests.scope_authority_witness s
                JOIN requests.publication_witness p ON p.organization = s.organization
                    AND p.workflow_transaction = s.workflow_transaction AND p.xmin = s.xmin
                JOIN requests.request_visibility_fact v ON v.organization = p.organization AND v.ref = p.ref
                    AND v.applied_version = s.after_version AND v.xmin = s.xmin
                WHERE p.ref = ? AND v.assignee_only
                """, (row, i) -> new PendingConfirmation(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3)),
                ref.value());
    }

    private long audits(Entry entry, String state) {
        return authority.queryForObject("""
                SELECT count(*) FROM request_authority.publication_resolution_audit
                WHERE attempt_id = ? AND terminal_state = ? AND resolver_role = 'b11_resolver'
                """, Long.class, entry.attempt().id(), state);
    }

    private long confirmations(Entry entry) {
        return sql.queryForObject("""
                SELECT count(*) FROM requests.publication_confirmation
                WHERE attempt_id = ? AND projected_by = 'b11_projection'
                """, Long.class, entry.attempt().id());
    }

    void confirm(Entry entry) {
        profile.publicationResolver().orElseThrow().confirm(scope, entry.attempt());
    }

    private void projectionGrant(boolean allowed) {
        sql.execute((allowed ? "GRANT INSERT " : "REVOKE INSERT ") + PROJECTION_COLUMNS
                + " ON requests.publication_confirmation " + (allowed ? "TO " : "FROM ") + "b11_projection");
    }

    private boolean dispatchBlocked(RequestRef ref) {
        try {
            return dispatchId(ref) == 0;
        } catch (AuthorityUnavailableException unavailable) {
            return true;
        }
    }

    private long dispatchId(RequestRef ref) {
        return transactions.run(() -> {
            var guard = guards.lockShared(OWNER.organization()).orElseThrow();
            return profile.access().confirmedPublication(guard, ref).map(id -> id.value()).orElse(0L);
        });
    }

    private long fieldId(RequestRef ref) {
        return transactions.run(() -> {
            var guard = guards.lockShared(OWNER.organization()).orElseThrow();
            return profile.access().permittedPublicationId(PUBLISHER, guard, ref).map(id -> id.value()).orElse(0L);
        });
    }

    Entry binding(RequestType type, RequestRef ref) {
        return journal.activeBinding(scope.writer(), OWNER.organization(), type, ref).orElseThrow();
    }

    private long attempts() {
        return authority.queryForObject("SELECT count(*) FROM request_authority.publication_attempt", Long.class);
    }

    private String state(RequestRef ref) {
        return sql.queryForObject("SELECT state FROM requests.request WHERE ref = ?", String.class, ref.value());
    }

    private long count(String knownTable) {
        return sql.queryForObject("SELECT count(*) FROM " + knownTable, Long.class);
    }

    RequestRef accepted(B08MvcSession mvc, RequestType type) throws Exception {
        var created = call(mvc, "/requests", ownerBearer, issue(mvc, ownerBearer),
                Map.of("type", type.name(), "name", "Exact synthetic name", "body", "Exact synthetic body"));
        require(created.status() == 201, "Create setup failed with " + created.status());
        var ref = new RequestRef(JSON.readTree(created.body()).get("ref").asString());
        result(call(mvc, route(ref, "verify"), ownerBearer, issue(mvc, ownerBearer),
                Map.of("expectedRevision", observation(mvc, ref, "verify", ownerBearer))),
                ref, "verify", "VERIFIED", "2");
        baseline.confirm(profile.updates().stageAssignment(OPERATOR, ref, PUBLISHER));
        result(call(mvc, route(ref, "accept"), publisherBearer, issue(mvc, publisherBearer),
                Map.of("expectedRevision", observation(mvc, ref, "accept", publisherBearer))),
                ref, "accept", "ACCEPTED", "3");
        return ref;
    }

    void enable(AuthenticatedActor actor, RequestType type, Grant... permissions) {
        for (Grant permission : permissions) {
            baseline.confirm(profile.updates().stageEnablement(OPERATOR, new GrantKey(actor, permission, Optional.of(type))));
        }
    }

    private static Intent intent(B08MvcSession mvc, RequestRef ref, String bearer) throws Exception {
        return new Intent(ref, issue(mvc, bearer), observation(mvc, ref, "publish", bearer));
    }

    private Intent intent(B08MvcSession mvc, RequestRef ref) throws Exception {
        return intent(mvc, ref, publisherBearer);
    }

    static String issue(B08MvcSession mvc, String bearer) throws Exception {
        var reply = mvc.invoke(new B08MvcSession.Call("/operation-keys", bearer, "", ""));
        require(reply.status() == 201, "key issue setup failed with " + reply.status());
        return JSON.readTree(reply.body()).get("key").asString();
    }

    static String observation(B08MvcSession mvc, RequestRef ref, String action, String bearer) throws Exception {
        var reply = mvc.invoke(MockMvcRequestBuilders.get("/requests/{ref}/action-context", ref.value())
                .queryParam("action", action), bearer);
        require(reply.status() == 200, "action context setup failed with " + reply.status());
        return JSON.readTree(reply.body()).get("expectedRevision").asString();
    }

    private static B08MvcSession.Reply submit(B08MvcSession mvc, Intent intent, String bearer) throws Exception {
        return call(mvc, route(intent.ref(), "publish"), bearer, intent.key(),
                Map.of("expectedRevision", intent.observation()));
    }

    static B08MvcSession.Reply call(B08MvcSession mvc, String route, String bearer, String key,
                                            Map<String, String> fields) throws Exception {
        return mvc.invoke(new B08MvcSession.Call(route, bearer, key, JSON.writeValueAsString(fields)));
    }

    private static String route(RequestRef ref, String action) {
        return "/requests/" + ref.value() + "/actions/" + action;
    }

    private static void result(B08MvcSession.Reply reply, RequestRef ref, String action, String state, String revision) {
        require(reply.status() == 200, action + " expected 200, got " + reply.status());
        var json = JSON.readTree(reply.body());
        require(json.size() == (action.equals("publish") ? 5 : 4)
                        && json.get("ref").asString().equals(ref.value()) && json.get("action").asString().equals(action)
                        && json.get("state").asString().equals(state) && json.get("resultRevision").asString().equals(revision),
                "unexpected " + action + " wire result");
    }

    private static void problem(B08MvcSession.Reply reply, int expected, String code) {
        require(reply.status() == expected, code + " expected " + expected + ", got " + reply.status());
        var json = JSON.readTree(reply.body());
        require(json.get("type").asString().equals("urn:requests:problem:" + code) && !json.has("publicationId"),
                "wrong or protected problem fields for " + code);
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    var result = invoke(connection, method, arguments);
                    if (!method.getName().equals("createStatement")) {
                        return result;
                    }
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                            (unused, operation, operands) -> {
                                if (operation.getName().equals("execute") && operands != null && operands.length > 0
                                        && "SELECT pg_advisory_xact_lock(1919250805, 16)".equals(operands[0])) {
                                    try (var proof = proofOwner.getConnection(); var query = proof.createStatement();
                                         var rows = query.executeQuery("""
                                                 SELECT count(*) FROM request_authority.scope_journal_attempt
                                                 WHERE state = 'PENDING'
                                                 """)) {
                                        require(rows.next() && rows.getLong(1) <= 1, "unexpected pending scope attempts");
                                        if (rows.getLong(1) == 1) {
                                            preGateChecks.incrementAndGet();
                                        }
                                    }
                                }
                                return invoke(result, operation, operands);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static byte[] secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String bearer() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Intent(RequestRef ref, String key, String observation) { }
}
