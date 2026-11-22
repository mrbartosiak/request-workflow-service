package com.company.requests.adapter.jdbc;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Explicit PostgreSQL fixture migrations. Use the separately provisioned schema owner. */
public final class JdbcWorkflowMigrations {
    private static final List<String> SCRIPTS = List.of(
            "db/workflow/V001__policy_guard.sql", "db/workflow/V002__local_records.sql",
            "db/workflow/V003__create_claim_and_assignment.sql",
            "db/workflow/V004__scope_authority_witness.sql",
            "db/workflow/V005__scope_authority_projection.sql",
            "db/workflow/V006__effective_scope_access.sql", "db/workflow/V007__action_context_grants.sql",
            "db/workflow/V008__request_action_result_grants.sql", "db/workflow/V009__terminal_request_actions.sql",
            "db/workflow/V010__publish_grants.sql", "db/workflow/V011__detail_grants.sql",
            "db/workflow/V012__history_grants.sql", "db/workflow/V013__browse_filter_grants.sql",
            "db/workflow/V014__dispatch_grants.sql");

    private final JdbcTransactionRunner runner;
    private final JdbcTemplate jdbc;

    public JdbcWorkflowMigrations(DataSource migrationDataSource) {
        runner = new JdbcTransactionRunner(migrationDataSource);
        jdbc = new JdbcTemplate(migrationDataSource);
    }

    public void migrate() {
        migrateThrough(3);
    }

    /** Explicit qualification for deployments with effective scope proof. */
    public void migrateScopeWitnesses() {
        migrateThrough(4);
    }

    /** Enables the guarded current-authority projection after the witness migration. */
    public void migrateScopeProjection() {
        migrateThrough(5);
    }

    /** Explicitly installs mutable synthetic grants and visibility facts. */
    public void migrateEffectiveScopeAccess() {
        migrateThrough(6);
    }

    /** Enables independently managed existing-request context, execution and material field grants. */
    public void migrateActionContext() {
        migrateThrough(7);
    }

    /** Adds the distinct result grants for Edit, Verify and Accept. */
    public void migrateRequestActions() {
        migrateThrough(8);
    }

    /** Adds protected terminal reasons and separate current deleted metadata and result grants. */
    public void migrateTerminalRequestActions() {
        migrateThrough(9);
    }

    /** Enables independently managed Publish result and publication ID access. */
    public void migratePublishGrants() {
        migrateThrough(10);
    }

    /** Enables independently managed type, state and current revision reads. */
    public void migrateDetailGrants() {
        migrateThrough(11);
    }

    /** Enables separately managed history reads by request type. */
    public void migrateHistoryGrants() {
        migrateThrough(12);
    }

    /** Enables independent per-type browse filters and the organization-scoped seek. */
    public void migrateBrowse() {
        migrateThrough(13);
    }

    /** Enables the independently managed outbound reference grant. */
    public void migrateDispatchGrants() {
        migrateThrough(14);
    }

    void migrateThrough(int targetVersion) {
        runner.run(() -> {
            // Serialize creation of the schema and its migration history as well as upgrades.
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 1)");
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS requests");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS requests.schema_migration (
                        version integer PRIMARY KEY CHECK (version > 0)
                    )
                    """);
            List<Integer> applied = jdbc.queryForList(
                    "SELECT version FROM requests.schema_migration ORDER BY version", Integer.class);
            if (applied.size() > targetVersion) {
                throw new IllegalStateException("Workflow schema is newer than the requested migration level.");
            }
            for (int i = 0; i < applied.size(); i++) {
                if (applied.get(i) != i + 1) {
                    throw new IllegalStateException("Workflow migration history has a gap.");
                }
            }
            for (int i = applied.size(); i < targetVersion; i++) {
                var script = new ResourceDatabasePopulator(new ClassPathResource(SCRIPTS.get(i)));
                script.setSqlScriptEncoding("UTF-8");
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    script.populate(connection);
                    return null;
                });
                jdbc.update("INSERT INTO requests.schema_migration(version) VALUES (?)", i + 1);
            }
            return null;
        });
    }
}
