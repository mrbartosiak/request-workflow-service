package com.company.requests.adapter.integration.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Installs the explicitly enabled mapping add-ons under their respective schema owners. */
public final class JdbcExternalMappingProvisioning {
    private JdbcExternalMappingProvisioning() { }

    public static void migrateRegistry(DataSource authorityOwner) {
        migrate(authorityOwner, "request_authority", 1, "mapping_registry_v1.sql");
    }

    public static void migrateProjection(DataSource workflowOwner) {
        migrate(workflowOwner, "requests", 2, "external_mapping_v1.sql");
    }

    private static void migrate(DataSource owner, String schema, int lock, String resource) {
        try (var connection = owner.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Mapping migration requires a fresh schema-owner connection.");
            }
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                sql.execute("SET LOCAL statement_timeout = '5s'");
                sql.execute("SET LOCAL synchronous_commit = on");
                sql.execute("SELECT pg_advisory_xact_lock(1919250807, " + lock + ")");
                var migration = schema + ".external_mapping_schema_migration";
                sql.execute("CREATE TABLE IF NOT EXISTS " + migration + " (version integer PRIMARY KEY CHECK (version = 1))");
                boolean applied;
                try (var rows = sql.executeQuery("SELECT 1 FROM " + migration + " WHERE version = 1")) {
                    applied = rows.next();
                }
                if (!applied) {
                    sql.execute(source(resource));
                    sql.executeUpdate("INSERT INTO " + migration + " VALUES (1)");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Mapping migration failed.", failure);
        }
    }

    private static String source(String resource) {
        try (var stream = JdbcExternalMappingProvisioning.class.getResourceAsStream("/db/integration/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing mapping migration.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read mapping migration.", failure);
        }
    }
}
