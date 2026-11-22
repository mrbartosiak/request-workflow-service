package com.company.requests.adapter.authority;

import com.company.requests.domain.OrganizationId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;

/** Installs the publication add-on in the independent authority database under its owner. */
public final class JdbcPublicationProvisioning {
    private final ProofTransactions owner;

    public JdbcPublicationProvisioning(DataSource authorityOwner) {
        owner = new ProofTransactions(authorityOwner);
    }

    /** Bootstrap an initially empty namespace after the writer and workflow certificate are provisioned. */
    public void migrate() {
        owner.run(false, connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(1919250805, 2)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS request_authority.publication_schema_migration (
                            version integer PRIMARY KEY CHECK (version = 1)
                        )
                        """);
                try (var rows = statement.executeQuery("""
                        SELECT 1 FROM request_authority.publication_schema_migration WHERE version = 1
                        """)) {
                    if (rows.next()) {
                        return null;
                    }
                }
                statement.execute(source("publication_journal_v1.sql"));
                statement.executeUpdate("INSERT INTO request_authority.publication_schema_migration VALUES (1)");
                return null;
            }
        });
    }

    /** Adds independently audited, organization-scoped terminal resolution. */
    public void migrateResolver() {
        migrate();
        owner.run(false, connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(1919250805, 2)");
                try (var rows = statement.executeQuery("""
                        SELECT 1 FROM request_authority.publication_schema_migration WHERE version = 2
                        """)) {
                    if (rows.next()) {
                        return null;
                    }
                }
                statement.execute("""
                        ALTER TABLE request_authority.publication_schema_migration
                            DROP CONSTRAINT publication_schema_migration_version_check,
                            ADD CHECK (version IN (1, 2))
                        """);
                statement.execute(source("publication_resolution_v2.sql"));
                statement.executeUpdate("INSERT INTO request_authority.publication_schema_migration VALUES (2)");
                return null;
            }
        });
    }

    public void authorizeResolver(String databaseRole, OrganizationId organization, boolean confirm, boolean abort) {
        owner.run(false, connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.publication_resolver_grant
                        (resolver_role, organization, may_confirm, may_abort)
                    VALUES (CAST(? AS name), ?, ?, ?)
                    """)) {
                insert.setString(1, databaseRole);
                insert.setString(2, organization.value());
                insert.setBoolean(3, confirm);
                insert.setBoolean(4, abort);
                insert.executeUpdate();
                return null;
            }
        });
    }

    private static String source(String resource) {
        try (var stream = JdbcPublicationProvisioning.class
                .getResourceAsStream("/db/authority/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing independent publication migration.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read independent publication migration.", failure);
        }
    }
}
