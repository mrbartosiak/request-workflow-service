package com.company.requests.adapter.integration.jdbc;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies the workflow-owner opt-in inbox migration. */
public final class JdbcInboundMigration {
    private JdbcInboundMigration() { }

    public static void migrate(DataSource workflowOwner) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(workflowOwner)) {
            throw new IllegalStateException("Inbox migration requires an owned schema-owner transaction.");
        }
        var jdbc = new JdbcTemplate(workflowOwner);
        new TransactionTemplate(new JdbcTransactionManager(workflowOwner)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '5s'");
            jdbc.execute("SET LOCAL synchronous_commit = on");
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250808, 1)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS requests.inbound_schema_migration (
                        version integer PRIMARY KEY CHECK (version = 1)
                    )
                    """);
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM requests.inbound_schema_migration WHERE version = 1)", Boolean.class))) {
                return;
            }
            var script = new ResourceDatabasePopulator(new ClassPathResource("db/integration/inbound_inbox_v1.sql"));
            script.setSqlScriptEncoding("UTF-8");
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                script.populate(connection);
                return null;
            });
            jdbc.update("INSERT INTO requests.inbound_schema_migration VALUES (1)");
        });
    }
}
