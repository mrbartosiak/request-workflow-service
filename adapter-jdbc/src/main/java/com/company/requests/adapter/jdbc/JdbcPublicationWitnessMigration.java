package com.company.requests.adapter.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** The publication witness add-on, enabled explicitly by the workflow schema owner. */
public final class JdbcPublicationWitnessMigration {
    private final JdbcTransactionRunner owner;
    private final JdbcTemplate jdbc;

    public JdbcPublicationWitnessMigration(DataSource workflowOwner) {
        owner = new JdbcTransactionRunner(workflowOwner);
        jdbc = new JdbcTemplate(workflowOwner);
    }

    public void migrate() {
        owner.run(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 1)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS requests.publication_schema_migration (
                        version integer PRIMARY KEY CHECK (version = 1)
                    )
                    """);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.publication_schema_migration WHERE version = 1)
                    """, Boolean.class))) {
                return null;
            }
            jdbc.execute("LOCK TABLE requests.request IN SHARE MODE");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.request WHERE publication_id IS NOT NULL)
                    """, Boolean.class))) {
                throw new IllegalStateException("Existing publications require an attested journal migration.");
            }
            var script = new ResourceDatabasePopulator(new ClassPathResource("db/workflow/publication_witness_v1.sql"));
            script.setSqlScriptEncoding("UTF-8");
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                script.populate(connection);
                return null;
            });
            jdbc.update("INSERT INTO requests.publication_schema_migration VALUES (1)");
            return null;
        });
    }

    /** Adds the guarded original confirmation projection after the B16 witness schema. */
    public void migrateConfirmations() {
        migrate();
        owner.run(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 1)");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.publication_schema_migration WHERE version = 2)
                    """, Boolean.class))) {
                return null;
            }
            jdbc.execute("""
                    ALTER TABLE requests.publication_schema_migration
                        DROP CONSTRAINT publication_schema_migration_version_check,
                        ADD CHECK (version IN (1, 2))
                    """);
            var script = new ResourceDatabasePopulator(
                    new ClassPathResource("db/workflow/publication_confirmation_v2.sql"));
            script.setSqlScriptEncoding("UTF-8");
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                script.populate(connection);
                return null;
            });
            jdbc.update("INSERT INTO requests.publication_schema_migration VALUES (2)");
            return null;
        });
    }

    /** Retains original Publish proof when an eligible live receipt is deleted. */
    public void migrateReceiptRetirement() {
        migrateConfirmations();
        owner.run(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 1)");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.publication_schema_migration WHERE version = 3)
                    """, Boolean.class))) {
                return null;
            }
            jdbc.execute("""
                    ALTER TABLE requests.publication_schema_migration
                        DROP CONSTRAINT publication_schema_migration_version_check,
                        ADD CHECK (version IN (1, 2, 3))
                    """);
            var resource = new ClassPathResource("db/workflow/publication_receipt_retirement_v3.sql");
            String script;
            try (var input = resource.getInputStream()) {
                script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not read the publication receipt retirement migration.", failure);
            }
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute(script);
                }
                return null;
            });
            jdbc.update("INSERT INTO requests.publication_schema_migration VALUES (3)");
            return null;
        });
    }
}
