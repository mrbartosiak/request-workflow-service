package com.company.requests.adapter.integration.jdbc;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit owner provisioning. The remote schema belongs in an independent local database. */
public final class JdbcSyntheticDeliveryMigration {
    private JdbcSyntheticDeliveryMigration() { }

    public static void workflow(DataSource owner) {
        migrate(owner, 2, "requests.outbox_delivery_recovery", "synthetic_delivery_v1.sql");
    }

    public static void remote(DataSource owner) {
        migrate(owner, 3, "synthetic_delivery.delivery_key", "synthetic_remote_v1.sql");
    }

    private static void migrate(DataSource owner, int lock, String marker, String sql) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(owner)) {
            throw new IllegalStateException("Synthetic delivery provisioning requires its own transaction.");
        }
        var jdbc = new JdbcTemplate(owner);
        new TransactionTemplate(new JdbcTransactionManager(owner)).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL synchronous_commit = on");
            jdbc.query("SELECT pg_advisory_xact_lock(1919250805, ?)", row -> { }, lock);
            if (jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, marker) == null) {
                if ("synthetic_delivery_v1.sql".equals(sql)) {
                    if (schemaExists(jdbc, "synthetic_delivery")) {
                        throw new IllegalStateException("Workflow and synthetic remote ledgers require independent databases.");
                    }
                    var constraint = jdbc.queryForObject("""
                            SELECT quote_ident(conname::text) FROM pg_constraint
                            WHERE conrelid = 'requests.outbox_attempt'::regclass AND contype = 'c'
                                AND pg_get_constraintdef(oid) LIKE '%publication_attempt%'
                            """, String.class);
                    jdbc.execute("ALTER TABLE requests.outbox_attempt DROP CONSTRAINT " + constraint);
                } else if (schemaExists(jdbc, "requests") || schemaExists(jdbc, "request_authority")) {
                    throw new IllegalStateException("Synthetic remote ledger requires its own database.");
                }
                var script = new ResourceDatabasePopulator(new ClassPathResource("db/integration/" + sql));
                script.setSqlScriptEncoding("UTF-8");
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    script.populate(connection);
                    return null;
                });
            }
        });
    }

    private static boolean schemaExists(JdbcTemplate jdbc, String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regnamespace(?) IS NOT NULL", Boolean.class, schema));
    }
}
