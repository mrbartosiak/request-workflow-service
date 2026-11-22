package com.company.requests.adapter.integration.jdbc;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit owner migration after the synthetic delivery schema. */
public final class JdbcDispatchSchedulingMigration {
    private JdbcDispatchSchedulingMigration() { }

    public static void migrate(DataSource owner) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(owner)) {
            throw new IllegalStateException("Dispatch scheduling provisioning requires its own transaction.");
        }
        var jdbc = new JdbcTemplate(owner);
        new TransactionTemplate(new JdbcTransactionManager(owner)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL synchronous_commit = on");
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 5)");
            if (jdbc.queryForObject("SELECT to_regclass('requests.outbox_delivery_recovery')::text", String.class)
                    == null) {
                throw new IllegalStateException("Dispatch scheduling follows synthetic delivery provisioning.");
            }
            if (jdbc.queryForObject("SELECT to_regclass('requests.outbox_dispatch_lane')::text", String.class)
                    == null) {
                var script = new ResourceDatabasePopulator(
                        new ClassPathResource("db/integration/outbox_scheduling_v1.sql"));
                script.setSqlScriptEncoding("UTF-8");
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    script.populate(connection);
                    return null;
                });
            } else if (jdbc.queryForObject("SELECT to_regclass('requests.outbox_scheduled_occupied_slot')::text",
                    String.class) == null) {
                throw new IllegalStateException("Dispatch scheduling requires the reviewed durable occupancy schema.");
            }
        });
    }
}
