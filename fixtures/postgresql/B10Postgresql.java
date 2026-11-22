import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcEffectiveScopeStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeChange;
import com.company.requests.application.EffectiveScopeStore.Assignment;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeStore.GrantOverride;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.EffectiveScopeUpdates.PendingConfirmation;
import com.company.requests.application.EffectiveScopeUpdates.Permission;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityResolution.Operation;
import com.company.requests.application.ScopeAuthorityResolution.OperatorCredential;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Components;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Native oracle for the opt-in effective access profile. */
public final class B10Postgresql {
    static final OrganizationId ORG = new OrganizationId("b10-local");
    static final OrganizationId OTHER = new OrganizationId("b10-other");
    static final AuthenticatedActor OWNER = actor(ORG, "original", "person-origin");
    static final AuthenticatedActor ASSIGNEE = actor(ORG, "assignee", "person-assignee-東京");
    static final AuthenticatedActor OPERATOR = actor(ORG, "scope-operator", "person-operator");
    private static final AuthenticatedActor ASSIGNER = actor(ORG, "assignment-operator", "person-assigner");
    private static final AuthenticatedActor FOREIGN = actor(OTHER, "original", "person-origin");
    static final RequestType TYPE = RequestType.HR_TICKET;
    private static final RequestType BUG = RequestType.BUG_REPORT;
    private static final IntentDigest.Create INTENT = new IntentDigest.Create(TYPE, "test name", "test body");
    private static final IntentDigest.Create BUG_INTENT = new IntentDigest.Create(BUG, "race name", "race body");
    private static final Set<Field> ALL = Set.of(Field.values());
    private static final Set<Field> RESULT = Set.of(Field.REF, Field.ACTION, Field.RESULT_REVISION, Field.STATE);
    private static final Set<Field> MASK = Set.of(Field.REF, Field.ACTION, Field.RESULT_REVISION, Field.NAME);
    static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 5);
    private static final SyntheticScopePolicy POLICY = new SyntheticScopePolicy(ORG, 1, List.of(
            new SyntheticScopePolicy.Grant(OWNER, true, Set.of(TYPE, BUG), Set.of(TYPE, BUG)),
            new SyntheticScopePolicy.Grant(ASSIGNEE, true, Set.of(TYPE), Set.of(TYPE)),
            new SyntheticScopePolicy.Grant(OPERATOR, false, Set.of(), Set.of()),
            new SyntheticScopePolicy.Grant(ASSIGNER, false, Set.of(), Set.of())));
    private static final Map<AuthenticatedActor, Set<Permission>> OPERATORS = Map.of(
            OPERATOR, Set.of(Permission.values()), ASSIGNER, Set.of(Permission.MOVE_ASSIGNMENT));
    private static final Admission ADMISSION = new Admission(POLICY.withMutationOperators(OPERATORS).genesis(), WRITER);
    private static final RequestRef FOREIGN_REF = new RequestRef("synthetic-foreign-request");
    private static final byte[] ENVELOPE = "bounded-synthetic-result".getBytes(StandardCharsets.UTF_8);
    private static final String ATTEMPT = "request_authority.scope_journal_attempt";
    private static final String DELTA = "request_authority.scope_journal_delta";
    private static final String WITNESS = "requests.scope_authority_witness";
    private static final String PROJECTION = "requests.scope_authority_projection";
    private static final String RECEIPT_CLAIM = "INSERT INTO requests.operation_receipt";

    private final OperatorCredential confirmer = new OperatorCredential(token());
    private final OperatorCredential wrongConfirmer = new OperatorCredential(token());
    private final DataSource workflowAdmin;
    private final JdbcTemplate workflowOwner;
    private final JdbcTemplate proofOwner;
    private final DataSource command;
    private final JdbcTransactionRunner commandTx;
    private final JdbcWorkflowRecords guards;
    private final JdbcScopeAuthorityJournal journal;
    private final Components profile;
    private final SyntheticEffectiveAccessConfiguration.Settings profileSettings;
    private final AtomicReference<Cut> commandCut = new AtomicReference<>();
    private final AtomicReference<Cut> prepareCut = new AtomicReference<>();

    private B10Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                          QualifyPostgresql.SqlConnection createProof, B10FixtureSources sources) throws SQLException {
        this(workflow, proof, projector, resolver, createProof, sources, UnaryOperator.identity());
    }

    B10Postgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                  QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                  QualifyPostgresql.SqlConnection createProof, B10FixtureSources sources,
                  UnaryOperator<Connection> decorate) throws SQLException {
        workflowAdmin = sources.source(workflow.migration());
        var proofAdmin = sources.source(proof.migration());
        workflowOwner = new JdbcTemplate(workflowAdmin);
        proofOwner = new JdbcTemplate(proofAdmin);
        command = sources.source(workflow.runtime(), connection -> decorate.apply(inject(connection, commandCut)));
        commandTx = JdbcTransactionRunner.forCreate(command);
        guards = new JdbcWorkflowRecords(command);
        var preparation = sources.source(proof.runtime(), connection -> inject(connection, prepareCut));
        var original = sources.source(workflow.observer());
        var createAuthority = sources.source(createProof);
        var projectorSource = sources.source(projector);
        var resolverSource = sources.source(resolver);
        journal = JdbcScopeAuthorityJournal.withConfirmedProjection(preparation, original);
        var actors = new SyntheticActorResolver("b10-issuer", "b10-audience", List.of(
                new SyntheticActorResolver.Binding("original", token(), OWNER)));
        var secrets = new OperationSecrets(1, Map.of(1, bytes()), 1, Map.of(1, bytes()));
        var settings = new SyntheticCreateConfiguration.Settings(command, createAuthority, POLICY, WRITER,
                actors, Instant::now, secrets);
        profileSettings = new SyntheticEffectiveAccessConfiguration.Settings(settings, preparation, original,
                projectorSource, resolverSource, OPERATORS);
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(profileSettings);
        provision(workflowAdmin, proofAdmin);
        require(privilege(command, "request_assignment", "assignee_person_id", "UPDATE")
                        && !privilege(command, "request_assignment", "ref", "UPDATE")
                        && !privilege(command, "request", "originating_person_id", "UPDATE")
                        && !privilege(projectorSource, "request_assignment", "assignee_person_id", "UPDATE")
                        && !privilege(original, "policy_guard", "policy_version", "UPDATE"),
                "workflow role grants exceeded the guarded write boundary");
        require(!globalPrivilege(preparation, DELTA, "SELECT") && !globalPrivilege(createAuthority, ATTEMPT, "SELECT")
                        && !globalPrivilege(preparation, "request_authority.release_prepared", "INSERT")
                        && !globalPrivilege(resolverSource, ATTEMPT, "UPDATE"),
                "proof roles crossed the preparation, creation or resolution boundaries");
        System.out.println("PASS separate command, observer, projector, Create proof, journal and resolver role grants");
    }

    SyntheticEffectiveAccessConfiguration.Settings settings() {
        return profileSettings;
    }

    Components components() {
        return profile;
    }

    void pauseBeforeScopeWitness(CountDownLatch entered, CountDownLatch resume) {
        commandCut.set(new Cut(WITNESS, CutAt.BEFORE_EXECUTE, entered, resume));
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.effectiveScopeAccess(args, (workflow, proof, projector, resolver, createProof) -> {
                try (var sources = new B10FixtureSources()) {
                    new B10Postgresql(workflow, proof, projector, resolver, createProof, sources).exercise();
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    System.err.println("JDBC SQLSTATE " + sql.getSQLState());
                } else if (cause instanceof AssertionError assertion) {
                    System.err.println("B10 assertion: " + assertion.getMessage());
                } else {
                    System.err.println("B10 fixture: " + cause.getClass().getSimpleName());
                }
                Arrays.stream(cause.getStackTrace()).limit(8).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void provision(DataSource workflowAdmin, DataSource proofAdmin) {
        var migrations = new JdbcWorkflowMigrations(workflowAdmin);
        migrations.migrateScopeProjection();
        migrations.migrateEffectiveScopeAccess();
        migrations.migrateEffectiveScopeAccess();
        workflowOwner.execute("""
                GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer, b11_projection;
                GRANT SELECT, UPDATE (policy_version) ON requests.policy_guard TO b04_workflow, b11_projection;
                GRANT SELECT, INSERT ON requests.request, requests.request_body, requests.transition_fact,
                    requests.operation_receipt, requests.request_assignment, requests.scope_grant_override,
                    requests.request_visibility_fact TO b04_workflow;
                GRANT UPDATE (result_action, result_ref, result_revision, result_state, publication_namespace,
                    publication_id, original_status) ON requests.operation_receipt TO b04_workflow;
                GRANT UPDATE (allowed, applied_version) ON requests.scope_grant_override TO b04_workflow;
                GRANT UPDATE (assignee_person_id, assignment_version) ON requests.request_assignment TO b04_workflow;
                GRANT UPDATE (fact_version, assignee_only, field_mask, applied_version)
                    ON requests.request_visibility_fact TO b04_workflow;
                GRANT INSERT ON requests.scope_authority_witness TO b04_workflow;
                GRANT SELECT ON requests.scope_authority_projection TO b04_workflow;
                GRANT SELECT ON requests.policy_guard, requests.scope_authority_projection,
                    requests.scope_authority_witness TO b04_observer;
                GRANT SELECT, INSERT, UPDATE ON requests.scope_authority_projection TO b11_projection;
                """);
        workflowOwner.update("INSERT INTO requests.policy_guard VALUES (?, 1), (?, 1)", ORG.value(), OTHER.value());
        commandTx.run(() -> {
            var foreign = Lifecycle.create(new Lifecycle.Creation(FOREIGN_REF, TYPE, OTHER, "other request",
                            new RequestBody("synthetic foreign payload"), new CreationOrder(1, 1),
                            FOREIGN.person().orElseThrow()),
                    new TransitionFact.Occurrence(Instant.now(), FOREIGN.principal()));
            guards.insert(foreign.request());
            guards.insertBody(OTHER, FOREIGN_REF, foreign.body());
            guards.appendFact(OTHER, foreign.transition());
            return null;
        });
        var provisioning = new JdbcAuthorityProvisioning(proofAdmin);
        provisioning.migrate();
        provisioning.certifyWriter(WRITER);
        provisioning.certifyWorkflow(WRITER, JdbcWorkflowIdentity.inspect(command));
        provisioning.certifyGenesis(ADMISSION.genesis());
        provisioning.certifyGenesis(new SyntheticScopePolicy(OTHER, 1, List.of()).genesis());
        provisioning.migrateScopeJournal();
        provisioning.migrateScopeResolver();
        var operator = UUID.randomUUID();
        provisioning.authorizeScopeOperator(operator, confirmer, ORG, Operation.CONFIRM);
        provisioning.authorizeScopeOperator(operator, confirmer, ORG, Operation.ABORT);
        provisioning.authorizeScopeOperator(UUID.randomUUID(), wrongConfirmer, OTHER, Operation.CONFIRM);
        proofOwner.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b04_proof, b11_resolver, b10_create_proof;
                GRANT SELECT ON request_authority.scope_genesis, request_authority.writer_epoch,
                    request_authority.workflow_certificate TO b04_proof, b11_resolver, b10_create_proof;
                GRANT SELECT ON request_authority.scope_journal_head, request_authority.scope_journal_attempt,
                    request_authority.scope_journal_confirmation, request_authority.scope_journal_terminal_check
                    TO b04_proof, b11_resolver;
                GRANT INSERT (attempt_id, organization, generation, workflow_transaction, before_version, after_version,
                    delta_digest) ON request_authority.scope_journal_attempt TO b04_proof;
                GRANT UPDATE (sequence) ON request_authority.scope_journal_attempt TO b04_proof;
                GRANT INSERT ON request_authority.scope_journal_delta TO b04_proof;
                GRANT UPDATE (last_sequence) ON request_authority.scope_journal_head TO b04_proof;
                GRANT EXECUTE ON FUNCTION request_authority.apply_scope_terminal(uuid, text) TO b11_resolver;
                GRANT INSERT ON request_authority.scope_journal_confirmation TO b11_resolver;
                GRANT SELECT, INSERT, UPDATE (terminal_state, outcome) ON request_authority.scope_resolver_audit TO b11_resolver;
                GRANT SELECT ON request_authority.scope_resolver_operator, request_authority.scope_resolver_grant,
                    request_authority.scope_resolver_delta_check TO b11_resolver;
                GRANT SELECT ON request_authority.creation_counter, request_authority.creation_reservation TO b10_create_proof;
                GRANT INSERT ON request_authority.creation_reservation, request_authority.release_prepared TO b10_create_proof;
                GRANT UPDATE (last_ordinal) ON request_authority.creation_counter TO b10_create_proof;
                """);
        require(List.of(1, 2, 3, 4, 5, 6).equals(workflowOwner.queryForList(
                "SELECT version FROM requests.schema_migration ORDER BY version", Integer.class)), "V005-to-V006 migration history");
    }

    private void exercise() throws Exception {
        var first = create(OWNER, INTENT);
        var second = create(OWNER, INTENT);
        operatorDigestCut(second);
        require(fields(OWNER, first.ref()).equals(ALL) && fields(ASSIGNEE, first.ref()).equals(RESULT),
                "initial private field membership");
        expect(IllegalStateException.class, () -> commandTx.run(() ->
                profile.access().permittedFields(OWNER, guards.find(ORG).orElseThrow(), first.ref())));
        expect(AccessDeniedException.class, () -> profile.updates().stageAssignment(OWNER, first.ref(), ASSIGNEE));
        expect(AccessDeniedException.class, () -> profile.updates().stageAssignment(ASSIGNER, first.ref(), FOREIGN));
        expect(AccessDeniedException.class, () -> profile.updates().stageAssignment(FOREIGN, first.ref(), ASSIGNEE));
        expect(AccessDeniedException.class, () -> profile.updates().stageAssignment(
                ASSIGNER, FOREIGN_REF, ASSIGNEE));
        expect(AccessDeniedException.class, () -> profile.updates().stageVisibility(ASSIGNER, first.ref(), true, ALL));
        expect(AccessDeniedException.class, () -> profile.updates().stageRevocation(OPERATOR, key(FOREIGN, Grant.CREATE, TYPE)));
        require(fields(OWNER, FOREIGN_REF).isEmpty() && count(proofOwner, ATTEMPT) == 0 && version() == 1,
                "cross-scope read or unauthorized mutation exceeded its scope");

        var old = new Assignment(OWNER.person().orElseThrow(), 1);
        var next = new Assignment(ASSIGNEE.person().orElseThrow(), 2);
        var moved = profile.updates().stageAssignment(ASSIGNER, first.ref(), ASSIGNEE);
        exact(moved, new EffectiveScopeChange(new PolicyGuard(ORG, 1), ASSIGNER,
                new EffectiveScopeChange.Move(first.ref(), old, next)));
        var assignment = commandTx.run(() -> new JdbcEffectiveScopeStore(command).assignment(
                guards.lockShared(ORG).orElseThrow(), first.ref()).orElseThrow());
        String origin = workflowOwner.queryForObject("SELECT originating_person_id FROM requests.request WHERE ref = ?",
                String.class, first.ref().value());
        require(assignment.equals(next) && old.person().value().equals(origin), "effective assignment and immutable origin");
        deferred(first);
        expect(AccessDeniedException.class, () -> profile.resolver().resolve(wrongConfirmer,
                ADMISSION, moved.attemptId(), Operation.CONFIRM));
        confirm(moved);
        require(fields(OWNER, first.ref()).equals(RESULT) && fields(ASSIGNEE, first.ref()).equals(ALL),
                "new assignment controls current private fields");
        System.out.println("PASS assignment privileges, stable origin and changed private field membership");

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            claimOrder(workers);
            releaseWins(workers, first);
            writerWins(workers, second);
            effectWins(workers);
            checkpointCut(second);
            rollbackCut(false);
            rollbackCut(true);
            enableGrants(second);
            confirmedRevokeRollsBackClaim(workers);
        }
        require(count(workflowOwner, WITNESS) == 9 && count(proofOwner, ATTEMPT) == 11,
                "committed and aborted counts after exact native cuts");
        System.out.printf("PASS final version=%d, witnesses=%d, independent attempts=%d, confirmed prefix=%d%n",
                version(), count(workflowOwner, WITNESS), count(proofOwner, ATTEMPT),
                journal.confirmedAuthority().current(ADMISSION).sequence());
    }

    private void operatorDigestCut(Created request) {
        var priorAttempts = count(proofOwner, ATTEMPT);
        var priorPreparation = preparations();
        var forged = new SyntheticEffectiveAccessConfiguration.Settings(profileSettings.create(),
                profileSettings.preparation(), profileSettings.originalObserver(), profileSettings.projection(),
                profileSettings.resolver(), Map.of(OPERATOR, Set.of(Permission.values()),
                        ASSIGNER, Set.of(Permission.MOVE_ASSIGNMENT, Permission.ENABLE_GRANT)));
        String expected = ADMISSION.genesis().policyDigest();
        require(!expected.equals(forged.effectivePolicy().genesis().policyDigest()),
                "different administrative rights retained the certified operator digest");
        var altered = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(forged);
        expect(AuthorityUnavailableException.class, () -> altered.updates().stageEnablement(
                ASSIGNER, key(ASSIGNEE, Grant.CREATE, BUG)));
        expect(AuthorityUnavailableException.class, () -> altered.create().execute(
                OWNER, request.key(), request.intent(), B10Postgresql::unexpectedEncode));
        require(count(proofOwner, ATTEMPT) == priorAttempts && preparations() == priorPreparation
                        && version() == 1 && profile.access().requireCreate(OWNER, TYPE).equals(ADMISSION),
                "operator digest mismatch had effects or disturbed matching scope");
        System.out.println("PASS altered operator rights fenced both mutation and receipt admission before effects");
    }

    private void enableGrants(Created original) {
        var create = key(ASSIGNEE, Grant.CREATE, BUG);
        var result = key(ASSIGNEE, Grant.CREATE_RESULT, BUG);
        var previous = key(OWNER, Grant.CREATE, TYPE);
        long attempts = count(proofOwner, ATTEMPT);
        expect(AccessDeniedException.class, () -> profile.updates().stageEnablement(ASSIGNER, create));
        expect(AccessDeniedException.class, () -> profile.updates().stageRevocation(OPERATOR, create));
        expect(AccessDeniedException.class, () -> profile.updates().stageEnablement(OPERATOR, key(FOREIGN, Grant.CREATE, BUG)));
        expect(IllegalArgumentException.class, () -> profile.updates().stageEnablement(OPERATOR, key(ASSIGNEE, Grant.CREATE, TYPE)));
        expect(IllegalArgumentException.class, () -> profile.updates().stageRevocation(OPERATOR, previous));
        require(count(proofOwner, ATTEMPT) == attempts, "no-op or unauthorized grant change opened a journal attempt");

        var issued = profile.create().issue(ASSIGNEE).key();
        expect(AccessDeniedException.class, () -> profile.create().execute(ASSIGNEE, issued, BUG_INTENT,
                B10Postgresql::unexpectedEncode));
        var enabled = profile.updates().stageEnablement(OPERATOR, create);
        exact(enabled, new EffectiveScopeChange(new PolicyGuard(ORG, 6), OPERATOR,
                new EffectiveScopeChange.SetGrant(create, Optional.empty(), false, true)));
        expect(AuthorityUnavailableException.class, () -> profile.create().execute(ASSIGNEE, issued, BUG_INTENT,
                B10Postgresql::unexpectedEncode));
        deferred(original);
        confirm(enabled);
        long receipts = count(workflowOwner, "requests.operation_receipt");
        long releases = preparations();
        require(profile.create().execute(ASSIGNEE, issued, BUG_INTENT, B10Postgresql::unexpectedEncode)
                        == CreateCommand.Rejection.CONCEALED
                        && count(workflowOwner, "requests.operation_receipt") == receipts + 1
                        && preparations() == releases,
                "affirmative Create grant must allow one effect while the independent result grant is absent");
        expect(AccessDeniedException.class, () -> replay(original, B10Postgresql::unexpectedEncode));

        var resultEnabled = profile.updates().stageEnablement(OPERATOR, result);
        exact(resultEnabled, new EffectiveScopeChange(new PolicyGuard(ORG, 7), OPERATOR,
                new EffectiveScopeChange.SetGrant(result, Optional.empty(), false, true)));
        confirm(resultEnabled);
        require(profile.create().execute(ASSIGNEE, issued, BUG_INTENT, ignored -> ENVELOPE) instanceof CreateCommand.Released
                        && count(workflowOwner, "requests.operation_receipt") == receipts + 1
                        && preparations() == releases + 1,
                "affirmative result grant must release the existing receipt once without a new effect");

        var reinstated = profile.updates().stageEnablement(OPERATOR, previous);
        exact(reinstated, new EffectiveScopeChange(new PolicyGuard(ORG, 8), OPERATOR,
                new EffectiveScopeChange.SetGrant(previous, Optional.of(new GrantOverride(false, 4)), false, true)));
        deferred(original);
        confirm(reinstated);
        require(replay(original, ignored -> ENVELOPE) instanceof CreateCommand.Released
                        && count(workflowOwner, "requests.operation_receipt") == receipts + 1,
                "confirmed reinstatement must reopen the original exact retry");
        System.out.println("PASS journaled grant enablement, result permission and reinstatement activated only after confirmation");
    }

    private void confirmedRevokeRollsBackClaim(ExecutorService workers) throws Exception {
        String key = profile.create().issue(ASSIGNEE).key();
        long receipts = count(workflowOwner, "requests.operation_receipt");
        long requests = count(workflowOwner, "requests.request");
        long reservations = count(proofOwner, "request_authority.creation_reservation");
        long releases = preparations();
        var grant = key(ASSIGNEE, Grant.CREATE, BUG);
        var barrier = new Cut(RECEIPT_CLAIM, CutAt.AFTER_EXECUTE);
        commandCut.set(barrier);
        var creating = workers.submit(() -> expect(AccessDeniedException.class, () ->
                profile.create().execute(ASSIGNEE, key, BUG_INTENT, B10Postgresql::unexpectedEncode)));
        try {
            await(barrier.entered());
            var revoked = profile.updates().stageRevocation(OPERATOR, grant);
            exact(revoked, new EffectiveScopeChange(new PolicyGuard(ORG, 9), OPERATOR,
                    new EffectiveScopeChange.SetGrant(grant, Optional.of(new GrantOverride(true, 7)), true, false)));
            confirm(revoked);
        } finally {
            barrier.resume().countDown();
        }
        creating.get(4, TimeUnit.SECONDS);
        require(count(workflowOwner, "requests.operation_receipt") == receipts
                        && count(workflowOwner, "requests.request") == requests
                        && count(proofOwner, "request_authority.creation_reservation") == reservations
                        && preparations() == releases,
                "guarded denial left a receipt claim, request, reservation or preparation");
        System.out.println("PASS confirmed revocation between claim and guard rolled back the tentative Create claim");
    }

    private void claimOrder(ExecutorService workers) throws Exception {
        String key = profile.create().issue(OWNER).key();
        long receipts = count(workflowOwner, "requests.operation_receipt");
        long requests = count(workflowOwner, "requests.request");
        long reservations = count(proofOwner, "request_authority.creation_reservation");
        long preparations = preparations();
        var target = new AtomicReference<RequestRef>();
        var barrier = new Cut(RECEIPT_CLAIM, CutAt.AFTER_EXECUTE);
        commandCut.set(barrier);
        var winner = workers.submit(() -> profile.create().execute(OWNER, key, INTENT, receipt -> {
            target.set(receipt.resultRef());
            return ENVELOPE;
        }));
        try {
            await(barrier.entered());
            requireUnheldGuard();
            var contender = workers.submit(() -> profile.create().execute(OWNER, key, INTENT,
                    B10Postgresql::unexpectedEncode));
            requireWaiting("%" + RECEIPT_CLAIM + "%", "receipt key");
            requireUnheldGuard();
            require(count(workflowOwner, "requests.operation_receipt") == receipts,
                    "uncommitted tentative claim became visible");
            require(contender.get(4, TimeUnit.SECONDS) == CreateCommand.Rejection.OUTCOME_UNKNOWN,
                    "contended claim must defer after its bounded key wait");
        } finally {
            barrier.resume().countDown();
        }
        require(winner.get(4, TimeUnit.SECONDS) instanceof CreateCommand.Released, "winner must commit and release");
        require(profile.create().execute(OWNER, key, INTENT, receipt -> {
            require(receipt.resultRef().equals(target.get()), "exact replay returned a different request");
            return ENVELOPE;
        }) instanceof CreateCommand.Released, "exact replay did not release after contender rollback");
        require(count(workflowOwner, "requests.operation_receipt") == receipts + 1
                        && count(workflowOwner, "requests.request") == requests + 1
                        && count(proofOwner, "request_authority.creation_reservation") == reservations + 1
                        && preparations() == preparations + 2, "key contention duplicated effects or releases");
        System.out.println("PASS receipt key waited while the third session acquired the organization guard NOWAIT; "
                + "contender rolled back and winner replayed exactly with one effect");
    }

    private void requireUnheldGuard() throws SQLException {
        try (var connection = workflowAdmin.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT policy_version FROM requests.policy_guard WHERE organization = ? FOR UPDATE NOWAIT
                     """)) {
            connection.setAutoCommit(false);
            statement.setString(1, ORG.value());
            try (var rows = statement.executeQuery()) {
                require(rows.next(), "independent command guard missing");
            } catch (SQLException failure) {
                if ("55P03".equals(failure.getSQLState())) {
                    throw new AssertionError("tentative receipt claim held the organization guard SQLSTATE=55P03");
                }
                throw failure;
            } finally {
                connection.rollback();
            }
        }
    }

    private void releaseWins(ExecutorService workers, Created first) throws Exception {
        var entered = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        long prepared = preparations();
        var handoff = workers.submit(() -> replay(first, receipt -> {
            entered.countDown();
            await(complete);
            return ENVELOPE;
        }));
        PendingConfirmation update;
        await(entered);
        try {
            var writer = workers.submit(() -> profile.updates().stageVisibility(OPERATOR, first.ref(), false, MASK));
            requireWaiting("FOR UPDATE");
            complete.countDown();
            require(handoff.get(4, TimeUnit.SECONDS) instanceof CreateCommand.Released, "earlier handoff must win");
            update = writer.get(4, TimeUnit.SECONDS);
        } finally {
            complete.countDown();
        }
        exact(update, new EffectiveScopeChange(new PolicyGuard(ORG, 2), OPERATOR,
                new EffectiveScopeChange.SetVisibility(first.ref(), Visibility.INITIAL, new Visibility(1, false, MASK))));
        deferred(first);
        confirm(update);
        require(replay(first, B10Postgresql::unexpectedEncode) == CreateCommand.Rejection.CONCEALED,
                "missing current receipt field must conceal prior receipt");
        require(fields(OWNER, first.ref()).equals(Set.of(Field.REF, Field.ACTION, Field.RESULT_REVISION))
                        && fields(ASSIGNEE, first.ref()).equals(MASK),
                "field mask and assignment must compose");
        require(preparations() == prepared + 1, "only earlier permitted release may append preparation");
        System.out.println("PASS shared handoff won before blocked visibility write; current field mask concealed later replay");
    }

    private void writerWins(ExecutorService workers, Created second) throws Exception {
        var grant = key(OWNER, Grant.CREATE, TYPE);
        String unused = profile.create().issue(OWNER).key();
        long priorRequests = count(workflowOwner, "requests.request");
        long priorReservations = count(proofOwner, "request_authority.creation_reservation");
        long prepared = preparations();
        var barrier = new Cut(WITNESS, CutAt.BEFORE_EXECUTE);
        commandCut.set(barrier);
        var writer = workers.submit(() -> profile.updates().stageRevocation(OPERATOR, grant));
        await(barrier.entered());
        PendingConfirmation update;
        try {
            var waiting = workers.submit(() -> expect(AuthorityUnavailableException.class, () ->
                    replay(second, B10Postgresql::unexpectedEncode)));
            requireWaiting("FOR SHARE");
            barrier.resume().countDown();
            update = writer.get(4, TimeUnit.SECONDS);
            waiting.get(4, TimeUnit.SECONDS);
        } finally {
            barrier.resume().countDown();
        }
        exact(update, new EffectiveScopeChange(new PolicyGuard(ORG, 3), OPERATOR, new EffectiveScopeChange.SetGrant(grant, Optional.empty(), true, false)));
        deferred(second);
        confirm(update);
        if (Boolean.getBoolean("requests.b10.legacyRed")) {
            var legacy = new CurrentAccess(journal, WRITER, List.of(profileSettings.effectivePolicy()));
            expect(AccessDeniedException.class, () -> legacy.requireCreate(OWNER, TYPE));
        }
        expect(AccessDeniedException.class, () -> replay(second, B10Postgresql::unexpectedEncode));
        expect(AccessDeniedException.class, () ->
                profile.create().execute(OWNER, unused, INTENT, B10Postgresql::unexpectedEncode));
        require(count(workflowOwner, "requests.request") == priorRequests
                        && count(proofOwner, "request_authority.creation_reservation") == priorReservations
                        && preparations() == prepared,
                "prior grant authorized a later preparation, reservation or effect");
        require(profile.create().issue(OWNER).key() != null && fields(OWNER, second.ref()).contains(Field.REF),
                "typed Create revocation preserves independent issuance and field grants");
        System.out.println("PASS prior exclusive writer fenced waiting replay; confirmed grant denied replay and unused key");
    }

    private void effectWins(ExecutorService workers) throws Exception {
        var barrier = new Cut("INSERT INTO requests.request_body", CutAt.BEFORE_EXECUTE);
        String key = profile.create().issue(OWNER).key();
        long prior = count(workflowOwner, "requests.request");
        long priorReceipts = count(workflowOwner, "requests.operation_receipt");
        commandCut.set(barrier);
        var coming = workers.submit(() -> {
            try {
                profile.create().execute(OWNER, key, BUG_INTENT, ignored -> ENVELOPE);
            } catch (AccessDeniedException | AuthorityUnavailableException expected) {
                // The subsequent handoff can follow the writer queued behind the effect.
            }
        });
        await(barrier.entered());
        PendingConfirmation update;
        var grant = key(OWNER, Grant.CREATE, BUG);
        try {
            var writer = workers.submit(() -> profile.updates().stageRevocation(OPERATOR, grant));
            requireWaiting("FOR UPDATE");
            barrier.resume().countDown();
            coming.get(4, TimeUnit.SECONDS);
            update = writer.get(4, TimeUnit.SECONDS);
        } finally {
            barrier.resume().countDown();
        }
        exact(update, new EffectiveScopeChange(new PolicyGuard(ORG, 4), OPERATOR, new EffectiveScopeChange.SetGrant(grant, Optional.empty(), true, false)));
        confirm(update);
        require(count(workflowOwner, "requests.request") == prior + 1
                        && count(workflowOwner, "requests.operation_receipt") == priorReceipts + 1,
                "earlier effect and receipt did not survive revocation");
        expect(AccessDeniedException.class, () ->
                profile.create().execute(OWNER, key, BUG_INTENT, B10Postgresql::unexpectedEncode));
        System.out.println("PASS earlier exclusive Create effect and receipt survived the blocked writer; its later replay was denied");
    }

    private void checkpointCut(Created second) {
        var visible = profile.updates().stageVisibility(OPERATOR, second.ref(), true, RESULT);
        exact(visible, new EffectiveScopeChange(new PolicyGuard(ORG, 5), OPERATOR,
                new EffectiveScopeChange.SetVisibility(second.ref(), Visibility.INITIAL, new Visibility(1, true, RESULT))));
        confirm(visible);
        require(fields(ASSIGNEE, second.ref()).isEmpty(), "assignee-only fact must conceal from other actors");
        String digest = workflowOwner.queryForObject("SELECT prefix_digest FROM " + PROJECTION, String.class);
        workflowOwner.update("UPDATE " + PROJECTION + " SET prefix_digest = ?", "0".repeat(64));
        expect(AuthorityUnavailableException.class, () -> fields(OWNER, second.ref()));
        workflowOwner.update("UPDATE " + PROJECTION + " SET prefix_digest = ?", digest);
        UUID attempt = visible.attemptId();
        String confirmations = "request_authority.scope_journal_confirmation";
        String proof = proofOwner.queryForObject("DELETE FROM " + confirmations
                + " WHERE attempt_id = ? RETURNING evidence_digest", String.class, attempt);
        expect(AuthorityUnavailableException.class, () -> fields(OWNER, second.ref()));
        proofOwner.update("INSERT INTO " + confirmations + " VALUES (?, ?)", attempt, proof);
        require(fields(OWNER, second.ref()).equals(RESULT), "exact restored checkpoint and confirmation must reopen");
        System.out.println("PASS changed projection and withheld confirmation each fenced current field access");
    }

    private void rollbackCut(boolean lostPrepare) {
        long version = version();
        long witnesses = count(workflowOwner, WITNESS);
        var grant = new GrantKey(ASSIGNEE, Grant.KEY_ISSUE, Optional.empty());
        var cut = new Cut(DELTA, CutAt.LOST_COMMIT);
        if (lostPrepare) {
            prepareCut.set(cut);
        } else {
            workflowOwner.execute("REVOKE INSERT ON " + WITNESS + " FROM b04_workflow");
        }
        try {
            expect(lostPrepare ? AuthorityUnavailableException.class : org.springframework.dao.DataAccessException.class,
                    () -> profile.updates().stageRevocation(OPERATOR, grant));
        } finally {
            if (!lostPrepare) {
                workflowOwner.execute("GRANT INSERT ON " + WITNESS + " TO b04_workflow");
            }
        }
        require((!lostPrepare || cut.entered().getCount() == 0) && version() == version
                        && count(workflowOwner, WITNESS) == witnesses && count(workflowOwner, "requests.scope_grant_override") == 2,
                "fault cut left a local grant effect");
        var pending = journal.findUnresolved(ADMISSION).orElseThrow();
        require(pending.state() == State.PENDING, "fault cut must leave an independently numbered PENDING entry");
        expect(AuthorityUnavailableException.class, () -> profile.create().issue(ASSIGNEE));
        require(profile.resolver().resolve(confirmer, ADMISSION, pending.attempt().id(), Operation.ABORT) == State.ABORTED
                && profile.create().issue(ASSIGNEE).key() != null, "audited original rollback must reopen issuance");
        System.out.println(lostPrepare ? "PASS lost durable prepare reply blocked effects until audited rollback"
                : "PASS witness insertion failure rolled back the local grant and version; audited rollback reopened");
    }

    void confirm(PendingConfirmation result) {
        require(profile.resolver().resolve(confirmer, ADMISSION, result.attemptId(), Operation.CONFIRM) == State.COMMITTED,
                "audited confirmation failed");
        var checkpoint = journal.confirmedAuthority().current(ADMISSION);
        require(checkpoint.policyVersion() == result.effectiveVersion() && checkpoint.sequence() == result.sequence()
                && checkpoint.policyVersion() == version(), "exact confirmed policy version and sequence");
    }

    private void exact(PendingConfirmation result, EffectiveScopeChange change) {
        var delta = change.delta();
        var entry = journal.find(ADMISSION, result.attemptId()).orElseThrow();
        String digest = workflowOwner.queryForObject("SELECT delta_digest FROM " + WITNESS
                        + " WHERE attempt_id = ? AND sequence = ? AND workflow_transaction = CAST(? AS xid8)",
                String.class, result.attemptId(), result.sequence(), entry.attempt().workflowTransaction());
        byte[] protectedDelta = proofOwner.queryForObject("SELECT exact_delta FROM " + DELTA + " WHERE attempt_id = ?",
                byte[].class, result.attemptId());
        require(entry.state() == State.PENDING && entry.attempt().beforeVersion() == change.before().policyVersion()
                        && entry.attempt().afterVersion() == result.effectiveVersion()
                        && entry.attempt().deltaDigest().equals(delta.digest()) && delta.digest().equals(digest)
                        && Arrays.equals(delta.bytes(), protectedDelta) && version() == result.effectiveVersion(),
                "journal delta, local witness, effective version and mutation do not agree");
        System.out.printf("PASS exact scope delta sequence=%d before=%d after=%d sha256=%s%n",
                result.sequence(), entry.attempt().beforeVersion(), result.effectiveVersion(), digest);
    }

    private Created create(AuthenticatedActor actor, IntentDigest.Create intent) {
        String key = profile.create().issue(actor).key();
        var request = new AtomicReference<RequestRef>();
        long started = System.nanoTime();
        var result = profile.create().execute(actor, key, intent, receipt -> {
            request.set(receipt.resultRef());
            return ENVELOPE;
        });
        require(result instanceof CreateCommand.Released value && Arrays.equals(value.envelope(), ENVELOPE),
                "initial Create outcome=" + (result instanceof CreateCommand.Rejection rejection ? rejection : "envelope")
                        + ", elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        + ", receiptRows=" + count(workflowOwner, "requests.operation_receipt")
                        + ", preparations=" + preparations());
        return new Created(key, request.get(), intent);
    }

    private CreateCommand.Result replay(Created request, Function<OperationReceipt, byte[]> encode) {
        return profile.create().execute(OWNER, request.key(), request.intent(), encode);
    }

    private void deferred(Created request) {
        long before = preparations();
        expect(AuthorityUnavailableException.class, () -> replay(request, B10Postgresql::unexpectedEncode));
        expect(AuthorityUnavailableException.class, () -> fields(OWNER, request.ref()));
        require(before == preparations(), "unconfirmed scope prepared protected output");
    }

    private Set<Field> fields(AuthenticatedActor actor, RequestRef ref) {
        return commandTx.run(() -> profile.access().permittedFields(actor, guards.lockShared(ORG).orElseThrow(), ref));
    }

    private long version() {
        return workflowOwner.queryForObject("SELECT policy_version FROM requests.policy_guard WHERE organization = ?",
                Long.class, ORG.value());
    }

    private long preparations() {
        return count(proofOwner, "request_authority.release_prepared");
    }

    private static long count(JdbcTemplate connection, String table) {
        return connection.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static boolean privilege(DataSource source, String table, String column, String privilege) {
        return Boolean.TRUE.equals(new JdbcTemplate(source).queryForObject(
                "SELECT has_column_privilege(?, ?, ?)", Boolean.class, "requests." + table, column, privilege));
    }

    private static boolean globalPrivilege(DataSource source, String table, String privilege) {
        return Boolean.TRUE.equals(new JdbcTemplate(source).queryForObject(
                "SELECT has_table_privilege(?, ?)", Boolean.class, table, privilege));
    }

    void requireWaiting(String lockClause) throws SQLException, InterruptedException {
        requireWaiting("%FROM requests.policy_guard%" + lockClause + "%", lockClause);
    }

    private void requireWaiting(String sqlPattern, String label) throws SQLException, InterruptedException {
        long started = System.nanoTime();
        long end = started + TimeUnit.MILLISECONDS.toNanos(900);
        try (var connection = workflowAdmin.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM pg_stat_activity
                     WHERE usename = 'b04_workflow' AND wait_event_type = 'Lock'
                       AND query LIKE ? AND cardinality(pg_blocking_pids(pid)) > 0
                     """)) {
            statement.setString(1, sqlPattern);
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        System.out.printf("PASS observed real PostgreSQL %s wait after %d ms%n", label,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        return;
                    }
                }
                Thread.sleep(4);
            } while (System.nanoTime() < end);
        }
        throw new AssertionError("Did not observe the expected native wait " + label);
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(4, TimeUnit.SECONDS), "bounded fixture barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("fixture barrier was interrupted");
        }
    }

    private static byte[] unexpectedEncode(OperationReceipt receipt) {
        throw new AssertionError("Protected receipt reached its serializer");
    }

    private static GrantKey key(AuthenticatedActor actor, Grant grant, RequestType type) {
        return new GrantKey(actor, grant, Optional.of(type));
    }

    private static AuthenticatedActor actor(OrganizationId organization, String principal, String person) {
        return new AuthenticatedActor("b10-issuer", organization, principal, AuthenticatedActor.Kind.HUMAN,
                Optional.of(new PersonId(person)));
    }

    private static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes());
    }

    private static byte[] bytes() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private static Connection inject(Connection connection, AtomicReference<Cut> current) {
        var dropped = new AtomicBoolean();
        return wrap(Connection.class, connection, (target, method, args) -> {
            if (method.getName().equals("commit") && dropped.get()) {
                invoke(target, method, args);
                throw new SQLException("synthetic prepare reply lost", "08006");
            }
            Object result = invoke(target, method, args);
            var cut = current.get();
            if (cut != null && method.getName().equals("prepareStatement")
                    && args[0] instanceof String sql && sql.contains(cut.fragment())
                    && current.compareAndSet(cut, null)) {
                return wrap(PreparedStatement.class, (PreparedStatement) result, (statement, operation, values) -> {
                    if (!operation.getName().equals("executeUpdate")) {
                        return invoke(statement, operation, values);
                    }
                    if (cut.at() == CutAt.AFTER_EXECUTE) {
                        Object updated = invoke(statement, operation, values);
                        require(Integer.valueOf(1).equals(updated), "winner did not acquire the actual tentative claim");
                        cut.entered().countDown();
                        await(cut.resume());
                        return updated;
                    }
                    cut.entered().countDown();
                    if (cut.at() == CutAt.LOST_COMMIT) {
                        dropped.set(true);
                    } else {
                        await(cut.resume());
                    }
                    return invoke(statement, operation, values);
                });
            }
            return result;
        });
    }

    private static <T> T wrap(Class<T> type, T instance, Call callback) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> callback.call(instance, method, args)));
    }

    private static Object invoke(Object instance, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void expect(Class<? extends RuntimeException> kind, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (kind.isInstance(failure)) {
                return;
            }
            throw failure;
        }
        throw new AssertionError("Expected " + kind.getSimpleName());
    }

    private static void require(boolean condition, String diagnostic) {
        if (!condition) {
            throw new AssertionError(diagnostic);
        }
    }

    private record Created(String key, RequestRef ref, IntentDigest.Create intent) { }

    private enum CutAt { BEFORE_EXECUTE, AFTER_EXECUTE, LOST_COMMIT }

    private record Cut(String fragment, CutAt at, CountDownLatch entered, CountDownLatch resume) {
        Cut(String fragment, CutAt at) {
            this(fragment, at, new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    @FunctionalInterface interface Call {
        Object call(Object target, Method method, Object[] args) throws Throwable;
    }
}
