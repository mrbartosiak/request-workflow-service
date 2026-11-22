package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PublicationConfirmationStore;
import com.company.requests.application.PublicationJournal;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ReceiptKey;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Projects independently confirmed original publication evidence under the exclusive organization guard. */
public final class JdbcPublicationConfirmationStore implements PublicationConfirmationStore {
    private static final String EXACT = """
            attempt_id = ? AND writer_id = ? AND generation = ? AND organization = ? AND request_type = ?
              AND ref = ? AND namespace = ? AND publication_id = ? AND result_revision = ?
              AND workflow_transaction = CAST(? AS xid8)
            """;
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcPublicationConfirmationStore(DataSource source) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public boolean contains(PolicyGuard guard, Entry committed) {
        JdbcTransactionRunner.requireGuard(source, guard);
        var exact = exact(guard, committed);
        try {
            return jdbc.query("""
                    SELECT receipt_identity FROM requests.publication_confirmation WHERE
                    """ + EXACT, (row, index) -> row.getString(1), exact).stream()
                    .anyMatch(committed.attempt().receiptIdentity()::equals);
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    @Override
    public void record(PolicyGuard guard, Entry committed) {
        JdbcTransactionRunner.requireExclusiveGuard(source, guard);
        if (contains(guard, committed)) {
            return;
        }
        var attempt = committed.attempt();
        var exact = exact(guard, committed);
        try {
            var observed = jdbc.query("""
                    SELECT receipt_issuer, receipt_principal, receipt_token_id
                    FROM requests.publication_commit_evidence WHERE
                    """ + EXACT, (row, index) -> PublicationJournal.receiptIdentity(
                    new ReceiptKey(row.getString(1), guard.organization(), row.getString(2), row.getString(3))), exact);
            if (!observed.equals(List.of(attempt.receiptIdentity()))) {
                throw new AuthorityUnavailableException();
            }
            jdbc.update("""
                    INSERT INTO requests.publication_confirmation (
                        attempt_id, writer_id, generation, organization, request_type, ref, namespace,
                        publication_id, result_revision, workflow_transaction, receipt_identity
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS xid8), ?)
                    ON CONFLICT (attempt_id) DO NOTHING
                    """, attempt.id(), attempt.writer().writerId(), attempt.writer().generation(),
                    attempt.organization().value(), attempt.type().name(), attempt.ref().value(),
                    attempt.namespace(), committed.allocation().orElseThrow().value(), attempt.resultRevision(),
                    attempt.workflowTransaction(), attempt.receiptIdentity());
            if (!contains(guard, committed)) {
                throw new AuthorityUnavailableException();
            }
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private static Object[] exact(PolicyGuard guard, Entry committed) {
        var attempt = committed.attempt();
        if (committed.state() != State.COMMITTED || !guard.organization().equals(attempt.organization())) {
            throw new AuthorityUnavailableException();
        }
        return new Object[]{attempt.id(), attempt.writer().writerId(), attempt.writer().generation(),
                attempt.organization().value(), attempt.type().name(), attempt.ref().value(), attempt.namespace(),
                committed.allocation().orElseThrow().value(), attempt.resultRevision(), attempt.workflowTransaction()};
    }
}
