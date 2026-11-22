import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.adapter.authority.JdbcReleaseEvidence.Input;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeUpdates.Permission;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityResolution.Operation;
import com.company.requests.application.ScopeAuthorityResolution.OperatorCredential;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B33Runtime;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Keeps B10's certified scope and B14's role boundaries while providing private child logins. */
final class B33Profile {
    static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    static final AuthenticatedActor APPROVER = B10Postgresql.ASSIGNEE;
    private static final AuthenticatedActor ASSIGNER = new AuthenticatedActor(OWNER.issuer(), OWNER.organization(),
            "assignment-operator", AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("person-assigner")));
    private static final Map<AuthenticatedActor, Set<Permission>> OPERATORS = Map.of(
            B10Postgresql.OPERATOR, Set.of(Permission.values()), ASSIGNER, Set.of(Permission.MOVE_ASSIGNMENT));

    enum Role {
        COMMAND(Store.WORKFLOW, "b04_workflow"), JOURNAL(Store.PROOF, "b04_proof"),
        ORIGINAL(Store.WORKFLOW, "b04_observer"), PROJECTION(Store.WORKFLOW, "b11_projection"),
        RESOLVER(Store.PROOF, "b11_resolver"), CREATE(Store.PROOF, "b10_create_proof"),
        PREPARE(Store.PROOF, "b12_prepare"), HANDOFF(Store.PROOF, "b12_handoff");

        final Store store;
        final String login;

        Role(Store store, String login) {
            this.store = store;
            this.login = login;
        }
    }

    record Child(Map<Role, String> passwords, WriterEpoch writer, String signing, String intent, String observation,
                 String owner, String alias, String approver, String approverAlias) {
        List<String> fields() {
            var fields = new ArrayList<String>();
            for (Role role : Role.values()) {
                fields.add(passwords.get(role));
            }
            fields.addAll(List.of(writer.writerId().toString(), Long.toString(writer.generation()), signing, intent,
                    observation, owner, alias, approver, approverAlias));
            return List.copyOf(fields);
        }

        static Child read(java.io.BufferedReader reader) throws java.io.IOException {
            var passwords = new EnumMap<Role, String>(Role.class);
            for (Role role : Role.values()) {
                passwords.put(role, field(reader));
            }
            return new Child(Map.copyOf(passwords),
                    new WriterEpoch(UUID.fromString(field(reader)), Long.parseLong(field(reader))),
                    field(reader), field(reader), field(reader), field(reader), field(reader), field(reader), field(reader));
        }

        private static String field(java.io.BufferedReader reader) throws java.io.IOException {
            String field = reader.readLine();
            if (field == null || !field.matches("[A-Za-z0-9_-]{1,128}")) {
                throw new java.io.IOException("Invalid private B33 configuration field.");
            }
            return field;
        }

        DataSource source(Role role, String application) {
            return B33Runtime.role(role.store, role.login, passwords.get(role), application);
        }

        SyntheticEffectiveAccessConfiguration.Settings settings(String app, DataSource command, DataSource proof,
                                                               DataSource journal, DataSource original,
                                                               Supplier<Instant> clock) {
            var resolver = new SyntheticActorResolver(OWNER.issuer(), "b10-audience", List.of(
                    new SyntheticActorResolver.Binding("b33-owner", owner, OWNER),
                    new SyntheticActorResolver.Binding("b33-owner-alias", alias, OWNER),
                    new SyntheticActorResolver.Binding("b33-approver", approver, APPROVER),
                    new SyntheticActorResolver.Binding("b33-approver-alias", approverAlias, APPROVER)));
            var secrets = secrets();
            var ports = new SyntheticCreateConfiguration.GuardedReplay(OriginalReceiptProof.unavailable(),
                    JdbcReleaseEvidence.preparation(source(Role.PREPARE, app)),
                    JdbcReleaseEvidence.handoffs(source(Role.HANDOFF, app)));
            var create = new SyntheticCreateConfiguration.Settings(command, proof, B10Postgresql.policy(), writer,
                    resolver, clock::get, secrets, Optional.of(ports));
            return new SyntheticEffectiveAccessConfiguration.Settings(create, journal, original,
                    source(Role.PROJECTION, app), source(Role.RESOLVER, app), OPERATORS,
                    Optional.of(new ObservationCodec.Keys(7, Map.of(7, decode(observation)))));
        }

        OperationSecrets secrets() {
            return new OperationSecrets(1, Map.of(1, decode(signing)), 1, Map.of(1, decode(intent)));
        }

        private static byte[] decode(String secret) {
            return Base64.getUrlDecoder().decode(secret);
        }

        @Override
        public String toString() {
            return "B33 child settings";
        }
    }

    private final QualifyPostgresql.Connections proof;
    private final OperatorCredential confirmer = new OperatorCredential(B32Fixture.token());
    private final SyntheticEffectiveAccessConfiguration.Components scope;
    private final Admission admission;
    final Child child;

    B33Profile(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
               QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
               QualifyPostgresql.SqlConnection createProof, B10FixtureSources baselineSources) throws Exception {
        this.proof = proof;
        var baseline = new B10Postgresql(workflow, proof, projector, resolver, createProof, baselineSources,
                java.util.function.UnaryOperator.identity());
        var workflowRoot = source(workflow.migration());
        var proofRoot = source(proof.migration());
        var migrations = new JdbcWorkflowMigrations(workflowRoot);
        migrations.migrateActionContext();
        migrations.migrateRequestActions();
        migrations.migrateTerminalRequestActions();
        var work = new JdbcTemplate(workflowRoot);
        var authority = new JdbcTemplate(proofRoot);
        work.execute("""
                GRANT UPDATE (state, revision, last_sequence) ON requests.request TO b04_workflow;
                GRANT UPDATE (body) ON requests.request_body TO b04_workflow;
                GRANT INSERT ON requests.transition_reason TO b04_workflow;
                GRANT SELECT ON requests.schema_migration TO b04_workflow;
                GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer;
                ALTER ROLE b04_observer SET default_transaction_read_only = on;
                """);
        var provisioning = new JdbcAuthorityProvisioning(proofRoot);
        provisioning.migrateReleaseEvidence();
        provisioning.authorizeReleaseEvidence(Role.PREPARE.login, OWNER.organization(), Input.PREPARATION);
        provisioning.authorizeReleaseEvidence(Role.HANDOFF.login, OWNER.organization(), Input.HANDOFF);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b04_observer, b12_prepare, b12_handoff;
                GRANT SELECT ON ALL TABLES IN SCHEMA request_authority TO b04_observer;
                GRANT SELECT ON request_authority.schema_migration TO b10_create_proof;
                GRANT EXECUTE ON FUNCTION request_authority.prepare_release(uuid, text, bytea, text, bigint, bigint,
                    bigint, uuid, bigint, text) TO b12_prepare;
                GRANT EXECUTE ON FUNCTION request_authority.record_release_evidence(uuid, text, bytea, text)
                    TO b12_handoff;
                ALTER ROLE b04_observer SET default_transaction_read_only = on;
                """);
        enable(baseline, OWNER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_EDIT, Grant.EXECUTE_EDIT,
                Grant.RESULT_READ_EDIT, Grant.CONTEXT_VERIFY, Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY);
        enable(baseline, APPROVER, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT,
                Grant.RESULT_READ_ACCEPT);
        provisioning.authorizeScopeOperator(UUID.randomUUID(), confirmer, OWNER.organization(), Operation.CONFIRM);

        var passwords = new EnumMap<Role, String>(Role.class);
        passwords.put(Role.COMMAND, workflow.runtimeLogin().password());
        passwords.put(Role.JOURNAL, proof.runtimeLogin().password());
        for (Role role : List.of(Role.ORIGINAL, Role.PROJECTION, Role.RESOLVER, Role.CREATE, Role.PREPARE, Role.HANDOFF)) {
            // The imported provisioner exposes only the two basic login fields. Its extra qualified
            // roles receive fresh fixture passwords after setup. The initial pools then close.
            String password = B32Fixture.token();
            (role.store == Store.WORKFLOW ? work : authority).execute(
                    "ALTER ROLE " + role.login + " PASSWORD '" + password + "'");
            passwords.put(role, password);
        }
        child = new Child(Map.copyOf(passwords), B10Postgresql.WRITER, B32Fixture.token(), B32Fixture.token(),
                B32Fixture.token(), B32Fixture.token(), B32Fixture.token(), B32Fixture.token(), B32Fixture.token());
        String app = "b33-scope";
        var operatorSettings = child.settings(app, child.source(Role.COMMAND, app), child.source(Role.CREATE, app),
                child.source(Role.JOURNAL, app), child.source(Role.ORIGINAL, app), () -> B33Harness.CLOCK);
        var genesis = operatorSettings.effectivePolicy().genesis();
        B33Evidence.require(genesis.equals(baseline.settings().effectivePolicy().genesis()),
                "Worker and provisioner genesis differ.");
        admission = new Admission(genesis, child.writer());
        scope = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(operatorSettings);
    }

    void assign(String ref) {
        var pending = scope.updates().stageAssignment(B10Postgresql.OPERATOR, new RequestRef(ref), APPROVER);
        confirm(pending.attemptId());
    }

    void setEditResult(boolean visible) {
        var grant = grant(OWNER, Grant.RESULT_READ_EDIT);
        var updates = scope.updates();
        var pending = visible ? updates.stageEnablement(B10Postgresql.OPERATOR, grant)
                : updates.stageRevocation(B10Postgresql.OPERATOR, grant);
        confirm(pending.attemptId());
    }

    private void confirm(UUID attempt) {
        B33Evidence.require(scope.resolver().resolve(confirmer, admission, attempt, Operation.CONFIRM) == State.COMMITTED,
                "Audited B33 scope operation was not confirmed.");
    }

    OperationKeyIssuer.VerifiedKey key(AuthenticatedActor actor, String signed, Instant now) {
        var issuer = new OperationKeyIssuer(scope.access(), () -> now, child.secrets(),
                OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        return issuer.verify(signed, actor, admission);
    }

    IntentDigest digest() {
        return new IntentDigest(child.secrets());
    }

    Connection observer(Store store) throws SQLException {
        var connection = store == Store.WORKFLOW ? child.source(Role.ORIGINAL, "b33-observer").getConnection()
                : proof.observer().open();
        try {
            connection.setSchema(store.schema);
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private static void enable(B10Postgresql baseline, AuthenticatedActor actor, Grant... permissions) {
        for (var permission : permissions) {
            baseline.confirm(baseline.components().updates().stageEnablement(B10Postgresql.OPERATOR,
                    grant(actor, permission)));
        }
    }

    private static GrantKey grant(AuthenticatedActor actor, Grant permission) {
        return new GrantKey(actor, permission, Optional.of(B10Postgresql.TYPE));
    }

    private static DataSource source(QualifyPostgresql.SqlConnection opener) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return opener.open();
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                throw new java.sql.SQLFeatureNotSupportedException();
            }
        };
    }
}
