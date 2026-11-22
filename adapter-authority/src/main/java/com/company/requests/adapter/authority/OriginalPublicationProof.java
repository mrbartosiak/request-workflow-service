package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PublicationJournal;
import com.company.requests.application.PublicationJournal.Attempt;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.WriterLineage.Identity;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;

/** Observes the exact live certified workflow transaction, never using absence as proof. */
final class OriginalPublicationProof {
    private final ProofTransactions observer;

    OriginalPublicationProof(DataSource observer) {
        this.observer = new ProofTransactions(observer);
    }

    void requireHeld(Connection proof, Attempt attempt) {
        observe(proof, attempt, workflow -> {
            try (var query = workflow.prepareStatement("""
                    WITH held AS MATERIALIZED (
                        SELECT locktype, database, classid, objid, objsubid, transactionid
                        FROM pg_catalog.pg_locks
                        WHERE pid = ? AND granted AND mode = 'ExclusiveLock'
                    )
                    SELECT 1 FROM held gate CROSS JOIN held txn
                    JOIN requests.publication_target_evidence r
                      ON r.organization = ? AND r.request_type = ? AND r.ref = ? AND r.state = 'ACCEPTED'
                    WHERE gate.locktype = 'advisory' AND gate.classid = 1919250805 AND gate.objid = 16
                      AND gate.objsubid = 2 AND gate.database = (
                          SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
                      AND txn.locktype = 'transactionid'
                      AND txn.transactionid::text::numeric = mod(CAST(? AS numeric), 4294967296)
                      AND pg_xact_status(CAST(? AS xid8)) = 'in progress'
                      AND r.revision = ?
                      AND 8 = (
                          SELECT count(DISTINCT word.classid)
                          FROM held word JOIN generate_series(0, 7) AS binding(lane)
                            ON word.classid = 1919250810 + lane
                            AND word.objid::bigint = ('x' || substr(?, 1 + lane * 8, 8))::bit(32)::bigint
                          WHERE word.locktype = 'advisory' AND word.objsubid = 2 AND word.database = gate.database
                      )
                    """)) {
                query.setInt(1, attempt.backendPid());
                query.setString(2, attempt.organization().value());
                query.setString(3, attempt.type().name());
                query.setString(4, attempt.ref().value());
                query.setString(5, attempt.workflowTransaction());
                query.setString(6, attempt.workflowTransaction());
                query.setLong(7, attempt.resultRevision() - 1);
                query.setString(8, attempt.gateBinding());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AuthorityUnavailableException();
                    }
                }
            }
            return null;
        });
    }

    void requireAborted(Connection proof, Attempt attempt) {
        observe(proof, attempt, workflow -> {
            try (var query = workflow.prepareStatement("SELECT pg_xact_status(CAST(? AS xid8))")) {
                query.setString(1, attempt.workflowTransaction());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !"aborted".equals(rows.getString(1))) {
                        throw new AuthorityUnavailableException();
                    }
                }
            }
            return null;
        });
    }

    void requireCommitted(Connection proof, Entry pending) {
        Attempt attempt = pending.attempt();
        observe(proof, attempt, workflow -> {
            try (var query = workflow.prepareStatement("""
                    SELECT receipt_issuer, receipt_principal, receipt_token_id
                    FROM requests.publication_commit_evidence
                    WHERE attempt_id = ? AND writer_id = ? AND generation = ? AND organization = ?
                      AND request_type = ? AND ref = ? AND namespace = ? AND publication_id = ?
                      AND result_revision = ? AND workflow_transaction = CAST(? AS xid8)
                      AND pg_xact_status(workflow_transaction) = 'committed'
                    """)) {
                query.setObject(1, attempt.id());
                query.setObject(2, attempt.writer().writerId());
                query.setLong(3, attempt.writer().generation());
                query.setString(4, attempt.organization().value());
                query.setString(5, attempt.type().name());
                query.setString(6, attempt.ref().value());
                query.setString(7, attempt.namespace());
                query.setLong(8, pending.allocation().orElseThrow().value());
                query.setLong(9, attempt.resultRevision());
                query.setString(10, attempt.workflowTransaction());
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !attempt.receiptIdentity().equals(PublicationJournal.receiptIdentity(
                            new ReceiptKey(rows.getString(1), attempt.organization(), rows.getString(2), rows.getString(3))))) {
                        throw new AuthorityUnavailableException();
                    }
                }
            }
            return null;
        });
    }

    private <T> T observe(Connection proof, Attempt attempt, ProofTransactions.Work<T> work) {
        return observer.run(true, workflow -> {
            try (var statement = workflow.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT host(inet_server_addr()), inet_server_port(), current_database(),
                                pg_postmaster_start_time()
                         WHERE NOT pg_is_in_recovery()
                         """)) {
                if (!rows.next() || rows.getString(1) == null) {
                    throw new AuthorityUnavailableException();
                }
                var identity = new Identity(rows.getString(1), rows.getInt(2), rows.getString(3),
                        rows.getObject(4, OffsetDateTime.class).toInstant());
                JdbcCreateProof.requireWorkflow(proof, attempt.writer(), identity);
            }
            return work.apply(workflow);
        });
    }
}
