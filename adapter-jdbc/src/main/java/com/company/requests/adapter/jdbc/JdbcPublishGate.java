package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PublicationJournal;
import com.company.requests.application.PublicationJournal.Attempt;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.PublishGate;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.TransactionBusyException;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-scoped PostgreSQL gate for the shared initial publication namespace. */
public final class JdbcPublishGate implements PublishGate {
    private static final Object HELD_GATE = new Object();
    private final DataSource workflow;
    private final JdbcTemplate jdbc;
    private final JdbcReceiptStore receipts;

    public JdbcPublishGate(DataSource workflow) {
        this.workflow = Objects.requireNonNull(workflow);
        jdbc = new JdbcTemplate(workflow);
        receipts = new JdbcReceiptStore(workflow);
    }

    @Override
    public Attempt acquire(PolicyGuard guard, WriterEpoch writer, RequestRef ref, ReceiptKey receipt) {
        JdbcTransactionRunner.requireExclusiveGuard(workflow, guard);
        if (TransactionSynchronizationManager.hasResource(HELD_GATE)) {
            throw new IllegalStateException("A command transaction may acquire only one publication gate.");
        }
        if (!guard.organization().equals(receipt.organization())) {
            throw new AuthorityUnavailableException();
        }
        try {
            jdbc.execute("SET LOCAL lock_timeout = '200ms'");
            jdbc.execute("SET LOCAL statement_timeout = '400ms'");
            var target = jdbc.query("""
                    SELECT request_type, revision
                    FROM requests.request
                    WHERE organization = ? AND ref = ? AND state = 'ACCEPTED'
                    FOR UPDATE
                    """, (row, index) -> new Target(RequestType.valueOf(row.getString(1)), row.getLong(2)),
                    guard.organization().value(), ref.value()).stream().findFirst()
                    .orElseThrow(AuthorityUnavailableException::new);
            if (target.revision() == Long.MAX_VALUE) {
                throw new AuthorityUnavailableException();
            }

            // The database-wide lock orders all reservations and ends with this transaction.
            jdbc.execute("SELECT pg_advisory_xact_lock(1919250805, 16)");
            var attempt = jdbc.queryForObject("SELECT pg_backend_pid(), pg_current_xact_id()::text",
                    (row, index) -> new Attempt(UUID.randomUUID(), writer, guard.organization(), target.type(), ref,
                            row.getInt(1), row.getString(2), PublicationJournal.receiptIdentity(receipt),
                            target.revision() + 1));
            // Eight indexed lock words let the independent observer attest the complete digest.
            // PostgreSQL releases the words and the namespace lock together at transaction end.
            jdbc.execute("""
                    SELECT pg_advisory_xact_lock(1919250810 + lane,
                           ('x' || substr(?, 1 + lane * 8, 8))::bit(32)::integer)
                    FROM generate_series(0, 7) AS binding(lane)
                    """, (PreparedStatement statement) -> {
                statement.setString(1, attempt.gateBinding());
                statement.execute();
                return null;
            });

            var held = new Held(workflow, guard, attempt);
            TransactionSynchronizationManager.bindResource(HELD_GATE, held);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResource(HELD_GATE);
                }
            });
            return attempt;
        } catch (DataAccessException failure) {
            if (failure instanceof PessimisticLockingFailureException || failure instanceof QueryTimeoutException
                    || (failure.getMostSpecificCause() instanceof SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState())))) {
                throw new TransactionBusyException(failure);
            }
            throw failure;
        }
    }

    @Override
    public void appendWitness(Entry pending, OperationReceipt receipt) {
        JdbcTransactionRunner.requireBoundConnection(workflow);
        Attempt attempt = pending.attempt();
        if (!(TransactionSynchronizationManager.getResource(HELD_GATE) instanceof Held held)
                || held.workflow() != workflow || !held.attempt().equals(attempt)) {
            throw new IllegalStateException("The publication witness requires the original command's namespace gate.");
        }
        JdbcTransactionRunner.requireExclusiveGuard(workflow, held.guard());
        if (pending.state() != State.PENDING || receipt.action() != Action.PUBLISH
                || !receipt.key().organization().equals(attempt.organization())
                || !PublicationJournal.receiptIdentity(receipt.key()).equals(attempt.receiptIdentity())
                || !receipt.resultRef().equals(attempt.ref()) || receipt.resultRevision() != attempt.resultRevision()
                || receipt.keyGeneration() != attempt.writer().generation()
                || !receipt.publicationId().equals(pending.allocation())
                || receipts.find(receipt.key()).filter(receipt::equals).isEmpty()) {
            throw new AuthorityUnavailableException();
        }
        ReceiptKey key = receipt.key();
        jdbc.update("""
                INSERT INTO requests.publication_witness (
                    attempt_id, writer_id, generation, organization, request_type, ref, namespace, publication_id,
                    receipt_issuer, receipt_principal, receipt_token_id, result_revision, workflow_transaction
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, pg_current_xact_id())
                """, attempt.id(), attempt.writer().writerId(), attempt.writer().generation(),
                attempt.organization().value(), attempt.type().name(), attempt.ref().value(), attempt.namespace(),
                pending.allocation().orElseThrow().value(), key.issuer(), key.principal(), key.tokenId(),
                attempt.resultRevision());
    }

    static void requireHeld(DataSource workflow, PolicyGuard guard, RequestRef ref, long resultRevision) {
        if (!(TransactionSynchronizationManager.getResource(HELD_GATE) instanceof Held held)
                || held.workflow() != workflow || !held.guard().equals(guard)
                || !held.attempt().ref().equals(ref) || held.attempt().resultRevision() != resultRevision) {
            throw new IllegalStateException("The publication update requires the original command's namespace gate.");
        }
    }

    static void requireOutboxTarget(DataSource workflow, OutboundObligation obligation) {
        JdbcTransactionRunner.requireBoundConnection(workflow);
        if (!(TransactionSynchronizationManager.getResource(HELD_GATE) instanceof Held held)
                || !held.attempt().organization().equals(obligation.tenant())
                || !OutboundObligation.effectIdentity(held.attempt().receiptIdentity(), obligation.triggerSlot())
                        .equals(obligation.effectId())) {
            throw new IllegalStateException("The obligation requires the original, scoped Publish effect.");
        }
        requireHeld(workflow, held.guard(), obligation.ref(), obligation.revision());
        JdbcTransactionRunner.requireExclusiveGuard(workflow, held.guard());
    }

    private record Target(RequestType type, long revision) {
    }

    private record Held(DataSource workflow, PolicyGuard guard, Attempt attempt) {
    }
}
