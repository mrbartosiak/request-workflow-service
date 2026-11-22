package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeCheckpoint;
import com.company.requests.application.WriterLineage.Identity;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads exact proof only on the independently certified live workflow process. */
final class OriginalWorkflowProof {
    private final ProofTransactions transactions;

    OriginalWorkflowProof(DataSource observer) {
        transactions = new ProofTransactions(observer);
    }

    String status(Connection proof, Attempt attempt) {
        return run(proof, attempt.admission(), workflow -> transactionStatus(workflow, attempt));
    }

    void requireWitness(Connection proof, Entry entry) {
        var attempt = entry.attempt();
        run(proof, attempt.admission(), workflow -> {
            if (!"committed".equals(transactionStatus(workflow, attempt))) {
                throw new AuthorityUnavailableException();
            }
            try (var query = workflow.prepareStatement("""
                    SELECT writer_id, generation, before_version, after_version, delta_digest,
                           workflow_transaction::text
                    FROM requests.scope_authority_witness
                    WHERE organization = ? AND sequence = ? AND attempt_id = ?
                      AND xmin::text::numeric = mod(workflow_transaction::text::numeric, 4294967296)
                    """)) {
                var writer = attempt.admission().writer();
                query.setString(1, JdbcScopeAuthorityJournal.organization(attempt.admission()));
                query.setLong(2, entry.sequence().orElseThrow());
                query.setObject(3, attempt.id());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !writer.writerId().equals(rows.getObject(1, UUID.class))
                            || writer.generation() != rows.getLong(2) || attempt.beforeVersion() != rows.getLong(3)
                            || attempt.afterVersion() != rows.getLong(4) || !attempt.deltaDigest().equals(rows.getString(5))
                            || !attempt.workflowTransaction().equals(rows.getString(6))) {
                        throw new AuthorityUnavailableException();
                    }
                }
            }
            return null;
        });
    }

    void requireProjection(Connection proof, ScopeCheckpoint checkpoint) {
        var admission = checkpoint.admission();
        run(proof, admission, workflow -> {
            try (var query = workflow.prepareStatement("""
                    SELECT 1
                    FROM requests.policy_guard g
                    LEFT JOIN requests.scope_authority_projection p USING (organization)
                    WHERE g.organization = ? AND g.policy_version = ?
                      AND ((? = 0 AND p.organization IS NULL) OR
                           (p.writer_id = ? AND p.generation = ? AND p.sequence = ?
                            AND p.policy_version = g.policy_version AND p.prefix_digest = ?))
                    """)) {
                query.setString(1, JdbcScopeAuthorityJournal.organization(admission));
                query.setLong(2, checkpoint.policyVersion());
                query.setLong(3, checkpoint.sequence());
                query.setObject(4, admission.writer().writerId());
                query.setLong(5, admission.writer().generation());
                query.setLong(6, checkpoint.sequence());
                query.setString(7, checkpoint.prefixDigest());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AuthorityUnavailableException();
                    }
                }
            }
            return null;
        });
    }

    private <T> T run(Connection proof, Admission admission, ProofTransactions.Work<T> work) {
        return transactions.run(true, workflow -> {
            try (var statement = workflow.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT host(inet_server_addr()), inet_server_port(), current_database(), pg_postmaster_start_time()
                         WHERE NOT pg_is_in_recovery()
                         """)) {
                if (!rows.next() || rows.getString(1) == null) {
                    throw new AuthorityUnavailableException();
                }
                var identity = new Identity(rows.getString(1), rows.getInt(2), rows.getString(3),
                        rows.getObject(4, OffsetDateTime.class).toInstant());
                JdbcCreateProof.requireWorkflow(proof, admission.writer(), identity);
            }
            return work.apply(workflow);
        });
    }

    private static String transactionStatus(Connection workflow, Attempt attempt) throws SQLException {
        try (var query = workflow.prepareStatement("SELECT pg_xact_status(CAST(? AS xid8))")) {
            query.setString(1, attempt.workflowTransaction());
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
