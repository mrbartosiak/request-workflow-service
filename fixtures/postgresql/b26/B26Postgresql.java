import static com.company.requests.integration.InboundCoordinator.Outcome.ACKNOWLEDGED;
import static com.company.requests.integration.InboundCoordinator.Outcome.REJECTED;
import static com.company.requests.integration.InboundCoordinator.Outcome.UNAVAILABLE;
import static com.company.requests.integration.InboundNotice.Hint.CHANGE;
import static com.company.requests.integration.InboundNotice.Hint.CORRECTION;
import static com.company.requests.integration.InboundNotice.Type.DETAILS_CHANGED;
import static com.company.requests.integration.InboundNotice.Type.STATUS_CHANGED;

import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcPublicationProvisioning;
import com.company.requests.adapter.integration.jdbc.JdbcExternalMappingProvisioning;
import com.company.requests.adapter.integration.jdbc.JdbcExternalMappingProjection;
import com.company.requests.adapter.integration.jdbc.JdbcExternalMappingRegistry;
import com.company.requests.adapter.integration.jdbc.JdbcInboundInbox;
import com.company.requests.adapter.integration.jdbc.JdbcInboundMigration;
import com.company.requests.adapter.integration.jdbc.JdbcOriginalMappingEffect;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.EffectiveScopeStore;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Components;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import com.company.requests.bootstrap.SyntheticInboundConfiguration;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.ExternalMapping;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.ExternalMappings;
import com.company.requests.integration.InboundCoordinator;
import com.company.requests.integration.InboundInbox.Scope;
import com.company.requests.integration.InboundNotice;
import com.company.requests.integration.InboundNotice.Hint;
import com.company.requests.integration.InboundNotice.Identity;
import com.company.requests.integration.InboundNotice.Type;
import com.company.requests.integration.SyntheticInboundSources;
import com.company.requests.integration.SyntheticInboundSources.Route;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Two independently composed synthetic receivers and an independent observer on real PostgreSQL. */
public final class B26Postgresql {
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor SERVICE = service("b26-inbound");
    private static final AuthenticatedActor DENIED = service("b26-denied");
    private static final OrganizationId CROSS_ORG = new OrganizationId("b26-second-org");
    private static final AuthenticatedActor CROSS_OWNER = new AuthenticatedActor("synthetic-b26", CROSS_ORG,
            "cross-owner", AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("cross-person")));
    private static final AuthenticatedActor CROSS_SERVICE = service(CROSS_ORG, "cross-inbound");
    private static final AuthenticatedActor CROSS_DENIED = service(CROSS_ORG, "cross-denied");
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final Scope A = new Scope("synthetic-b26", "local-tenant", "tenant-events");
    private static final Scope B = new Scope(A.sourceSystem(), A.tenant(), "independent-events");
    private static final Scope C = new Scope(A.sourceSystem(), A.tenant(), "denied-events");
    private static final ExternalMapping.Scope MAPPING_SCOPE = new ExternalMapping.Scope(A.sourceSystem(), A.tenant(), "record");
    private static final Identity EVENT_ID = new Identity(new byte[]{0, -1, 'A', -64, -128});
    private static final Identity EVENT_BINARY_NEIGHBOR = new Identity(new byte[]{0, -1, 'a', -64, -128});
    private static final Identity MAPPED = identity(40);
    private static final Identity OTHER_MAPPED = identity(41);
    private static final Identity QUARANTINED_MAPPING = identity(42);
    private static final Identity CROSS_MAPPED = identity(43);
    private static final Route ROUTE_A = route("route-a", A, SERVICE);
    private static final Route ROUTE_B = route("route-b", B, SERVICE);
    private static final Route ROUTE_C = route("route-c", C, DENIED);
    private static final Route ROUTE_CROSS = route("route-cross", A, CROSS_SERVICE);
    private static final Route ROUTE_CROSS_DENIED = route("route-cross-denied", A, CROSS_DENIED);

    private final B26CommitCut cuts = new B26CommitCut();
    private final AtomicReference<Set<Scope>> admitted = new AtomicReference<>(Set.of(A, B, C));
    private final B10Postgresql baseline;
    private final JdbcTemplate owner;
    private final JdbcTemplate observer;
    private final Settings settings;
    private final ExternalMappings mappings;
    private final InboundCoordinator first;
    private final InboundCoordinator second;
    private final CrossOrganization cross;

    private B26Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections authority,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, B10FixtureSources data) throws SQLException {
        baseline = new B10Postgresql(workflow, authority, projector, resolver, createProof, data, cuts::wrap, policy());
        settings = baseline.settings();
        var workflowOwner = ownerSource(workflow.migration());
        var authorityOwner = ownerSource(authority.migration());
        owner = new JdbcTemplate(workflowOwner);
        observer = new JdbcTemplate(data.source(workflow.observer()));
        provision(workflowOwner, authorityOwner);
        mappings = mappings(settings, baseline.components(), SERVICE, DENIED);
        first = receiver(settings, baseline.components(), mappings, ROUTE_A, ROUTE_B, ROUTE_C);
        var original = settings.create();
        var command = data.source(workflow.runtime());
        var other = new SyntheticCreateConfiguration.Settings(command, original.proof(), original.policy(),
                original.writer(), original.actors(), original.clock(), original.secrets());
        var independent = new Settings(other, settings.preparation(), settings.originalObserver(), settings.projection(),
                settings.resolver(), settings.operators());
        var app = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(independent);
        second = receiver(independent, app, mappings(independent, app, SERVICE, DENIED), ROUTE_A, ROUTE_B, ROUTE_C);

        var crossPolicy = new SyntheticScopePolicy(CROSS_ORG, 1, List.of(
                new SyntheticScopePolicy.Grant(CROSS_OWNER, true, Set.of(TYPE), Set.of(TYPE)),
                new SyntheticScopePolicy.Grant(CROSS_SERVICE, false, Set.of(), Set.of(TYPE)),
                new SyntheticScopePolicy.Grant(CROSS_DENIED, false, Set.of(), Set.of())));
        var crossCommands = new SyntheticCreateConfiguration.Settings(command, original.proof(), crossPolicy,
                original.writer(), original.actors(), original.clock(), original.secrets());
        var crossSettings = new Settings(crossCommands, settings.preparation(), settings.originalObserver(),
                settings.projection(), settings.resolver(), Map.of());
        var crossGenesis = crossSettings.effectivePolicy().genesis();
        var authorityProvisioning = new JdbcAuthorityProvisioning(authorityOwner);
        authorityProvisioning.certifyGenesis(crossGenesis);
        authorityProvisioning.initializeScopeJournal(crossGenesis);
        owner.update("INSERT INTO requests.policy_guard (organization, policy_version) VALUES (?, 1)", CROSS_ORG.value());
        var crossApp = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(crossSettings);
        var crossMappings = mappings(crossSettings, crossApp, CROSS_SERVICE, CROSS_DENIED);
        cross = new CrossOrganization(crossApp.create(), crossMappings,
                receiver(crossSettings, crossApp, crossMappings, ROUTE_CROSS),
                receiver(crossSettings, crossApp, crossMappings, ROUTE_CROSS_DENIED));
    }

    private static DataSource ownerSource(QualifyPostgresql.SqlConnection opener) {
        // The qualified fixture reserves PostgreSQL's administrator connection slots.
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return opener.open(); }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }
        };
    }

    public static void main(String[] args) {
        try {
            require(args.length == 4 && args[3].matches("b26-[a-z0-9-]{1,27}"),
                    "Usage: run|cleanup BIN RUNS_DIRECTORY b26-RUN_ID");
            args[3] = "b10-" + args[3];
            QualifyPostgresql.effectiveScopeAccess(args, (workflow, authority, projector, resolver, createProof) -> {
                Persisted committed;
                Admission admission;
                try (var data = new B10FixtureSources()) {
                    var fixture = new B26Postgresql(workflow, authority, projector, resolver, createProof, data);
                    committed = fixture.exercise();
                    admission = new Admission(fixture.settings.effectivePolicy().genesis(), fixture.settings.create().writer());
                }
                workflow.restart().run();
                try (var fresh = new B10FixtureSources()) {
                    var database = new JdbcTemplate(fresh.source(workflow.observer()));
                    require(committed.equals(persisted(database)), "receipt, processing or quarantine changed on clean restart");
                    try {
                        new JdbcCreateProof(fresh.source(createProof)).requireWriter(admission,
                                JdbcWorkflowIdentity.inspect(fresh.source(workflow.runtime())));
                        throw new AssertionError("The old workflow certificate admitted the restarted incarnation.");
                    } catch (AuthorityUnavailableException expected) {
                        System.out.println("PASS clean PostgreSQL restart retained the exact ledger and fenced the old writer certificate");
                    }
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B26 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void provision(DataSource workflowOwner, DataSource authorityOwner) {
        new JdbcPublicationProvisioning(authorityOwner).migrate();
        JdbcExternalMappingProvisioning.migrateRegistry(authorityOwner);
        JdbcExternalMappingProvisioning.migrateProjection(workflowOwner);
        JdbcInboundMigration.migrate(workflowOwner);
        JdbcInboundMigration.migrate(workflowOwner);
        new JdbcTemplate(authorityOwner).execute("""
                GRANT EXECUTE ON FUNCTION request_authority.reserve_external_mapping(
                    uuid, text, text, text, bytea, text, text, text, bigint, bigint, text, bigint, uuid, bigint) TO b04_proof;
                GRANT SELECT ON request_authority.external_mapping_binding,
                    request_authority.external_mapping_quarantine TO b04_proof;
                """);
        owner.execute("""
                GRANT SELECT ON requests.external_mapping_create_evidence, requests.external_mapping TO b04_workflow;
                GRANT INSERT ON requests.external_mapping TO b04_workflow;
                GRANT SELECT ON requests.external_mapping_commit_evidence TO b04_observer;
                GRANT SELECT ON requests.inbound_inbox TO b04_workflow;
                GRANT SELECT (source_system, source_tenant, identity_domain, source_identity)
                    ON requests.inbound_processing TO b04_workflow;
                GRANT INSERT (source_system, source_tenant, identity_domain, source_identity, declared_type, organization,
                    request_type, ref, source_version, target_revision, semantic_digest)
                    ON requests.inbound_inbox, requests.inbound_quarantine TO b04_workflow;
                GRANT INSERT (source_system, source_tenant, identity_domain, source_identity, identifier_type, external_id)
                    ON requests.inbound_processing TO b04_workflow;
                GRANT SELECT ON requests.inbound_inbox, requests.inbound_processing, requests.inbound_quarantine,
                    requests.request, requests.transition_fact, requests.operation_receipt TO b04_observer;
                """);
        require(Boolean.TRUE.equals(owner.queryForObject("""
                SELECT NOT has_table_privilege('b04_workflow', 'requests.inbound_inbox', 'UPDATE')
                   AND NOT has_table_privilege('b04_workflow', 'requests.inbound_processing', 'DELETE')
                   AND NOT has_table_privilege('b04_workflow', 'requests.inbound_quarantine', 'DELETE')
                   AND NOT has_any_column_privilege('b04_workflow', 'requests.inbound_quarantine', 'SELECT')
                   AND NOT has_column_privilege('b04_workflow', 'requests.inbound_inbox', 'workflow_transaction', 'INSERT')
                   AND NOT has_column_privilege('b04_workflow', 'requests.inbound_processing', 'envelope_version', 'INSERT')
                   AND NOT has_any_column_privilege('b04_observer', 'requests.inbound_inbox', 'INSERT')
                """, Boolean.class)), "Inbox privileges cross the role boundary.");
        try {
            new JdbcInboundInbox(settings.create().command()).lastSourceVersion(new PolicyGuard(OWNER.organization(), 1),
                    A, new Target(OWNER.organization(), TYPE, new RequestRef("T".repeat(32))));
            throw new AssertionError("An unbound ledger operation was admitted.");
        } catch (IllegalStateException expected) {
            System.out.println("PASS repeatable owner migration, immutable role grants and bound inbox transaction");
        }
    }

    private InboundCoordinator receiver(Settings config, Components app, ExternalMappings mappingService, Route... routes) {
        return SyntheticInboundConfiguration.receiver(config.create().command(), app.access(), mappingService,
                new SyntheticInboundSources(List.of(routes)),
                (guard, current, scope) -> current.writer().equals(config.create().writer()) && admitted.get().contains(scope));
    }

    private ExternalMappings mappings(Settings config, Components app, AuthenticatedActor service, AuthenticatedActor denied) {
        var command = config.create().command();
        var projection = new JdbcExternalMappingProjection(command);
        var registry = new JdbcExternalMappingRegistry(config.preparation());
        return new ExternalMappings(JdbcTransactionRunner.forCreate(command), new JdbcWorkflowRecords(command),
                (actor, guard, scope, operation) -> scope.equals(MAPPING_SCOPE)
                        && (actor.equals(service) || actor.equals(denied) && operation == ExternalMappings.Operation.USE)
                        ? Optional.of(new ExternalMappings.Authorization(Set.of(TYPE), false)) : Optional.empty(),
                app.access()::requireDispatchScope, projection, app.access()::confirmedPublication,
                registry, projection, new JdbcOriginalMappingEffect(config.originalObserver(), registry));
    }

    private Persisted exercise() throws Exception {
        var target = create(baseline.components().create(), OWNER);
        var other = create(baseline.components().create(), OWNER);
        var crossTarget = create(cross.create(), CROSS_OWNER);
        bind(MAPPED, target, ExternalMappings.Outcome.PROJECTED);
        bind(OTHER_MAPPED, other, ExternalMappings.Outcome.PROJECTED);
        bind(QUARANTINED_MAPPING, target, ExternalMappings.Outcome.PROJECTED);
        bind(QUARANTINED_MAPPING, other, ExternalMappings.Outcome.QUARANTINED);
        require(cross.mappings().bind(CROSS_SERVICE, new ExternalMapping.Key(MAPPING_SCOPE, CROSS_MAPPED.bytes()),
                crossTarget, Optional.empty()) == ExternalMappings.Outcome.PROJECTED, "Second organization mapping setup failed.");
        var initial = snapshot(observer);
        var notice = notice(EVENT_ID, STATUS_CHANGED, MAPPED, 101, 1, CHANGE);
        deniedInputs(notice, initial);
        failedCommit(notice, initial);
        var original = commitInOrder(notice, target);
        conflicts(notice, original);
        require(send(second, ROUTE_B, notice(EVENT_ID, DETAILS_CHANGED, MAPPED, 101, 1, CORRECTION)) == ACKNOWLEDGED
                && send(second, ROUTE_A, notice(EVENT_BINARY_NEIGHBOR, STATUS_CHANGED, MAPPED, 102, 1, CHANGE)) == ACKNOWLEDGED
                && snapshot(observer).matches(3, 3, 4), "Certified domain and binary identity separation failed.");
        System.out.println("PASS identical binary identity in separately certified domain and distinct binary identity in original domain");

        var beforeStale = snapshot(observer);
        require(send(first, ROUTE_A, notice(identity(9), STATUS_CHANGED, MAPPED, 100, 1, CHANGE)) == REJECTED
                && send(first, ROUTE_A, notice(identity(9), DETAILS_CHANGED, MAPPED, 102, 1, CHANGE)) == REJECTED
                && send(first, ROUTE_A, notice(identity(9), STATUS_CHANGED, MAPPED, 103, 2, CHANGE)) == REJECTED
                && send(second, ROUTE_A, notice) == ACKNOWLEDGED
                && beforeStale.equals(snapshot(observer)), "Out-of-order source or mismatched target revision added a row.");
        System.out.println("PASS stale new notices added zero rows and an exact older committed notice still acknowledged");

        lostCommitReply(target);
        admitted.set(Set.of(B, C));
        require(send(second, ROUTE_A, notice) == UNAVAILABLE
                && send(second, ROUTE_B, notice(identity(10), STATUS_CHANGED, MAPPED, 104, 1, CHANGE)) == ACKNOWLEDGED,
                "A withheld continuity scope failed closed or blocked the independent scope.");
        admitted.set(Set.of(A, B, C));
        crossOrganizationConflicts(target, crossTarget);
        var priorRevocation = snapshot(observer);
        baseline.confirm(baseline.components().updates().stageRevocation(B10Postgresql.OPERATOR,
                new EffectiveScopeStore.GrantKey(SERVICE, EffectiveScopeStore.Grant.CREATE_RESULT, Optional.of(TYPE))));
        require(send(second, ROUTE_A, notice) == REJECTED
                && send(second, ROUTE_A, notice(identity(11), STATUS_CHANGED, MAPPED, 105, 1, CHANGE)) == REJECTED
                && priorRevocation.equals(snapshot(observer)), "A revoked service still received a positive acknowledgment.");
        System.out.println("PASS per-domain continuity and current confirmed service revocation");
        var complete = snapshot(observer);
        require(complete.matches(6, 6, 6) && initial.sameWorkflow(complete)
                && original.equals(pair(observer, A, EVENT_ID)), "Final exact effect and immutable original counts.");
        System.out.printf("PASS final inbox=%d processing=%d quarantine=%d workflow-delta=0%n",
                complete.receipts, complete.processing, complete.quarantine);
        return persisted(observer);
    }

    private void crossOrganizationConflicts(Target originalTarget, Target alternateTarget) throws Exception {
        var bound = notice(EVENT_ID, STATUS_CHANGED, CROSS_MAPPED, 101, 1, CHANGE);
        var original = pair(observer, A, EVENT_ID);
        var baseline = snapshot(observer);
        var outcome = send(cross.receiver(), ROUTE_CROSS, bound);
        require(outcome == REJECTED, "A currently authorized cross-organization conflict returned " + outcome);
        require(send(cross.receiver(), ROUTE_CROSS, bound) == REJECTED && crossDiscrepancies(alternateTarget, EVENT_ID) == 1
                && original.equals(pair(observer, A, EVENT_ID)) && snapshot(observer).matches(5, 5, 5),
                "A repeat cross-organization conflict failed to preserve exactly one discrepancy and the original pair.");
        System.out.println("PASS two authorized organizations shared one inbox key, with a durable conflict and unchanged original");

        var eventIdentity = identity(19);
        var firstNotice = notice(eventIdentity, STATUS_CHANGED, MAPPED, 105, 1, CHANGE);
        var competingNotice = notice(eventIdentity, STATUS_CHANGED, CROSS_MAPPED, 105, 1, CHANGE);
        var cut = cuts.arm(B26CommitCut.Point.PAUSE_BEFORE_COMMIT);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var delivery = workers.submit(() -> send(first, ROUTE_A, firstNotice));
            B26CommitCut.Observation staged;
            int waitingBackend;
            try {
                staged = cut.await();
                require(!delivery.isDone() && snapshot(observer).matches(5, 5, 5)
                        && send(cross.denied(), ROUTE_CROSS_DENIED, competingNotice) == REJECTED
                        && send(cross.receiver(), ROUTE_CROSS,
                                notice(eventIdentity, STATUS_CHANGED, identity(99), 105, 1, CHANGE)) == REJECTED
                        && snapshot(observer).matches(5, 5, 5),
                        "Denied or unmapped input reached the locked full key or changed the ledger.");
                try (var probe = owner.getDataSource().getConnection()) {
                    var contender = workers.submit(() -> send(cross.receiver(), ROUTE_CROSS, competingNotice));
                    waitingBackend = awaitAdvisoryWaiter(probe, staged.backend());
                    require(contender.get(5, TimeUnit.SECONDS) == UNAVAILABLE && snapshot(observer).matches(5, 5, 5),
                            "A different organization bypassed the same-key lock or exceeded the existing safe timeout.");
                }
            } finally {
                cut.release.countDown();
            }
            require(delivery.get(5, TimeUnit.SECONDS) == ACKNOWLEDGED, "The original organization did not acknowledge its commit.");
            var originalPair = pair(observer, A, eventIdentity);
            checkPair(originalPair, staged.transaction(), originalTarget, 105);
            require(send(cross.receiver(), ROUTE_CROSS, competingNotice) == REJECTED
                    && send(cross.receiver(), ROUTE_CROSS, competingNotice) == REJECTED
                    && crossDiscrepancies(alternateTarget, eventIdentity) == 1
                    && originalPair.equals(pair(observer, A, eventIdentity))
                    && send(first, ROUTE_A, firstNotice) == ACKNOWLEDGED && snapshot(observer).matches(6, 6, 6)
                    && baseline.sameWorkflow(snapshot(observer)),
                    "Cross-organization same-key arbitration changed the original pair or added a workflow effect.");
            System.out.printf("PASS cross-organization same-key waiter pid=%d blocked on advisory lock owned by pid=%d xid=%s%n",
                    waitingBackend, staged.backend(), staged.transaction());
            System.out.println("PASS denied and unmapped cross-organization submissions bypassed the locked key with zero writes");
        }
    }

    private static int awaitAdvisoryWaiter(Connection connection, int ownerBackend) throws SQLException, InterruptedException {
        try (var query = connection.prepareStatement("""
                SELECT pid FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT pg_advisory_xact_lock(%'
                  AND ? = ANY(pg_blocking_pids(pid))
                """)) {
            query.setInt(1, ownerBackend);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            do {
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        return rows.getInt(1);
                    }
                }
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
        }
        throw new AssertionError("PostgreSQL did not observe the expected cross-organization advisory lock waiter.");
    }

    private long crossDiscrepancies(Target target, Identity identity) {
        return observer.queryForObject("""
                SELECT count(*) FROM requests.inbound_quarantine
                WHERE source_system = ? AND source_tenant = ? AND identity_domain = ? AND source_identity = ?
                  AND organization = ? AND ref = ? AND pg_xact_status(workflow_transaction) = 'committed'
                """, Long.class, A.sourceSystem(), A.tenant(), A.identityDomain(), identity.bytes(),
                target.organization().value(), target.ref().value());
    }

    private void deniedInputs(InboundNotice notice, Snapshot initial) throws GeneralSecurityException {
        var wrongTenant = new InboundNotice(A.sourceSystem(), "wrong-tenant", EVENT_ID, STATUS_CHANGED, MAPPED, 101, 1, CHANGE);
        var wrongSource = new InboundNotice("wrong-source", A.tenant(), EVENT_ID, STATUS_CHANGED, MAPPED, 101, 1, CHANGE);
        require(first.receive("unknown", notice, sign(ROUTE_A, notice)) == REJECTED
                && first.receive(ROUTE_B.credential(), notice, sign(ROUTE_A, notice)) == REJECTED
                && first.receive(ROUTE_A.credential(), notice, new byte[32]) == REJECTED
                && first.receive(ROUTE_A.credential(), notice(EVENT_ID, DETAILS_CHANGED, MAPPED, 101, 1, CHANGE),
                        sign(ROUTE_A, notice)) == REJECTED
                && send(first, ROUTE_A, wrongTenant) == REJECTED && send(first, ROUTE_A, wrongSource) == REJECTED
                && send(first, ROUTE_C, notice) == REJECTED
                && send(first, ROUTE_A, notice(EVENT_ID, STATUS_CHANGED, identity(99), 101, 1, CHANGE)) == REJECTED
                && send(first, ROUTE_A, notice(EVENT_ID, STATUS_CHANGED, QUARANTINED_MAPPING, 101, 1, CHANGE)) == REJECTED
                && snapshot(observer).equals(initial), "Untrusted, inaccessible or unmapped input changed the workflow.");
        System.out.println("PASS sender, integrity, tenant, source, target grant and missing/quarantined mapping red cases");
    }

    private void failedCommit(InboundNotice notice, Snapshot initial) throws GeneralSecurityException {
        owner.execute("""
                CREATE FUNCTION requests.b26_reject_processing_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Synthetic B26 deferred commit failure' USING ERRCODE = '23514';
                END;
                $$;
                """);
        owner.execute("""
                CREATE CONSTRAINT TRIGGER b26_processing_commit_cut AFTER INSERT ON requests.inbound_processing
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION requests.b26_reject_processing_commit()
                """);
        var cut = cuts.arm(B26CommitCut.Point.PAUSE_BEFORE_COMMIT);
        cut.release.countDown();
        require(send(first, ROUTE_A, notice) == UNAVAILABLE && snapshot(observer).equals(initial)
                && cut.observation.receipts() == 1 && cut.observation.processing() == 1
                && "23514".equals(cut.commitSqlState)
                && "aborted".equals(status(cut.observation.transaction())), "A deferred processing commit failure escaped.");
        System.out.printf("PASS red deferred processing failure SQLSTATE=%s pid=%d xid=%s aborted, ACK withheld, counts=%d/%d/%d%n",
                cut.commitSqlState, cut.observation.backend(), cut.observation.transaction(),
                initial.receipts, initial.processing, initial.quarantine);
        owner.execute("DROP TRIGGER b26_processing_commit_cut ON requests.inbound_processing");
        owner.execute("DROP FUNCTION requests.b26_reject_processing_commit()");
    }

    private Pair commitInOrder(InboundNotice notice, Target target) throws Exception {
        var cut = cuts.arm(B26CommitCut.Point.PAUSE_BEFORE_COMMIT);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var delivery = workers.submit(() -> send(first, ROUTE_A, notice));
            B26CommitCut.Observation staged;
            try {
                staged = cut.await();
                require(!delivery.isDone() && staged.synchronous() && staged.receipts() == 1 && staged.processing() == 1
                        && "in progress".equals(status(staged.transaction())) && snapshot(observer).matches(0, 0, 0),
                        "ACK or visible rows escaped before COMMIT.");
                require(send(second, ROUTE_A, notice) == UNAVAILABLE && snapshot(observer).matches(0, 0, 0),
                        "A contender admitted the uncommitted identity.");
            } finally {
                cut.release.countDown();
            }
            require(delivery.get(5, TimeUnit.SECONDS) == ACKNOWLEDGED, "Durable commit did not acknowledge.");
            var saved = pair(observer, A, EVENT_ID);
            checkPair(saved, staged.transaction(), target, 101);
            require(send(second, ROUTE_A, notice) == ACKNOWLEDGED && saved.equals(pair(observer, A, EVENT_ID))
                    && snapshot(observer).matches(1, 1, 0), "Exact retry was missing or rewrote the original effect.");
            System.out.printf("PASS green ACK after commit pid=%d xid=%s, independent receiver exact retry kept 1/1/0%n",
                    staged.backend(), staged.transaction());
            return saved;
        }
    }

    private void conflicts(InboundNotice exact, Pair original) throws GeneralSecurityException {
        for (var conflict : List.of(notice(EVENT_ID, DETAILS_CHANGED, MAPPED, 101, 1, CHANGE),
                notice(EVENT_ID, STATUS_CHANGED, MAPPED, 101, 1, CORRECTION),
                notice(EVENT_ID, STATUS_CHANGED, OTHER_MAPPED, 101, 1, CHANGE),
                notice(EVENT_ID, STATUS_CHANGED, MAPPED, 102, 1, CHANGE))) {
            var observed = send(second, ROUTE_A, conflict);
            var repeated = send(first, ROUTE_A, conflict);
            require(observed == REJECTED && repeated == REJECTED,
                    "Expected a committed conflict rejection, received " + observed + " then " + repeated);
        }
        require(snapshot(observer).matches(1, 1, 4) && original.equals(pair(observer, A, EVENT_ID))
                && send(second, ROUTE_A, exact) == ACKNOWLEDGED, "Quarantine replaced the accepted receipt or blocked a repeat.");
        var contents = observer.queryForList("""
                SELECT declared_type || ':' || source_version || ':' || semantic_digest AS binding
                FROM requests.inbound_quarantine
                WHERE pg_xact_status(workflow_transaction) = 'committed' AND target_revision = 1
                """, String.class);
        require(Set.copyOf(contents).size() == 4, "Quarantine is missing durable type, version or semantic bindings.");
        System.out.println("PASS committed type, semantic, target and version conflicts preserved original pair and exact replay");
    }

    private void lostCommitReply(Target target) throws Exception {
        var event = notice(identity(7), STATUS_CHANGED, MAPPED, 103, 1, CHANGE);
        var cut = cuts.arm(B26CommitCut.Point.LOSE_COMMIT_REPLY);
        require(send(first, ROUTE_A, event) == UNAVAILABLE, "Ambiguous commit falsely acknowledged.");
        var committed = pair(observer, A, event.sourceIdentity());
        checkPair(committed, cut.await().transaction(), target, 103);
        require(send(second, ROUTE_A, event) == ACKNOWLEDGED && committed.equals(pair(observer, A, event.sourceIdentity()))
                && snapshot(observer).matches(4, 4, 4), "Lost acknowledgment caused a second receipt or processing obligation.");
        System.out.printf("PASS lost commit response left one durable pair xid=%s; second receiver ACK kept 4/4/4%n",
                committed.transaction);
    }

    private void checkPair(Pair saved, String transaction, Target target, long version) {
        String transactionXmin = Long.toString(Long.parseUnsignedLong(transaction) & 0xffff_ffffL);
        require(saved.transaction.equals(transaction) && saved.processingTransaction.equals(transaction)
                && transactionXmin.equals(saved.receiptXmin) && transactionXmin.equals(saved.processingXmin)
                && "committed".equals(status(transaction))
                && saved.reference.equals(target.ref().value()) && saved.version == version
                && saved.targetRevision == 1 && saved.envelope == 1 && saved.mapping.equals("28")
                && "STATUS_CHANGED".equals(saved.type) && saved.digest.matches("[0-9a-f]{64}"),
                "The durable receipt and processing pair did not match the committed transaction and original identity.");
    }

    private String status(String xid) {
        return observer.queryForObject("SELECT pg_xact_status(CAST(? AS xid8))", String.class, xid);
    }

    private static Pair pair(JdbcTemplate observer, Scope scope, Identity identity) {
        return observer.queryForObject("""
                SELECT i.workflow_transaction::text, p.workflow_transaction::text, i.xmin::text, p.xmin::text,
                    i.ref, i.source_version, i.target_revision, p.envelope_version, encode(p.external_id, 'hex'),
                    i.declared_type, i.semantic_digest
                FROM requests.inbound_inbox i JOIN requests.inbound_processing p
                    USING (source_system, source_tenant, identity_domain, source_identity)
                WHERE i.source_system = ? AND i.source_tenant = ? AND i.identity_domain = ? AND i.source_identity = ?
                """, (row, number) -> new Pair(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getLong(6), row.getLong(7), row.getInt(8), row.getString(9), row.getString(10),
                row.getString(11)), scope.sourceSystem(), scope.tenant(), scope.identityDomain(), identity.bytes());
    }

    private static Snapshot snapshot(JdbcTemplate observer) {
        return observer.queryForObject("""
                SELECT (SELECT count(*) FROM requests.inbound_inbox),
                    (SELECT count(*) FROM requests.inbound_processing), (SELECT count(*) FROM requests.inbound_quarantine),
                    (SELECT count(*) FROM requests.request), (SELECT count(*) FROM requests.transition_fact),
                    (SELECT count(*) FROM requests.operation_receipt)
                """, (row, number) -> new Snapshot(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4),
                row.getLong(5), row.getLong(6)));
    }

    private static Persisted persisted(JdbcTemplate observer) {
        String fingerprint = observer.queryForObject("""
                SELECT encode(sha256(convert_to(jsonb_build_array(
                    (SELECT jsonb_agg(to_jsonb(i) ORDER BY to_jsonb(i)::text COLLATE "C") FROM requests.inbound_inbox i),
                    (SELECT jsonb_agg(to_jsonb(p) ORDER BY to_jsonb(p)::text COLLATE "C") FROM requests.inbound_processing p),
                    (SELECT jsonb_agg(to_jsonb(q) ORDER BY to_jsonb(q)::text COLLATE "C") FROM requests.inbound_quarantine q)
                )::text, 'UTF8')), 'hex')
                """, String.class);
        return new Persisted(snapshot(observer), fingerprint);
    }

    private static Target create(CreateCommand create, AuthenticatedActor actor) {
        var ref = new AtomicReference<RequestRef>();
        var result = create.execute(actor, create.issue(actor).key(),
                new IntentDigest.Create(TYPE, "Synthetic B26 target", "Synthetic target body"), receipt -> {
                    ref.set(receipt.resultRef());
                    return new byte[]{1};
                });
        require(result instanceof CreateCommand.Released, "Ordinary authorized Create did not commit.");
        return new Target(actor.organization(), TYPE, ref.get());
    }

    private void bind(Identity external, Target target, ExternalMappings.Outcome expected) {
        require(mappings.bind(SERVICE, new ExternalMapping.Key(MAPPING_SCOPE, external.bytes()), target, Optional.empty())
                == expected, "B18 mapping setup failed.");
    }

    private static InboundNotice notice(Identity id, Type type, Identity mapping, long version, long revision, Hint hint) {
        return new InboundNotice(A.sourceSystem(), A.tenant(), id, type, mapping, version, revision, hint);
    }

    private static Identity identity(int value) {
        return new Identity(new byte[]{(byte) value});
    }

    private static InboundCoordinator.Outcome send(InboundCoordinator receiver, Route route, InboundNotice notice)
            throws GeneralSecurityException {
        return receiver.receive(route.credential(), notice, sign(route, notice));
    }

    private static byte[] sign(Route route, InboundNotice notice) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(route.secret(), "HmacSHA256"));
        return mac.doFinal(SyntheticInboundSources.signingBytes(route.credential(), notice));
    }

    private static Route route(String id, Scope scope, AuthenticatedActor actor) {
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new Route(id, secret, scope, MAPPING_SCOPE.identifierType(), actor);
    }

    private static AuthenticatedActor service(String principal) {
        return service(OWNER.organization(), principal);
    }

    private static AuthenticatedActor service(OrganizationId organization, String principal) {
        return new AuthenticatedActor("synthetic-b26", organization, principal,
                AuthenticatedActor.Kind.SERVICE, Optional.empty());
    }

    private static SyntheticScopePolicy policy() {
        var assigner = new AuthenticatedActor("b10-issuer", OWNER.organization(), "assignment-operator",
                AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("person-assigner")));
        return new SyntheticScopePolicy(OWNER.organization(), 1, List.of(
                new SyntheticScopePolicy.Grant(OWNER, true, Set.of(TYPE), Set.of(TYPE)),
                new SyntheticScopePolicy.Grant(B10Postgresql.OPERATOR, false, Set.of(), Set.of()),
                new SyntheticScopePolicy.Grant(assigner, false, Set.of(), Set.of()),
                new SyntheticScopePolicy.Grant(SERVICE, false, Set.of(), Set.of(TYPE)),
                new SyntheticScopePolicy.Grant(DENIED, false, Set.of(), Set.of())));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Pair(String transaction, String processingTransaction, String receiptXmin, String processingXmin,
                        String reference, long version, long targetRevision, int envelope, String mapping, String type,
                        String digest) { }

    private record Persisted(Snapshot snapshot, String fingerprint) { }

    private record CrossOrganization(CreateCommand create, ExternalMappings mappings, InboundCoordinator receiver,
                                     InboundCoordinator denied) { }

    private record Snapshot(long receipts, long processing, long quarantine, long requests, long facts, long commands) {
        boolean matches(long expectedReceipts, long expectedProcessing, long expectedQuarantine) {
            return receipts == expectedReceipts && processing == expectedProcessing && quarantine == expectedQuarantine;
        }

        boolean sameWorkflow(Snapshot other) {
            return requests == other.requests && facts == other.facts && commands == other.commands;
        }
    }
}
