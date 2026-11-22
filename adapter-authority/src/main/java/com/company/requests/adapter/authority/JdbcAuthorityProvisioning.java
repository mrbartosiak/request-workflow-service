package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.ScopeAuthority.Genesis;
import com.company.requests.application.WriterEpoch;
import com.company.requests.application.WriterLineage.Identity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Explicit setup for an immutable synthetic proof slice, using the independent schema owner. */
public final class JdbcAuthorityProvisioning {
    private static final List<String> MIGRATIONS = List.of(
            "V001__synthetic_authority.sql", "V002__create_proof.sql", "V003__scope_authority_journal.sql");
    private final ProofTransactions transactions;

    public JdbcAuthorityProvisioning(DataSource independentMigrationDataSource) {
        transactions = new ProofTransactions(independentMigrationDataSource);
    }

    public void migrate() {
        migrateThrough(2);
    }

    /** Explicit qualification for deployments with effective scope proof. */
    public void migrateScopeJournal() {
        migrateThrough(MIGRATIONS.size());
    }

    private void migrateThrough(int target) {
        List<String> sources = MIGRATIONS.subList(0, target).stream()
                .map(JdbcAuthorityProvisioning::migrationSource).toList();
        transactions.run(false, connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(1919250805, 2)");
                statement.execute("CREATE SCHEMA IF NOT EXISTS request_authority");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS request_authority.schema_migration (
                            version integer PRIMARY KEY CHECK (version > 0)
                        )
                        """);
                var applied = new ArrayList<Integer>();
                try (var rows = statement.executeQuery(
                        "SELECT version FROM request_authority.schema_migration ORDER BY version")) {
                    while (rows.next()) {
                        applied.add(rows.getInt(1));
                    }
                }
                if (applied.size() > MIGRATIONS.size()) {
                    throw new AuthorityUnavailableException();
                }
                for (int index = 0; index < applied.size(); index++) {
                    if (applied.get(index) != index + 1) {
                        throw new AuthorityUnavailableException();
                    }
                }
                try (var record = connection.prepareStatement(
                        "INSERT INTO request_authority.schema_migration VALUES (?)")) {
                    for (int index = applied.size(); index < sources.size(); index++) {
                        for (String command : sources.get(index).split(";")) {
                            if (!command.isBlank()) {
                                statement.execute(command);
                            }
                        }
                        record.setInt(1, index + 1);
                        record.executeUpdate();
                    }
                }
                return null;
            }
        });
    }

    /** Install the initial high-water for a scope certified after V003. */
    public void initializeScopeJournal(Genesis genesis) {
        transactions.run(false, connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_journal_head (organization, last_sequence)
                    SELECT organization, 0 FROM request_authority.scope_genesis
                    WHERE organization = ? AND policy_version = ? AND policy_digest = ?
                    """)) {
                insert.setString(1, genesis.organization().value());
                insert.setLong(2, genesis.policyVersion());
                insert.setString(3, genesis.policyDigest());
                if (insert.executeUpdate() != 1) {
                    throw new AuthorityUnavailableException();
                }
            }
            return null;
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

    /** Bind the certified generation to its original live workflow process. Reapplication must be identical. */
    public void certifyWorkflow(WriterEpoch writer, Identity identity) {
        transactions.run(false, connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.workflow_certificate (
                        generation, server_address, server_port, database_name, server_started_at
                    ) SELECT generation, ?, ?, ?, ? FROM request_authority.writer_epoch
                    WHERE writer_id = ? AND generation = ? ON CONFLICT (generation) DO NOTHING
                    """)) {
                JdbcCreateProof.bindIdentity(insert, 1, identity);
                insert.setObject(5, writer.writerId());
                insert.setLong(6, writer.generation());
                insert.executeUpdate();
            }
            JdbcCreateProof.requireWorkflow(connection, writer, identity);
            return null;
        });
    }

    private static String migrationSource(String name) {
        String path = "/db/authority/" + name;
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
