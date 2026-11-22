package com.company.requests.adapter.integration.jdbc;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Workflow-owner opt-in migration after the original outbox and dispatch grant migrations. */
public final class JdbcOutboxWorkerMigration {
    private JdbcOutboxWorkerMigration() { }

    public static void migrate(DataSource workflowOwner) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(workflowOwner)) {
            throw new IllegalStateException("Worker migration requires an owned schema-owner transaction.");
        }
        var jdbc = new JdbcTemplate(workflowOwner);
        new TransactionTemplate(new JdbcTransactionManager(workflowOwner)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL synchronous_commit = on");
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 1)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS requests.outbox_worker_schema_migration (
                        version integer PRIMARY KEY CHECK (version = 1)
                    )
                    """);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.outbox_worker_schema_migration WHERE version = 1)
                    """, Boolean.class))) {
                return;
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM requests.schema_migration WHERE version = 14)
                    """, Boolean.class))) {
                throw new IllegalStateException("Install the workflow dispatch grant migration first.");
            }
            var script = new ResourceDatabasePopulator(new ClassPathResource("db/integration/outbox_worker_v1.sql"));
            script.setSqlScriptEncoding("UTF-8");
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                script.populate(connection);
                return null;
            });
            jdbc.update("INSERT INTO requests.outbox_worker_schema_migration VALUES (1)");
        });
    }
}
