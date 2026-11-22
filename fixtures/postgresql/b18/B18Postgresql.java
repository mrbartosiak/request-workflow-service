import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcPublicationProvisioning;
import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.authority.JdbcReleaseEvidence.Input;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.integration.jdbc.JdbcExternalMappingProjection;
import com.company.requests.adapter.integration.jdbc.JdbcExternalMappingProvisioning;
import com.company.requests.adapter.integration.jdbc.JdbcExternalMappingRegistry;
import com.company.requests.adapter.integration.jdbc.JdbcOriginalMappingEffect;
import com.company.requests.adapter.jdbc.JdbcPublicationWitnessMigration;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.TransactionBusyException;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.ExternalMapping.Association;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMapping.Publication;
import com.company.requests.integration.ExternalMapping.Scope;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.ExternalMappings;
import com.company.requests.integration.ExternalMappings.Authorization;
import com.company.requests.integration.ExternalMappings.Outcome;
import com.company.requests.integration.ExternalMappings.Registry.Reservation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Private real PostgreSQL oracle for independent mappings and workflow rewind. */
public final class B18Postgresql {
    private static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    private static final AuthenticatedActor PUBLISHER = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor OPERATOR = B10Postgresql.OPERATOR;
    private static final AuthenticatedActor MAPPER = service("b18-mapping");
    private static final AuthenticatedActor DENIED = service("b18-denied");
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final Scope LOCAL = new Scope("synthetic-alpha", "synthetic-one", "local");
    private static final Scope PUBLISHED = new Scope("synthetic-alpha", "synthetic-one", "published");
    private static final Scope OTHER_TENANT = new Scope("synthetic-alpha", "synthetic-two", "local");
    private static final Scope OTHER_SOURCE = new Scope("synthetic-beta", "synthetic-one", "local");
    private static final Scope OTHER_TYPE = new Scope("synthetic-alpha", "synthetic-one", "alternate");
    private static final Set<Scope> ALLOWED = Set.of(LOCAL, PUBLISHED, OTHER_TENANT, OTHER_SOURCE, OTHER_TYPE);
    private static final byte[] EXTERNAL = {0, (byte) 0xff, 'A', (byte) 0xc0, (byte) 0x80};
    private static final long EXACT_PUBLICATION = 9_007_199_254_740_993L;
    private static final String PROJECTION = "requests.external_mapping";
    private static final String REGISTRY = "request_authority.external_mapping_binding";
    private static final String QUARANTINE = "request_authority.external_mapping_quarantine";
    private static final String PUBLICATION_COLUMNS = """
            (attempt_id, writer_id, generation, organization, request_type, ref, namespace,
             publication_id, result_revision, workflow_transaction, receipt_identity)
            """;
    private static final JsonMapper JSON = new JsonMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicReference<CreateCut> createCut = new AtomicReference<>();
    private final String ownerBearer = token();
    private final String publisherBearer = token();
    private final JdbcTemplate authority;
    private final JdbcTemplate workflow;
    private final B10Postgresql baseline;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;
    private final SyntheticEffectiveAccessConfiguration.Components workflowProfile;
    private final JdbcPublicationJournal journal;
    private final JdbcExternalMappingRegistry registry;
    private final JdbcOriginalMappingEffect original;
    private final ExternalMappings mappings;
    private final Admission admission;

    private B18Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins release,
                          B10FixtureSources sources) throws SQLException {
        var workflowOwner = B08MvcSession.dataSource(workflow.migration());
        var proofOwner = B08MvcSession.dataSource(proof.migration());
        this.workflow = new JdbcTemplate(workflowOwner);
        authority = new JdbcTemplate(proofOwner);
        baseline = new B10Postgresql(workflow, proof, projector, resolver, createProof, sources, this::observe);
        provision(workflowOwner, proofOwner);
        var base = baseline.settings();
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", ownerBearer, OWNER),
                new SyntheticActorResolver.Binding("publisher", publisherBearer, PUBLISHER)));
        var replay = new SyntheticCreateConfiguration.GuardedReplay(OriginalReceiptProof.unavailable(),
                JdbcReleaseEvidence.preparation(sources.source(release.preparation())),
                JdbcReleaseEvidence.handoffs(sources.source(release.handoff())));
        var create = new SyntheticCreateConfiguration.Settings(base.create().command(), base.create().proof(),
                base.create().policy(), base.create().writer(), actors, Instant::now,
                new OperationSecrets(1, Map.of(1, secret()), 1, Map.of(1, secret())), Optional.of(replay));
        settings = new SyntheticEffectiveAccessConfiguration.Settings(create, base.preparation(), base.originalObserver(),
                base.projection(), base.resolver(), base.operators(),
                Optional.of(new ObservationCodec.Keys(7, Map.of(7, secret()))), true);
        workflowProfile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
        admission = new Admission(settings.effectivePolicy().genesis(), create.writer());
        journal = new JdbcPublicationJournal(base.preparation(), base.resolver(), base.originalObserver());
        registry = new JdbcExternalMappingRegistry(base.preparation());
        original = new JdbcOriginalMappingEffect(base.originalObserver(), registry);
        var projection = new JdbcExternalMappingProjection(create.command());
        mappings = new ExternalMappings(JdbcTransactionRunner.forCreate(create.command()),
                new JdbcWorkflowRecords(create.command()), B18Postgresql::authorize,
                workflowProfile.access()::requireDispatchScope, projection, workflowProfile.access()::confirmedPublication,
                registry, projection, original);
    }

    public static void main(String[] args) {
        try {
            require(args.length == 4 && args[3].matches("b18-[a-z0-9-]{1,27}"), "run|cleanup BIN RUNS b18-RUN_ID");
            args[3] = "b17-" + args[3];
            QualifyPostgresql.publishCommands(args, (workflow, proof, projector, resolver, createProof, release, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B18Postgresql(workflow, proof, projector, resolver, createProof, release, sources)
                            .exercise(workflow.runtimeLogin(), restore, sources);
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B18 " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void provision(DataSource workflowOwner, DataSource proofOwner) {
        new JdbcWorkflowMigrations(workflowOwner).migratePublishGrants();
        new JdbcPublicationWitnessMigration(workflowOwner).migrateConfirmations();
        var provisioning = new JdbcAuthorityProvisioning(proofOwner);
        provisioning.migrateReleaseEvidence();
        provisioning.authorizeReleaseEvidence("b12_prepare", OWNER.organization(), Input.PREPARATION);
        provisioning.authorizeReleaseEvidence("b12_handoff", OWNER.organization(), Input.HANDOFF);
        var publisher = new JdbcPublicationProvisioning(proofOwner);
        publisher.migrateResolver();
        publisher.authorizeResolver("b11_resolver", OWNER.organization(), true, true);
        JdbcExternalMappingProvisioning.migrateRegistry(proofOwner);
        JdbcExternalMappingProvisioning.migrateRegistry(proofOwner);
        JdbcExternalMappingProvisioning.migrateProjection(workflowOwner);
        JdbcExternalMappingProvisioning.migrateProjection(workflowOwner);
        workflow.execute("""
                GRANT UPDATE (state, revision, last_sequence, publication_namespace, publication_id)
                    ON requests.request TO b04_workflow;
                GRANT UPDATE (body) ON requests.request_body TO b04_workflow;
                GRANT INSERT ON requests.publication_witness TO b04_workflow;
                GRANT SELECT ON requests.publication_confirmation TO b04_workflow, b11_projection;
                GRANT SELECT ON requests.publication_target_evidence TO b04_observer;
                GRANT SELECT ON requests.publication_commit_evidence TO b04_observer, b11_projection;
                GRANT SELECT ON requests.external_mapping_create_evidence, requests.external_mapping TO b04_workflow;
                GRANT INSERT ON requests.external_mapping TO b04_workflow;
                GRANT SELECT ON requests.external_mapping_commit_evidence TO b04_observer;
                CREATE FUNCTION requests.b18_fixture_projection_failure() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Synthetic B18 projection rollback' USING ERRCODE = '55000';
                    END;
                    $$;
                """);
        permitPublicationProjection(true);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b12_prepare, b12_handoff;
                GRANT EXECUTE ON FUNCTION request_authority.prepare_release(uuid, text, bytea, text, bigint, bigint,
                    bigint, uuid, bigint, text) TO b12_prepare;
                GRANT EXECUTE ON FUNCTION request_authority.record_release_evidence(uuid, text, bytea, text)
                    TO b12_handoff;
                GRANT SELECT ON request_authority.publication_namespace, request_authority.publication_attempt
                    TO b04_proof, b11_resolver;
                GRANT INSERT (attempt_id, namespace, generation, organization, request_type, ref, backend_pid,
                    workflow_transaction, receipt_identity, result_revision)
                    ON request_authority.publication_attempt TO b04_proof;
                GRANT EXECUTE ON FUNCTION request_authority.allocate_publication(uuid) TO b04_proof;
                GRANT EXECUTE ON FUNCTION request_authority.may_resolve_publication(uuid, text),
                    request_authority.finish_audited_publication(uuid, text) TO b11_resolver;
                GRANT EXECUTE ON FUNCTION request_authority.reserve_external_mapping(
                    uuid, text, text, text, bytea, text, text, text, bigint, bigint, text, bigint, uuid, bigint) TO b04_proof;
                GRANT SELECT ON request_authority.external_mapping_binding,
                    request_authority.external_mapping_quarantine TO b04_proof;
                """);
        authority.update("UPDATE request_authority.publication_namespace SET last_id = ?", EXACT_PUBLICATION - 1);
        require(Boolean.TRUE.equals(authority.queryForObject("""
                SELECT NOT has_table_privilege('b04_proof', 'request_authority.external_mapping_binding', 'INSERT')
                  AND NOT has_table_privilege('b04_proof', 'request_authority.external_mapping_binding', 'UPDATE')
                  AND NOT has_function_privilege('b11_resolver',
                    'request_authority.reserve_external_mapping(uuid,text,text,text,bytea,text,text,text,bigint,bigint,text,bigint,uuid,bigint)',
                    'EXECUTE')
                """, Boolean.class)) && Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT NOT has_table_privilege('b04_workflow', 'requests.external_mapping', 'UPDATE')
                  AND NOT has_table_privilege('b04_workflow', 'requests.external_mapping', 'DELETE')
                  AND NOT has_table_privilege('b04_observer', 'requests.external_mapping', 'INSERT')
                """, Boolean.class)), "mapping role privilege levels");
        System.out.println("PASS independent mapping migrations are repeatable and runtime roles cannot replace a binding");
    }

    private void exercise(QualifyPostgresql.RuntimeLogin workflowLogin, QualifyPostgresql.ScopeRestore restore,
                          B10FixtureSources sources) throws Exception {
        enable(OWNER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY,
                Grant.RESULT_READ_VERIFY);
        enable(PUBLISHER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME, Grant.CONTEXT_ACCEPT,
                Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH, Grant.EXECUTE_PUBLISH,
                Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID);
        try (var mvc = new B08MvcSession(settings)) {
            rolledBackCreate(mvc);
            var first = target(created(mvc));
            var second = target(created(mvc));
            exactAndScoped(first, second);
            missingOriginalCreate(target(created(mvc)));
            publication(mvc, first);
            transactionalProjection(second);
            concurrentEquivalent(second);
            rewind(second, workflowLogin, restore, sources);
        }
    }

    private void rolledBackCreate(B08MvcSession mvc) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            String key = issue(mvc, ownerBearer);
            var cut = new CreateCut(new CountDownLatch(1), new CountDownLatch(1));
            createCut.set(cut);
            var request = worker.submit(() -> create(mvc, key));
            RequestRef provisional;
            var external = new Key(LOCAL, new byte[]{1, 55});
            try {
                require(cut.staged.await(5, TimeUnit.SECONDS), "Create did not reach the staged receipt cut");
                provisional = new RequestRef(authority.queryForObject("""
                        SELECT request_ref FROM request_authority.creation_reservation
                        ORDER BY created_epoch DESC, created_ordinal DESC LIMIT 1
                        """, String.class));
                expectFence(() -> mappings.bind(MAPPER, external, target(provisional), Optional.empty()));
                require(count(workflow, PROJECTION) == 0 && count(authority, REGISTRY) == 0
                                && committedCreate(provisional) == 0,
                        "mapping or Create evidence escaped from the staged command");
            } finally {
                cut.resume.countDown();
            }
            var response = request.get(7, TimeUnit.SECONDS);
            require(response.status() == 500 && !response.body().contains(provisional.value()),
                    "failed Create disclosed the provisional reference");
            require(mappings.bind(MAPPER, external, target(provisional), Optional.empty()) == Outcome.FENCED
                            && use(mappings, external).isEmpty() && count(authority, REGISTRY) == 0
                            && count(authority, QUARANTINE) == 0 && count(workflow, PROJECTION) == 0
                            && count(authority, "request_authority.creation_reservation") == 1
                            && committedCreate(provisional) == 0,
                    "rollback produced a mapping, projection or public result");
        }
        System.out.println("PASS staged before commit and rolled-back Create retained only the burned identity reservation");
    }

    private void exactAndScoped(Target first, Target second) throws Exception {
        var key = new Key(LOCAL, EXTERNAL);
        var unauthorizedScope = new Key(new Scope("synthetic-unknown", "synthetic-one", "local"), EXTERNAL);
        require(mappings.bind(DENIED, key, first, Optional.empty()) == Outcome.FENCED
                        && mappings.bind(MAPPER, unauthorizedScope, first, Optional.empty()) == Outcome.FENCED
                        && count(authority, REGISTRY) == 0, "unauthorized source scope reached registry");
        require(mappings.bind(MAPPER, key, first, Optional.empty()) == Outcome.PROJECTED
                        && mappings.bind(MAPPER, key, first, Optional.empty()) == Outcome.RECONCILED
                        && use(mappings, key).orElseThrow().target().equals(first),
                "committed Create did not project exactly once");
        for (Scope scope : List.of(OTHER_SOURCE, OTHER_TENANT, OTHER_TYPE)) {
            var scoped = new Key(scope, EXTERNAL);
            require(mappings.bind(MAPPER, scoped, second, Optional.empty()) == Outcome.PROJECTED
                            && use(mappings, scoped).orElseThrow().target().equals(second),
                    "equal bytes joined distinct source scopes");
        }
        for (byte[] bytes : List.of(new byte[]{0, -1, 'a', -64, -128}, new byte[]{0, -1, 'A', -64, -128, 0},
                new byte[]{0, -2, 'A', -64, -128}, new byte[0], new byte[6000])) {
            var different = new Key(LOCAL, bytes);
            require(mappings.bind(MAPPER, different, second, Optional.empty()) == Outcome.PROJECTED
                            && use(mappings, different).orElseThrow().target().equals(second),
                    "byte identity normalized a distinct value");
        }
        var originalBinding = registry.find(key).orElseThrow();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(EXTERNAL));
        require(digest.equals(authority.queryForObject(
                        "SELECT encode(external_id_digest, 'hex') FROM " + REGISTRY + " WHERE binding_id = ?",
                        String.class, originalBinding.id()))
                        && digest.equals(workflow.queryForObject(
                                "SELECT encode(external_id_digest, 'hex') FROM " + PROJECTION + " WHERE binding_id = ?",
                                String.class, originalBinding.id())),
                "raw binary digest did not match on both stores");
        require(mappings.bind(MAPPER, key, second, Optional.empty()) == Outcome.QUARANTINED
                        && mappings.bind(MAPPER, key, second, Optional.empty()) == Outcome.QUARANTINED
                        && count(authority, REGISTRY) == 9 && count(workflow, PROJECTION) == 9
                        && count(authority, QUARANTINE) == 1 && use(mappings, key).isEmpty()
                        && originalBinding.association().target().equals(first)
                        && Boolean.TRUE.equals(authority.queryForObject(
                                "SELECT ref = ? FROM " + REGISTRY + " WHERE binding_id = ?",
                                Boolean.class, first.ref().value(), originalBinding.id())),
                "conflict changed the accepted binding or exposed a quarantined mapping");
        System.out.printf("PASS exact binary binding and source/tenant/type isolation registry=9 projection=9 quarantine=1 sha256=%s%n",
                digest);
    }

    private void missingOriginalCreate(Target target) {
        var key = new Key(LOCAL, new byte[]{77});
        require(workflow.update("DELETE FROM requests.operation_receipt WHERE result_action = 'CREATE' AND result_ref = ?",
                        target.ref().value()) == 1 && committedCreate(target.ref()) == 0,
                "failed to cut original Create evidence");
        require(mappings.bind(MAPPER, key, target, Optional.empty()) == Outcome.FENCED
                        && registry.find(key).isEmpty() && count(authority, REGISTRY) == 9,
                "current row without its original Create commitment reserved a mapping");
        System.out.println("PASS present request and identity reservation without original Create proof were fenced");
    }

    private void publication(B08MvcSession mvc, Target target) throws Exception {
        var key = new Key(PUBLISHED, EXTERNAL);
        var anticipated = new Publication(PublicationId.INITIAL_NAMESPACE, new PublicationId(EXACT_PUBLICATION));
        require(mappings.bind(MAPPER, key, target, Optional.empty()) == Outcome.FENCED
                        && mappings.bind(MAPPER, key, target, Optional.of(anticipated)) == Outcome.FENCED,
                "publication-required identifier accepted an unresolved association");
        require(action(mvc, target.ref(), "verify", ownerBearer).status() == 200, "Verify setup");
        baseline.confirm(workflowProfile.updates().stageAssignment(OPERATOR, target.ref(), PUBLISHER));
        require(action(mvc, target.ref(), "accept", publisherBearer).status() == 200, "Accept setup");
        var pending = action(mvc, target.ref(), "publish", publisherBearer);
        require(pending.status() == 503 && !JSON.readTree(pending.body()).has("publicationId"), "pending Publish wire");
        var binding = journal.activeBinding(admission.writer(), OWNER.organization(), TYPE, target.ref()).orElseThrow();
        require(binding.state() == State.PENDING && binding.allocation().orElseThrow().equals(anticipated.id())
                        && mappings.bind(MAPPER, key, target, Optional.of(anticipated)) == Outcome.FENCED
                        && count(authority, REGISTRY) == 9,
                "local Publish commit without original confirmation reserved a mapping");
        var resolver = workflowProfile.publicationResolver().orElseThrow();
        permitPublicationProjection(false);
        try {
            try {
                resolver.confirm(admission, binding.attempt());
                throw new AssertionError("unpermitted publication projection was committed");
            } catch (AuthorityUnavailableException expected) {
                require(journal.find(binding.attempt()).orElseThrow().state() == State.COMMITTED
                                && mappings.bind(MAPPER, key, target, Optional.of(anticipated)) == Outcome.FENCED
                                && registry.find(key).isEmpty(),
                        "independent publication confirmation without its guarded projection reserved a mapping");
            }
        } finally {
            permitPublicationProjection(true);
        }
        resolver.confirm(admission, binding.attempt());
        var wrongNamespace = new Publication("synthetic-other-namespace", anticipated.id());
        var wrongId = new Publication(anticipated.namespace(), new PublicationId(EXACT_PUBLICATION - 1));
        require(mappings.bind(MAPPER, key, target, Optional.of(wrongNamespace)) == Outcome.FENCED
                        && mappings.bind(MAPPER, key, target, Optional.of(wrongId)) == Outcome.FENCED
                        && registry.find(key).isEmpty(), "incorrect publication tuple entered the registry");
        require(mappings.bind(MAPPER, key, target, Optional.of(anticipated)) == Outcome.PROJECTED
                        && mappings.bind(MAPPER, key, target, Optional.of(anticipated)) == Outcome.RECONCILED
                        && use(mappings, key).orElseThrow().publication().orElseThrow().equals(anticipated),
                "confirmed original publication did not project exactly");
        System.out.println("PASS mapping required the exact confirmed original publication namespace and 64-bit ID");
    }

    private void permitPublicationProjection(boolean enabled) {
        workflow.execute((enabled ? "GRANT INSERT " : "REVOKE INSERT ") + PUBLICATION_COLUMNS
                + " ON requests.publication_confirmation " + (enabled ? "TO " : "FROM ") + "b11_projection");
    }

    private void transactionalProjection(Target target) {
        var key = new Key(LOCAL, new byte[]{12});
        failProjection(key, target);
        require(mappings.recover(MAPPER, key) == Outcome.FENCED && use(mappings, key).isEmpty(),
                "uncommitted projection used registry reservation as original effect");
        require(mappings.bind(MAPPER, key, target, Optional.empty()) == Outcome.RECONCILED
                        && use(mappings, key).orElseThrow().target().equals(target),
                "same certified writer did not finish the original reservation");
        System.out.println("PASS local projection rollback left one independent reservation and no business admission");
    }

    private void failProjection(Key key, Target target) {
        long registryCount = count(authority, REGISTRY);
        long projections = count(workflow, PROJECTION);
        workflow.execute("""
                CREATE CONSTRAINT TRIGGER b18_fixture_projection_failure
                AFTER INSERT ON requests.external_mapping DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION requests.b18_fixture_projection_failure()
                """);
        try {
            try {
                mappings.bind(MAPPER, key, target, Optional.empty());
                throw new AssertionError("failed local commit reported mapping success");
            } catch (CommitOutcomeUnknownException expected) {
                // The fixture knows the server forced rollback after the statement staged the row.
            }
        } finally {
            workflow.execute("DROP TRIGGER b18_fixture_projection_failure ON requests.external_mapping");
        }
        require(count(authority, REGISTRY) == registryCount + 1 && count(workflow, PROJECTION) == projections
                        && registry.find(key).orElseThrow().association().target().equals(target),
                "workflow rollback changed the independent binding or committed a local projection");
    }

    private void concurrentEquivalent(Target target) throws Exception {
        var key = new Key(LOCAL, new byte[]{41});
        var proved = use(mappings, new Key(LOCAL, new byte[]{12})).orElseThrow();
        long prior = count(authority, REGISTRY);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> reserveTogether(key, proved, ready, start));
            var second = workers.submit(() -> reserveTogether(key, proved, ready, start));
            try {
                require(ready.await(3, TimeUnit.SECONDS), "registry contenders were not ready");
            } finally {
                start.countDown();
            }
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            require(a.binding().equals(b.binding()) && a.state() != b.state()
                            && Set.of(a.state(), b.state()).equals(Set.of(Reservation.State.RESERVED, Reservation.State.EQUIVALENT))
                            && count(authority, REGISTRY) == prior + 1 && use(mappings, key).isEmpty(),
                    "concurrent exact identity accepted more than one independent binding");
        }
        require(mappings.bind(MAPPER, key, target, Optional.empty()) == Outcome.RECONCILED
                        && use(mappings, key).orElseThrow().equals(proved),
                "guarded reconciliation did not project the concurrent reservation");
        System.out.println("PASS concurrent registry callers obtained one durable scoped identity and one local projection");
    }

    private Reservation reserveTogether(Key key, Association proved, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        require(start.await(3, TimeUnit.SECONDS), "registry contender missed its start");
        return registry.reserve(key, proved, admission.writer());
    }

    private void rewind(Target target, QualifyPostgresql.RuntimeLogin workflowLogin,
                        QualifyPostgresql.ScopeRestore restore, B10FixtureSources sources) throws Exception {
        long pastCount = count(workflow, PROJECTION);
        var restoredObserver = sources.source(restore.snapshot());
        var restoredCommand = sources.source(() -> connectRestored(workflowLogin));
        var restoredSql = new JdbcTemplate(restoredCommand);
        var committedKey = new Key(LOCAL, new byte[]{31, 0, -1});
        var unprovedKey = new Key(LOCAL, new byte[]{32, 0, -1});
        require(mappings.bind(MAPPER, committedKey, target, Optional.empty()) == Outcome.PROJECTED,
                "original projection setup");
        failProjection(unprovedKey, target);
        var committed = registry.find(committedKey).orElseThrow();
        var unproved = registry.find(unprovedKey).orElseThrow();
        var wrongLineage = new JdbcOriginalMappingEffect(restoredObserver, registry);
        require(original.committed(committed) && !original.committed(unproved) && !wrongLineage.committed(committed),
                "original effect evidence admitted an uncommitted or wrong lineage");
        require(count(restoredSql, PROJECTION) == pastCount && count(workflow, PROJECTION) == pastCount + 1
                        && count(authority, REGISTRY) == pastCount + 2,
                "older backup included later effects or rewound the registry");

        var cloneIdentity = JdbcWorkflowIdentity.inspect(restoredCommand);
        try {
            new JdbcCreateProof(settings.create().proof()).requireWriter(admission, cloneIdentity);
            throw new AssertionError("old workflow certificate admitted clone");
        } catch (AuthorityUnavailableException expected) {
            System.out.println("PASS original writer certificate rejects the restored workflow");
        }
        // The fixture attests this one private recovery lane. B39 owns full public writer recertification.
        var staged = new Admission(admission.genesis(),
                new WriterEpoch(UUID.randomUUID(), Math.incrementExact(admission.writer().generation())));
        var restoredGuard = new PolicyGuard(OWNER.organization(), workflow.queryForObject(
                "SELECT policy_version FROM requests.policy_guard WHERE organization = ?", Long.class,
                OWNER.organization().value()));
        var clone = new JdbcWorkflowIdentity(restoredCommand);
        var projection = new JdbcExternalMappingProjection(restoredCommand);
        var restored = new ExternalMappings(JdbcTransactionRunner.forCreate(restoredCommand),
                new JdbcWorkflowRecords(restoredCommand), B18Postgresql::authorize, guard -> {
                    if (!clone.current().equals(cloneIdentity) || !guard.equals(restoredGuard)) {
                        throw new AuthorityUnavailableException();
                    }
                    return staged;
                }, projection, (guard, ref) -> Optional.empty(), registry, projection, original);
        require(use(restored, committedKey).isEmpty() && use(restored, unprovedKey).isEmpty()
                        && restored.recover(MAPPER, unprovedKey) == Outcome.FENCED
                        && count(restoredSql, PROJECTION) == pastCount,
                "rewind reopened a missing or unproved local projection");
        expectFence(() -> restored.bind(MAPPER, committedKey, target, Optional.empty()));
        require(restored.recover(MAPPER, committedKey) == Outcome.PROJECTED
                        && restored.recover(MAPPER, committedKey) == Outcome.RECONCILED
                        && use(restored, committedKey).orElseThrow().equals(committed.association())
                        && use(restored, unprovedKey).isEmpty() && count(restoredSql, PROJECTION) == pastCount + 1
                        && count(authority, REGISTRY) == pastCount + 2
                        && count(authority, QUARANTINE) == 1
                        && Boolean.FALSE.equals(restoredSql.queryForObject(
                                "SELECT original_effect FROM " + PROJECTION + " WHERE binding_id = ?",
                                Boolean.class, committed.id())),
                "verified recovery did not rebuild exactly one projection with distinct recovery evidence");
        System.out.printf("PASS workflow rewind independent registry=%d live projection=%d old projection=%d restored projection=%d quarantine=1%n",
                pastCount + 2, pastCount + 1, pastCount, pastCount + 1);
    }

    private static Connection connectRestored(QualifyPostgresql.RuntimeLogin login) throws SQLException {
        var settings = new Properties();
        settings.setProperty("user", login.username());
        settings.setProperty("password", login.password());
        settings.setProperty("connectTimeout", "2");
        settings.setProperty("socketTimeout", "4");
        settings.setProperty("sslmode", "disable");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55482/" + login.database(), settings);
    }

    private static Optional<Authorization> authorize(AuthenticatedActor actor, PolicyGuard guard,
                                                       Scope scope, ExternalMappings.Operation operation) {
        return MAPPER.equals(actor) && guard.organization().equals(MAPPER.organization()) && ALLOWED.contains(scope)
                ? Optional.of(new Authorization(Set.of(TYPE), scope.equals(PUBLISHED))) : Optional.empty();
    }

    private static Optional<Association> use(ExternalMappings service, Key key) {
        return service.withVerifiedMapping(MAPPER, key, value -> value);
    }

    private long committedCreate(RequestRef ref) {
        return workflow.queryForObject("SELECT count(*) FROM requests.external_mapping_create_evidence WHERE ref = ?",
                Long.class, ref.value());
    }

    private void enable(AuthenticatedActor actor, Grant... grants) {
        for (Grant grant : grants) {
            baseline.confirm(workflowProfile.updates().stageEnablement(OPERATOR, new GrantKey(actor, grant, Optional.of(TYPE))));
        }
    }

    private static long count(JdbcTemplate database, String table) {
        return database.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private B08MvcSession.Reply create(B08MvcSession mvc, String key) throws Exception {
        return mvc.invoke(new B08MvcSession.Call("/requests", ownerBearer, key, JSON.writeValueAsString(Map.of(
                "type", TYPE.name(), "name", "Synthetic mapping target", "body", "Synthetic mapping body"))));
    }

    private RequestRef created(B08MvcSession mvc) throws Exception {
        var reply = create(mvc, issue(mvc, ownerBearer));
        require(reply.status() == 201, "Create setup status " + reply.status());
        return new RequestRef(JSON.readTree(reply.body()).get("ref").asString());
    }

    private static B08MvcSession.Reply action(B08MvcSession mvc, RequestRef ref, String action, String bearer) throws Exception {
        var context = mvc.invoke(MockMvcRequestBuilders.get("/requests/{ref}/action-context", ref.value())
                .queryParam("action", action), bearer);
        require(context.status() == 200, "action context setup status " + context.status());
        return mvc.invoke(new B08MvcSession.Call("/requests/" + ref.value() + "/actions/" + action,
                bearer, issue(mvc, bearer), JSON.writeValueAsString(Map.of(
                        "expectedRevision", JSON.readTree(context.body()).get("expectedRevision").asString()))));
    }

    private static String issue(B08MvcSession mvc, String bearer) throws Exception {
        var reply = mvc.invoke(new B08MvcSession.Call("/operation-keys", bearer, "", ""));
        require(reply.status() == 201, "key issue setup status " + reply.status());
        return JSON.readTree(reply.body()).get("key").asString();
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(connection, method, arguments);
                    if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                            && sql.startsWith("UPDATE requests.operation_receipt")) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (ignored, operation, operands) -> {
                                    Object updated = invoke(result, operation, operands);
                                    if (operation.getName().equals("executeUpdate")) {
                                        var cut = createCut.getAndSet(null);
                                        if (cut != null) {
                                            cut.staged().countDown();
                                            require(cut.resume().await(5, TimeUnit.SECONDS), "Create cut did not release");
                                            throw new SQLException("B18 forced Create rollback", "55000");
                                        }
                                    }
                                    return updated;
                                });
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

    private static void expectFence(java.util.function.Supplier<Outcome> attempt) {
        try {
            require(attempt.get() == Outcome.FENCED, "unexpected mapping outcome");
        } catch (AuthorityUnavailableException | TransactionBusyException fenced) {
            // An unavailable proof source or a bounded organization lock also suppresses the mapping.
        }
    }

    private static Target target(RequestRef ref) {
        return new Target(OWNER.organization(), TYPE, ref);
    }

    private static AuthenticatedActor service(String principal) {
        return new AuthenticatedActor("synthetic-b18", OWNER.organization(), principal,
                AuthenticatedActor.Kind.SERVICE, Optional.empty());
    }

    private static byte[] secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret());
    }

    private static void require(boolean expected, String message) {
        if (!expected) {
            throw new AssertionError(message);
        }
    }

    private record CreateCut(CountDownLatch staged, CountDownLatch resume) { }
}
