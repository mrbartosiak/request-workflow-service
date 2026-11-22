package com.company.requests.bootstrap;

import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.jdbc.JdbcWorkflowMigrations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;

/** Evidence from the actual source connection used by the app and an independent observer. */
public record B32Database(String endpoint, String schema, String user, String application, int pid, String migrator) {
    public enum Store {
        WORKFLOW("requests", "workflow_db", 55480, "b04_workflow", JdbcWorkflowMigrations.class),
        PROOF("request_authority", "proof_db", 55481, "b04_proof", JdbcAuthorityProvisioning.class);

        public final String schema;
        public final String database;
        public final int port;
        public final String user;
        final Class<?> migrator;

        Store(String schema, String database, int port, String user, Class<?> migrator) {
            this.schema = schema;
            this.database = database;
            this.port = port;
            this.user = user;
            this.migrator = migrator;
        }
    }

    public static B32Database read(Connection connection, Store store) throws Exception {
        String endpoint = scalar(connection, """
                SELECT concat_ws('/', host(inet_server_addr()), inet_server_port(), current_database(),
                                 current_setting('server_version_num'), pg_postmaster_start_time() AT TIME ZONE 'UTC')
                """);
        String schema = scalar(connection, """
                SELECT concat_ws('/', current_schema(), oid, pg_get_userbyid(nspowner))
                FROM pg_namespace WHERE nspname = current_schema()
                """) + "/" + scalar(connection, "SELECT string_agg(version::text, ',' ORDER BY version) FROM "
                + store.schema + ".schema_migration");
        return new B32Database(endpoint, schema, scalar(connection, "SELECT session_user"),
                scalar(connection, "SHOW application_name"), Integer.parseInt(scalar(connection, "SELECT pg_backend_pid()")),
                artifactHash(store.migrator));
    }

    public void save(Path file) throws Exception {
        Files.write(file, List.of(endpoint, schema, user, application, Integer.toString(pid), migrator));
    }

    public static B32Database load(Path file) throws Exception {
        List<String> fields = Files.readAllLines(file);
        return new B32Database(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                Integer.parseInt(fields.get(4)), fields.get(5));
    }

    public static String scalar(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Missing fixture observation.");
                }
                String value = rows.getString(1);
                if (value == null || rows.next()) {
                    throw new SQLException("Expected one non-null fixture observation.");
                }
                return value;
            }
        }
    }

    public static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String artifactHash(Class<?> type) throws Exception {
        Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        return hash(Files.readAllBytes(jar));
    }
}
