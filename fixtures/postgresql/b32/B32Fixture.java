import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.B08SyntheticScope;
import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import static com.company.requests.bootstrap.B32Database.scalar;

/** Uses the approved B04 cluster lifecycle and the real workflow and proof migrators. */
final class B32Fixture {
    private static final String OBSERVER = "b04_observer";
    private final QualifyPostgresql.Connections workflow;
    private final QualifyPostgresql.Connections proof;
    final WriterEpoch writer = new WriterEpoch(UUID.randomUUID(), 7);
    final String signing = token();
    final String intent = token();
    final String owner = token();
    final String alias = token();
    final String limited = token();
    final String noIssue = token();

    B32Fixture(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof) {
        this.workflow = workflow;
        this.proof = proof;
    }

    void provision() {
        var workflowRoot = dataSource(workflow.migration());
        var proofRoot = dataSource(proof.migration());
        var commandMigration = new JdbcWorkflowMigrations(workflowRoot);
        commandMigration.migrate();
        commandMigration.migrate();
        var work = new JdbcTemplate(workflowRoot);
        new JdbcTransactionRunner(workflowRoot).run(() -> {
            new JdbcWorkflowRecords(workflowRoot).insert(new PolicyGuard(B08SyntheticScope.ORGANIZATION, 1));
            return null;
        });
        work.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer");
        work.execute("GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer");
        work.execute("GRANT SELECT ON requests.schema_migration TO b04_workflow");
        work.execute("""
                GRANT SELECT, INSERT ON requests.request, requests.request_body, requests.transition_fact,
                    requests.operation_receipt, requests.request_assignment TO b04_workflow
                """);
        work.execute("GRANT SELECT, UPDATE (policy_version) ON requests.policy_guard TO b04_workflow");
        work.execute("""
                GRANT UPDATE (result_action, result_ref, result_revision, result_state, publication_namespace,
                    publication_id, original_status) ON requests.operation_receipt TO b04_workflow
                """);
        work.execute("ALTER ROLE b04_observer SET default_transaction_read_only = on");

        var authority = new JdbcAuthorityProvisioning(proofRoot);
        authority.migrate();
        authority.migrate();
        authority.certifyGenesis(B08SyntheticScope.POLICY.genesis());
        authority.certifyWriter(writer);
        authority.certifyWorkflow(writer, JdbcWorkflowIdentity.inspect(workflowRoot));
        var journal = new JdbcTemplate(proofRoot);
        journal.execute("GRANT USAGE ON SCHEMA request_authority TO b04_proof, b04_observer");
        journal.execute("GRANT SELECT ON ALL TABLES IN SCHEMA request_authority TO b04_observer");
        journal.execute("""
                GRANT SELECT ON request_authority.writer_epoch, request_authority.scope_genesis,
                    request_authority.creation_counter, request_authority.creation_reservation,
                    request_authority.workflow_certificate, request_authority.schema_migration TO b04_proof
                """);
        journal.execute("GRANT INSERT ON request_authority.creation_reservation, request_authority.release_prepared TO b04_proof");
        journal.execute("GRANT UPDATE (last_ordinal) ON request_authority.creation_counter TO b04_proof");
        journal.execute("ALTER ROLE b04_observer SET default_transaction_read_only = on");
    }

    List<String> settings() {
        return List.of(workflow.runtimeLogin().password(), proof.runtimeLogin().password(),
                writer.writerId().toString(), Long.toString(writer.generation()), signing, intent, owner, alias, limited, noIssue);
    }

    Connection observer(Store store) throws SQLException {
        Connection connection = (store == Store.WORKFLOW ? workflow : proof).observer().open();
        try {
            connection.setSchema(store.schema);
            connection.setClientInfo("ApplicationName", "b32-observer-" + store.name().toLowerCase(java.util.Locale.ROOT));
            return connection;
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void verifyReadOnly(Store store) throws Exception {
        try (var connection = observer(store); var sql = connection.createStatement()) {
            B32Database expected = B32Database.read(connection, store);
            B32Harness.require(expected.user().equals(OBSERVER)
                    && scalar(connection, "SHOW transaction_read_only").equals("on"), "Dedicated read-only observer required.");
            String write = store == Store.WORKFLOW ? "UPDATE requests.policy_guard SET policy_version = 1 WHERE false"
                    : "DELETE FROM request_authority.release_prepared WHERE false";
            deny(sql, write, "25006");
            sql.execute("SET default_transaction_read_only = off");
            deny(sql, write, "42501");
            System.out.printf("PASS %s observer backend=%d transaction read-only SQLSTATE=25006 role denied SQLSTATE=42501%n",
                    store, expected.pid());
        }
    }

    private static void deny(java.sql.Statement sql, String write, String expected) throws SQLException {
        try {
            sql.execute(write);
            throw new AssertionError("Observer may change durable data.");
        } catch (SQLException failure) {
            B32Harness.require(expected.equals(failure.getSQLState()), "Unexpected observer write SQLSTATE.");
        }
    }

    static String liveApp(Connection observer, int pid) throws SQLException {
        return scalar(observer, "SELECT application_name FROM pg_stat_activity WHERE pid = ?", pid);
    }

    static String blockers(Connection observer, int pid) throws SQLException {
        return scalar(observer, "SELECT pg_blocking_pids(?)::text", pid);
    }

    static void snapshot(String stage, Connection workflow, Connection proof, long local, long reservations,
                         long preparations) throws SQLException {
        String business = scalar(workflow, """
                SELECT concat_ws('/', (SELECT count(*) FROM requests.request),
                       (SELECT count(*) FROM requests.request_body), (SELECT count(*) FROM requests.transition_fact),
                       (SELECT count(*) FROM requests.request_assignment),
                       (SELECT count(*) FROM requests.operation_receipt),
                       (SELECT count(*) FROM requests.operation_receipt WHERE result_ref IS NOT NULL))
                """);
        String journal = scalar(proof, """
                SELECT concat_ws('/', (SELECT count(*) FROM request_authority.creation_reservation),
                       (SELECT count(*) FROM request_authority.release_prepared))
                """);
        B32Harness.require(business.equals(String.join("/", java.util.Collections.nCopies(6, Long.toString(local))))
                && journal.equals(reservations + "/" + preparations),
                "Unexpected durable state at " + stage + " workflow=" + business + " proof=" + journal);
        System.out.printf("PASS stage=%s workflow(request/body/fact/assignment/receipt/finalized)=%s "
                + "proof(reservation/preparation)=%s at=%s%n", stage, business, journal, Instant.now());
    }

    static void verifyResult(Connection workflow, Connection proof, String ref, String name, String body, Instant clock,
                             long generation, int preparations) throws SQLException {
        var actor = B08SyntheticScope.OWNER;
        var person = actor.person().orElseThrow().value();
        B32Harness.require(scalar(workflow, """
                SELECT count(*) FROM requests.request AS r
                JOIN requests.request_body AS b USING (organization, ref)
                JOIN requests.request_assignment AS a USING (organization, ref)
                JOIN requests.transition_fact AS f USING (organization, ref)
                JOIN requests.operation_receipt AS receipt ON receipt.organization = r.organization
                    AND receipt.result_ref = r.ref AND receipt.result_revision = r.revision
                WHERE r.organization = ? AND r.ref = ? AND r.name = ? AND b.body = ?
                    AND r.originating_person_id = ? AND a.assignee_person_id = ? AND a.assignment_version = 1
                    AND f.sequence = 1 AND f.actor_ref = ? AND f.action = 'CREATE' AND f.revision = 1
                    AND f.recorded_epoch_second = ? AND f.recorded_nano = ?
                    AND receipt.issuer = ? AND receipt.principal = ? AND receipt.original_status = 201
                """, actor.organization().value(), ref, name, body, person, person, actor.principal(),
                clock.getEpochSecond(), clock.getNano(), actor.issuer(), actor.principal()).equals("1"),
                "HTTP result must match one exact scoped workflow effect.");
        B32Harness.require(scalar(proof, """
                SELECT CASE WHEN count(DISTINCT p.key_digest) = 1 THEN count(DISTINCT p.correlation_id) ELSE 0 END
                FROM request_authority.release_prepared p
                JOIN request_authority.creation_reservation r ON r.organization = p.organization
                    AND r.request_ref = p.result_ref AND r.created_epoch = p.generation
                WHERE p.organization = ? AND p.result_ref = ? AND p.result_revision = 1 AND p.generation = ?
                """, actor.organization().value(), ref, generation).equals(Integer.toString(preparations)),
                "Independent reservations and release preparations must match the original result and key.");
        System.out.printf("PASS HTTP result, scoped actor, exact UTF-8 intent, trusted timestamp, finalized receipt and "
                + "%d independent preparation(s) identify one effect%n", preparations);
    }

    static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(32));
    }

    private static DataSource dataSource(QualifyPostgresql.SqlConnection login) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return login.open();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new java.sql.SQLFeatureNotSupportedException("The fixture uses its provisioned role.");
            }
        };
    }
}
