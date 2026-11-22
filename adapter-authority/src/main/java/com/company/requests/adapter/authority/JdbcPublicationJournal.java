package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PublicationJournal;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Independently durable journal for the certified live-writer publication slice. */
public final class JdbcPublicationJournal implements PublicationJournal {
    private static final String ATTEMPT = """
            SELECT attempt_id, organization, request_type, ref, backend_pid, workflow_transaction::text,
                   receipt_identity, result_revision, publication_id, state
            FROM request_authority.publication_attempt
            WHERE namespace = 'initial-requests' AND generation = ?
            """;

    private final ProofTransactions preparation;
    private final ProofTransactions resolution;
    private final OriginalPublicationProof original;
    private final boolean auditedResolution;

    public JdbcPublicationJournal(DataSource preparation, DataSource resolution, DataSource originalObserver) {
        this(preparation, resolution, originalObserver, false);
    }

    public static JdbcPublicationJournal withAuditedResolution(DataSource preparation, DataSource resolution,
                                                                DataSource originalObserver) {
        return new JdbcPublicationJournal(preparation, resolution, originalObserver, true);
    }

    private JdbcPublicationJournal(DataSource preparation, DataSource resolution, DataSource originalObserver,
                                   boolean auditedResolution) {
        this.preparation = new ProofTransactions(preparation);
        this.resolution = new ProofTransactions(resolution);
        original = new OriginalPublicationProof(originalObserver);
        this.auditedResolution = auditedResolution;
    }

    @Override
    public Entry register(Attempt attempt) {
        return change(preparation, attempt, connection -> {
            var prior = find(connection, attempt);
            if (prior.isPresent()) {
                return prior.orElseThrow();
            }
            original.requireHeld(connection, attempt);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.publication_attempt (
                        attempt_id, namespace, generation, organization, request_type, ref, backend_pid,
                        workflow_transaction, receipt_identity, result_revision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS xid8), ?, ?)
                    """)) {
                insert.setObject(1, attempt.id());
                insert.setString(2, attempt.namespace());
                insert.setLong(3, attempt.writer().generation());
                insert.setString(4, attempt.organization().value());
                insert.setString(5, attempt.type().name());
                insert.setString(6, attempt.ref().value());
                insert.setInt(7, attempt.backendPid());
                insert.setString(8, attempt.workflowTransaction());
                insert.setString(9, attempt.receiptIdentity());
                insert.setLong(10, attempt.resultRevision());
                if (insert.executeUpdate() != 1) {
                    throw new AuthorityUnavailableException();
                }
            }
            return new Entry(attempt, Optional.empty(), State.REGISTERED);
        });
    }

    @Override
    public Entry reserve(Attempt attempt) {
        return change(preparation, attempt, connection -> {
            Entry prior = requireEntry(connection, attempt);
            if (prior.state() != State.REGISTERED) {
                return prior;
            }
            original.requireHeld(connection, attempt);
            try (var allocate = connection.prepareStatement("SELECT request_authority.allocate_publication(?)")) {
                allocate.setObject(1, attempt.id());
                try (var rows = allocate.executeQuery()) {
                    if (!rows.next() || rows.getObject(1, Long.class) == null) {
                        throw new AuthorityUnavailableException();
                    }
                    return new Entry(attempt, Optional.of(new PublicationId(rows.getLong(1))), State.PENDING);
                }
            }
        });
    }

    @Override
    public Optional<Entry> find(Attempt attempt) {
        return preparation.run(true, connection -> {
            requireEpoch(connection, attempt.writer());
            return find(connection, attempt);
        });
    }

    @Override
    public Optional<Entry> activeBinding(WriterEpoch writer, OrganizationId organization, RequestType type,
                                         RequestRef ref) {
        Objects.requireNonNull(organization);
        Objects.requireNonNull(type);
        Objects.requireNonNull(ref);
        return preparation.run(true, connection -> {
            requireEpoch(connection, writer);
            try (var query = connection.prepareStatement(ATTEMPT + """
                    AND ref = ? AND state IN ('PENDING', 'COMMITTED')
                    """)) {
                query.setLong(1, writer.generation());
                query.setString(2, ref.value());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    Entry entry = read(rows, writer);
                    if (!entry.attempt().organization().equals(organization) || entry.attempt().type() != type) {
                        throw new AuthorityUnavailableException();
                    }
                    return Optional.of(entry);
                }
            }
        });
    }

    @Override
    public Entry confirm(Attempt attempt) {
        return change(resolution, attempt, connection -> {
            requireResolution(connection, attempt, State.COMMITTED);
            Entry prior = requireEntry(connection, attempt);
            if (prior.state() != State.COMMITTED) {
                if (prior.state() != State.PENDING) {
                    throw new AuthorityUnavailableException();
                }
                original.requireCommitted(connection, prior);
            }
            return finish(connection, prior, State.COMMITTED);
        });
    }

    @Override
    public Entry abort(Attempt attempt) {
        return change(resolution, attempt, connection -> {
            requireResolution(connection, attempt, State.ABORTED);
            Entry prior = requireEntry(connection, attempt);
            if (prior.state() == State.COMMITTED) {
                throw new AuthorityUnavailableException();
            }
            if (prior.state() != State.ABORTED) {
                original.requireAborted(connection, attempt);
            }
            return finish(connection, prior, State.ABORTED);
        });
    }

    private void requireResolution(Connection connection, Attempt attempt, State terminal) throws SQLException {
        if (auditedResolution) {
            requireDecision(connection, "SELECT request_authority.may_resolve_publication(?, ?)", attempt, terminal);
        }
    }

    private Entry finish(Connection connection, Entry prior, State terminal) throws SQLException {
        if (auditedResolution) {
            requireDecision(connection, "SELECT request_authority.finish_audited_publication(?, ?)",
                    prior.attempt(), terminal);
        } else if (prior.state() != terminal) {
            requireDecision(connection, "SELECT request_authority.finish_publication(?, ?)", prior.attempt(), terminal);
        }
        return new Entry(prior.attempt(), prior.allocation(), terminal);
    }

    private static void requireDecision(Connection connection, String sql, Attempt attempt, State terminal)
            throws SQLException {
        try (var change = connection.prepareStatement(sql)) {
            change.setObject(1, attempt.id());
            change.setString(2, terminal.name());
            try (var rows = change.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new AuthorityUnavailableException();
                }
            }
        }
    }

    private static Entry requireEntry(Connection connection, Attempt attempt) throws SQLException {
        return find(connection, attempt).orElseThrow(AuthorityUnavailableException::new);
    }

    private static Optional<Entry> find(Connection connection, Attempt attempt) throws SQLException {
        try (var query = connection.prepareStatement(ATTEMPT + " AND attempt_id = ?")) {
            query.setLong(1, attempt.writer().generation());
            query.setObject(2, attempt.id());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                var prior = read(rows, attempt.writer());
                if (!prior.attempt().equals(attempt)) {
                    throw new AuthorityUnavailableException();
                }
                return Optional.of(prior);
            }
        }
    }

    private static Entry read(ResultSet rows, WriterEpoch writer) throws SQLException {
        var attempt = new Attempt(rows.getObject(1, UUID.class), writer, new OrganizationId(rows.getString(2)),
                RequestType.valueOf(rows.getString(3)), new RequestRef(rows.getString(4)), rows.getInt(5),
                rows.getString(6), rows.getString(7), rows.getLong(8));
        var allocation = Optional.ofNullable(rows.getObject(9, Long.class)).map(PublicationId::new);
        return new Entry(attempt, allocation, State.valueOf(rows.getString(10)));
    }

    private static Entry change(ProofTransactions transactions, Attempt attempt, ProofTransactions.Work<Entry> change) {
        return transactions.run(false, connection -> {
            // The proof lock covers verification and persistence, including closure racing a delayed reservation.
            try (var lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(1919250805, 17)");
            }
            requireEpoch(connection, attempt.writer());
            return change.apply(connection);
        });
    }

    private static void requireEpoch(Connection connection, WriterEpoch writer) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM request_authority.writer_epoch w
                JOIN request_authority.workflow_certificate c USING (generation)
                CROSS JOIN request_authority.publication_namespace n
                WHERE w.writer_id = ? AND generation = ? AND n.namespace = 'initial-requests'
                  AND NOT pg_is_in_recovery() AND c.server_started_at <> pg_postmaster_start_time()
                """)) {
            query.setObject(1, writer.writerId());
            query.setLong(2, writer.generation());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AuthorityUnavailableException();
                }
            }
        }
    }
}
