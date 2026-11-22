package com.company.requests.adapter.authority;

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
                statement.execute(source());
                statement.executeUpdate("INSERT INTO request_authority.publication_schema_migration VALUES (1)");
                return null;
            }
        });
    }

    private static String source() {
        try (var stream = JdbcPublicationProvisioning.class
                .getResourceAsStream("/db/authority/publication_journal_v1.sql")) {
            if (stream == null) {
                throw new IllegalStateException("Missing independent publication migration.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read independent publication migration.", failure);
        }
    }
}
