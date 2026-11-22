package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.ScopeAuthority.Genesis;
import com.company.requests.application.WriterEpoch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;

/** Explicit setup for an immutable synthetic proof slice, using the independent schema owner. */
public final class JdbcAuthorityProvisioning {
    private final ProofTransactions transactions;

    public JdbcAuthorityProvisioning(DataSource independentMigrationDataSource) {
        transactions = new ProofTransactions(independentMigrationDataSource);
    }

    public void migrate() {
        String source = migrationSource();
        transactions.run(false, connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(1919250805, 2)");
                statement.execute("CREATE SCHEMA IF NOT EXISTS request_authority");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS request_authority.schema_migration (
                            version integer PRIMARY KEY CHECK (version > 0)
                        )
                        """);
                try (var rows = statement.executeQuery("SELECT version FROM request_authority.schema_migration")) {
                    if (rows.next()) {
                        if (rows.getInt(1) != 1 || rows.next()) {
                            throw new AuthorityUnavailableException();
                        }
                        return null;
                    }
                }
                for (String command : source.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
                statement.executeUpdate("INSERT INTO request_authority.schema_migration VALUES (1)");
                return null;
            }
        });
    }

    /** Certifies the only writer supported by this immutable slice. */
    public void certifyWriter(WriterEpoch writer) {
        transactions.run(false, connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.writer_epoch(writer_id, generation) VALUES (?, ?)
                    ON CONFLICT (singleton) DO NOTHING
                    """)) {
                insert.setObject(1, writer.writerId());
                insert.setLong(2, writer.generation());
                int inserted = insert.executeUpdate();
                if (inserted == 1) {
                    try (var counter = connection.prepareStatement("""
                            INSERT INTO request_authority.creation_counter(generation, last_ordinal) VALUES (?, 0)
                            """)) {
                        counter.setLong(1, writer.generation());
                        counter.executeUpdate();
                    }
                } else {
                    try (var check = connection.prepareStatement("""
                            SELECT 1 FROM request_authority.writer_epoch
                            WHERE writer_id = ? AND generation = ?
                            """)) {
                        check.setObject(1, writer.writerId());
                        check.setLong(2, writer.generation());
                        try (var rows = check.executeQuery()) {
                            if (!rows.next()) {
                                throw new AuthorityUnavailableException();
                            }
                        }
                    }
                }
                return null;
            }
        });
    }

    /** Install once, or verify that this is an identical reapplication. */
    public void certifyGenesis(Genesis genesis) {
        transactions.run(false, connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_genesis(organization, policy_version, policy_digest)
                    VALUES (?, ?, ?) ON CONFLICT (organization) DO NOTHING
                    """)) {
                insert.setString(1, genesis.organization().value());
                insert.setLong(2, genesis.policyVersion());
                insert.setString(3, genesis.policyDigest());
                if (insert.executeUpdate() == 0) {
                    try (var check = connection.prepareStatement("""
                            SELECT 1 FROM request_authority.scope_genesis
                            WHERE organization = ? AND policy_version = ? AND policy_digest = ?
                            """)) {
                        check.setString(1, genesis.organization().value());
                        check.setLong(2, genesis.policyVersion());
                        check.setString(3, genesis.policyDigest());
                        try (var rows = check.executeQuery()) {
                            if (!rows.next()) {
                                throw new AuthorityUnavailableException();
                            }
                        }
                    }
                }
                return null;
            }
        });
    }

    private static String migrationSource() {
        String path = "/db/authority/V001__synthetic_authority.sql";
        try (var stream = JdbcAuthorityProvisioning.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing independent authority migration.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read independent authority migration.", failure);
        }
    }
}
