package com.company.requests.adapter.jdbc;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Opt-in outbox schema add-on, installed by the workflow schema owner after the core workflow migrations. */
public final class JdbcOutboxMigration {
    private final JdbcTransactionRunner owner;
    private final JdbcTemplate jdbc;

    public JdbcOutboxMigration(DataSource workflowOwner) {
        owner = new JdbcTransactionRunner(workflowOwner);
        jdbc = new JdbcTemplate(workflowOwner);
    }

    public void migrate() {
        owner.run(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 1)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS requests.outbox_schema_migration (
                        version integer PRIMARY KEY CHECK (version = 1)
                    )
                    """);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.outbox_schema_migration WHERE version = 1)
                    """, Boolean.class))) {
                return null;
            }
            var script = new ResourceDatabasePopulator(new ClassPathResource("db/workflow/outbox_v1.sql"));
            script.setSqlScriptEncoding("UTF-8");
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                script.populate(connection);
                return null;
            });
            jdbc.update("INSERT INTO requests.outbox_schema_migration VALUES (1)");
            return null;
        });
    }
}
