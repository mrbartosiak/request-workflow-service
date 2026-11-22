import com.company.requests.adapter.integration.jdbc.JdbcSyntheticDeliveryMigration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A distinct database and SCRAM runtime role inside the owned proof cluster. */
final class B24RemoteDatabase {
    private static final String URL = "jdbc:postgresql://127.0.0.1:55481/b24_remote";

    final DataSource runtime;
    final JdbcTemplate observer;

    B24RemoteDatabase(QualifyPostgresql.SqlConnection proofAdmin) throws Exception {
        String ownerPassword = UUID.randomUUID().toString();
        String sendPassword = UUID.randomUUID().toString();
        try (var proof = proofAdmin.open(); var setup = proof.createStatement()) {
            require(proof.getAutoCommit(), "remote database provisioning needs autocommit");
            setup.execute("CREATE ROLE b24_remote_owner LOGIN NOINHERIT PASSWORD '" + ownerPassword + "'");
            setup.execute("CREATE ROLE b24_remote_send LOGIN NOINHERIT PASSWORD '" + sendPassword + "'");
            setup.execute("CREATE DATABASE b24_remote OWNER b24_remote_owner");
            setup.execute("REVOKE ALL ON DATABASE b24_remote FROM PUBLIC");
            setup.execute("GRANT CONNECT ON DATABASE b24_remote TO b24_remote_send");
            Path hba;
            try (var rows = setup.executeQuery("SHOW hba_file")) {
                require(rows.next(), "proof cluster did not supply its HBA path");
                hba = Path.of(rows.getString(1)).toRealPath();
            }
            require(hba.getFileName().toString().equals("pg_hba.conf")
                    && hba.getParent().getFileName().toString().equals("proof")
                    && hba.getParent().getParent().getFileName().toString().startsWith("b23-b24-")
                    && hba.getParent().getParent().getParent().getFileName().toString().equals("runs"),
                    "refusing to modify HBA outside the owned B24 run");
            Files.writeString(hba, """
                    host b24_remote b24_remote_owner 127.0.0.1/32 scram-sha-256
                    host b24_remote b24_remote_send 127.0.0.1/32 scram-sha-256
                    """, StandardOpenOption.APPEND);
            setup.execute("SELECT pg_reload_conf()");
        }
        var owner = B08MvcSession.dataSource(() -> connectOwner(ownerPassword));
        runtime = B08MvcSession.dataSource(() -> DriverManager.getConnection(URL, "b24_remote_send", sendPassword));
        JdbcSyntheticDeliveryMigration.remote(owner);
        JdbcSyntheticDeliveryMigration.remote(owner);
        observer = new JdbcTemplate(owner);
        observer.execute("""
                REVOKE ALL ON SCHEMA public FROM PUBLIC;
                GRANT USAGE ON SCHEMA synthetic_delivery TO b24_remote_send;
                GRANT SELECT ON synthetic_delivery.endpoint TO b24_remote_send;
                GRANT SELECT, INSERT ON synthetic_delivery.delivery_key, synthetic_delivery.delivery_attempt
                    TO b24_remote_send;
                GRANT UPDATE (fenced_through) ON synthetic_delivery.delivery_key TO b24_remote_send;
                GRANT UPDATE (state, effect_ref) ON synthetic_delivery.delivery_attempt TO b24_remote_send;
                GRANT SELECT, INSERT ON synthetic_delivery.delivery_observation TO b24_remote_send;
                """);
        require(Boolean.TRUE.equals(observer.queryForObject("""
                SELECT current_database() = 'b24_remote'
                    AND to_regnamespace('request_authority') IS NULL AND to_regnamespace('requests') IS NULL
                    AND NOT has_table_privilege('b24_remote_send', 'synthetic_delivery.delivery_attempt', 'DELETE')
                    AND NOT has_column_privilege('b24_remote_send', 'synthetic_delivery.delivery_key', 'contract', 'UPDATE')
                    AND NOT has_table_privilege('b24_remote_send', 'synthetic_delivery.delivery_observation', 'UPDATE')
                """, Boolean.class)), "remote serving database or role boundary is incorrect");
    }

    private static java.sql.Connection connectOwner(String password) throws SQLException {
        // Windows reads pg_hba.conf for new connections. Allow the same provisioner to run after reload on other hosts.
        for (int attempt = 0; ; attempt++) {
            try {
                return DriverManager.getConnection(URL, "b24_remote_owner", password);
            } catch (SQLException waiting) {
                if (attempt >= 20 || !"28000".equals(waiting.getSQLState())) {
                    throw waiting;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted awaiting loopback access.", stop);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
