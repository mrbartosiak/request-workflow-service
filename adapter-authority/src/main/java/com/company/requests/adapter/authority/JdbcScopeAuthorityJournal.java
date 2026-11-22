package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.ConfirmedScopeAuthority;
import com.company.requests.application.ScopeAuthority;
import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.LocalAttempt;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityEvidence;
import com.company.requests.application.ScopeAuthorityJournal;
import com.company.requests.application.ScopeCheckpoint;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;

/** Independent ordered journal and readiness gate for an explicitly enabled scope-proof deployment. */
public final class JdbcScopeAuthorityJournal implements ScopeAuthorityJournal, ScopeAuthorityEvidence, ScopeAuthority {
    private static final HexFormat HEX = HexFormat.of();
    private static final String ENTRY = """
            SELECT attempt_id, organization, generation, workflow_transaction::text AS workflow_transaction_text,
                   before_version, after_version, delta_digest, sequence, state
            FROM request_authority.scope_journal_attempt
            """;

    private final ProofTransactions journal;
    private final OriginalWorkflowProof originalWorkflow;
    private final boolean confirmedProjection;

    public JdbcScopeAuthorityJournal(DataSource independentJournal, DataSource originalWorkflowObserver) {
        this(independentJournal, originalWorkflowObserver, false);
    }

    public static JdbcScopeAuthorityJournal withConfirmedProjection(DataSource independentJournal,
                                                                    DataSource originalWorkflowObserver) {
        return new JdbcScopeAuthorityJournal(independentJournal, originalWorkflowObserver, true);
    }

    private JdbcScopeAuthorityJournal(DataSource independentJournal, DataSource originalWorkflowObserver,
                                      boolean confirmedProjection) {
        journal = new ProofTransactions(independentJournal);
        originalWorkflow = new OriginalWorkflowProof(originalWorkflowObserver);
        this.confirmedProjection = confirmedProjection;
    }

    public ConfirmedScopeAuthority confirmedAuthority() {
        if (!confirmedProjection) {
            throw new IllegalStateException("Confirmed scope projection has not been enabled.");
        }
        return admission -> journal.run(true, connection -> {
            lockScope(connection, admission, true);
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            return requireProjection(connection, admission);
        });
    }

    @Override
    public void requireCurrent(Admission admission) {
        if (confirmedProjection) {
            confirmedAuthority().requireCurrent(admission);
            return;
        }
        journal.run(true, connection -> {
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            var prefix = prefix(connection, admission);
            if (!prefix.contiguous() || prefix.unresolved() != 0 || prefix.committed() != 0) {
                // B09 supports the genesis projection. A resolver must install a verified newer projection.
                throw new AuthorityUnavailableException();
            }
            return null;
        });
    }

    @Override
    public Entry register(LocalAttempt local) {
        Attempt attempt = local.attempt();
        Admission admission = attempt.admission();
        return journal.run(false, connection -> {
            lockAttemptAndScope(connection, admission, attempt.id());
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            JdbcCreateProof.requireWorkflow(connection, admission.writer(), local.writer());
            Optional<Entry> prior = find(connection, admission, attempt.id());
            if (prior.isPresent()) {
                return requireExact(prior.get(), attempt);
            }
            long currentVersion = confirmedProjection ? requireProjection(connection, admission).policyVersion()
                    : requireGenesisPrefix(connection, admission);
            if (attempt.beforeVersion() != currentVersion
                    || !"in progress".equals(originalWorkflow.status(connection, attempt))) {
                throw new AuthorityUnavailableException();
            }
            String stateColumn = confirmedProjection ? "" : ", state";
            String stateValue = confirmedProjection ? "" : ", 'REGISTERED'";
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_journal_attempt (
                        attempt_id, organization, generation, workflow_transaction,
                        before_version, after_version, delta_digest%s
                    ) VALUES (?, ?, ?, CAST(? AS xid8), ?, ?, ?%s)
                    """.formatted(stateColumn, stateValue))) {
                insert.setObject(1, attempt.id());
                insert.setString(2, organization(admission));
                insert.setLong(3, admission.writer().generation());
                insert.setString(4, attempt.workflowTransaction());
                insert.setLong(5, attempt.beforeVersion());
                insert.setLong(6, attempt.afterVersion());
                insert.setBytes(7, HEX.parseHex(attempt.deltaDigest()));
                requireOne(insert.executeUpdate());
            }
            return new Entry(attempt, OptionalLong.empty(), State.REGISTERED);
        });
    }

    @Override
    public Entry prepare(Attempt attempt, Delta delta) {
        if (!attempt.deltaDigest().equals(delta.digest())) {
            throw new AuthorityUnavailableException();
        }
        Admission admission = attempt.admission();
        return journal.run(false, connection -> {
            lockAttemptAndScope(connection, admission, attempt.id());
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            Entry prior = requireExact(find(connection, admission, attempt.id())
                    .orElseThrow(AuthorityUnavailableException::new), attempt);
            if (prior.state() != State.REGISTERED) {
                return prior;
            }
            var prefix = prefix(connection, admission);
            if (!prefix.contiguous() || prefix.unresolved() != 1 || (!confirmedProjection && prefix.committed() != 0)
                    || prefix.head() == Long.MAX_VALUE) {
                throw new AuthorityUnavailableException();
            }
            long sequence = prefix.head() + 1;
            // V004 derives PENDING from assigning the sequence. V003 still uses its original statement.
            String prepareUpdate = confirmedProjection
                    ? "UPDATE request_authority.scope_journal_attempt SET sequence = ? WHERE attempt_id = ? AND state = 'REGISTERED'"
                    : """
                      UPDATE request_authority.scope_journal_attempt SET sequence = ?, state = 'PENDING'
                      WHERE attempt_id = ? AND state = 'REGISTERED'
                      """;
            try (var payload = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_journal_delta(attempt_id, delta_digest, exact_delta)
                    VALUES (?, ?, ?)
                    """);
                 var pending = connection.prepareStatement(prepareUpdate);
                 var advance = connection.prepareStatement("""
                    UPDATE request_authority.scope_journal_head SET last_sequence = ?
                    WHERE organization = ? AND last_sequence = ?
                    """)) {
                payload.setObject(1, attempt.id());
                payload.setBytes(2, HEX.parseHex(delta.digest()));
                payload.setBytes(3, delta.bytes());
                requireOne(payload.executeUpdate());
                pending.setLong(1, sequence);
                pending.setObject(2, attempt.id());
                requireOne(pending.executeUpdate());
                advance.setLong(1, sequence);
                advance.setString(2, organization(admission));
                advance.setLong(3, prefix.head());
                requireOne(advance.executeUpdate());
            }
            return new Entry(attempt, OptionalLong.of(sequence), State.PENDING);
        });
    }

    @Override
    public Optional<Entry> find(Admission admission, UUID attemptId) {
        return journal.run(true, connection -> {
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            return find(connection, admission, attemptId);
        });
    }

    @Override
    public Optional<Entry> findUnresolved(Admission admission) {
        return journal.run(true, connection -> {
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            try (var query = connection.prepareStatement(ENTRY + """
                    WHERE organization = ? AND state IN ('REGISTERED', 'PENDING')
                    """)) {
                query.setString(1, organization(admission));
                try (var rows = query.executeQuery()) {
                    return rows.next() ? Optional.of(readEntry(rows, admission)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Entry abort(Admission admission, UUID attemptId) {
        if (confirmedProjection) {
            // In the enabled deployment, terminal decisions go through the audited resolver.
            throw new AuthorityUnavailableException();
        }
        return journal.run(false, connection -> {
            lockAttemptAndScope(connection, admission, attemptId);
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            Entry entry = find(connection, admission, attemptId).orElseThrow(AuthorityUnavailableException::new);
            if (entry.state() == State.ABORTED) {
                return entry;
            }
            if (entry.state() == State.COMMITTED || !"aborted".equals(originalWorkflow.status(connection, entry.attempt()))) {
                throw new AuthorityUnavailableException();
            }
            return transition(connection, entry, State.ABORTED);
        });
    }

    private ScopeCheckpoint requireProjection(Connection connection, Admission admission) throws SQLException {
        var checkpoint = ScopeJournalCheckpoint.read(connection, admission);
        originalWorkflow.requireProjection(connection, checkpoint);
        return checkpoint;
    }

    private static long requireGenesisPrefix(Connection connection, Admission admission) throws SQLException {
        var prefix = prefix(connection, admission);
        if (!prefix.contiguous() || prefix.unresolved() != 0 || prefix.committed() != 0) {
            throw new AuthorityUnavailableException();
        }
        return admission.genesis().policyVersion();
    }

    private static Entry transition(Connection connection, Entry entry, State terminal) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE request_authority.scope_journal_attempt SET state = ?
                WHERE attempt_id = ? AND state = ?
                """)) {
            update.setString(1, terminal.name());
            update.setObject(2, entry.attempt().id());
            update.setString(3, entry.state().name());
            requireOne(update.executeUpdate());
        }
        return new Entry(entry.attempt(), entry.sequence(), terminal);
    }

    private static Optional<Entry> find(Connection connection, Admission admission, UUID id) throws SQLException {
        try (var query = connection.prepareStatement(ENTRY + "WHERE attempt_id = ?")) {
            query.setObject(1, id);
            return find(query, admission);
        }
    }

    static Optional<Entry> findScoped(Connection connection, Admission admission, UUID id) throws SQLException {
        try (var query = connection.prepareStatement(ENTRY + "WHERE attempt_id = ? AND organization = ?")) {
            query.setObject(1, id);
            query.setString(2, organization(admission));
            return find(query, admission);
        }
    }

    private static Optional<Entry> find(java.sql.PreparedStatement query, Admission admission) throws SQLException {
        try (var rows = query.executeQuery()) {
            return rows.next() ? Optional.of(readEntry(rows, admission)) : Optional.empty();
        }
    }

    static Entry readEntry(ResultSet row, Admission admission) throws SQLException {
        if (!organization(admission).equals(row.getString("organization"))
                || admission.writer().generation() != row.getLong("generation")) {
            throw new AuthorityUnavailableException();
        }
        var attempt = new Attempt(row.getObject("attempt_id", UUID.class), admission, row.getLong("before_version"),
                row.getLong("after_version"), HEX.formatHex(row.getBytes("delta_digest")),
                row.getString("workflow_transaction_text"));
        long sequence = row.getLong("sequence");
        return new Entry(attempt, row.wasNull() ? OptionalLong.empty() : OptionalLong.of(sequence),
                State.valueOf(row.getString("state")));
    }

    static Prefix prefix(Connection connection, Admission admission) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT h.last_sequence, count(a.sequence), coalesce(max(a.sequence), 0),
                       count(*) FILTER (WHERE a.state IN ('REGISTERED', 'PENDING')),
                       count(*) FILTER (WHERE a.state = 'COMMITTED')
                FROM request_authority.scope_journal_head h
                LEFT JOIN request_authority.scope_journal_attempt a USING (organization)
                WHERE organization = ? GROUP BY h.last_sequence
                """)) {
            query.setString(1, organization(admission));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AuthorityUnavailableException();
                }
                long head = rows.getLong(1);
                return new Prefix(head, rows.getLong(2) == head && rows.getLong(3) == head,
                        rows.getLong(4), rows.getLong(5));
            }
        }
    }

    static void lockAttemptAndScope(Connection connection, Admission admission, UUID id) throws SQLException {
        // The bigint and two-int advisory lock namespaces do not overlap.
        try (var attempt = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 19009))")) {
            attempt.setString(1, id.toString());
            attempt.execute();
        }
        lockScope(connection, admission, false);
    }

    private static void lockScope(Connection connection, Admission admission, boolean shared) throws SQLException {
        String lock = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
        try (var scope = connection.prepareStatement("SELECT " + lock + "(19009, hashtext(?))")) {
            scope.setString(1, organization(admission));
            scope.execute();
        }
    }

    private static Entry requireExact(Entry entry, Attempt attempt) {
        if (!entry.attempt().equals(attempt)) {
            throw new AuthorityUnavailableException();
        }
        return entry;
    }

    static String organization(Admission admission) {
        return admission.genesis().organization().value();
    }

    static void requireOne(int rows) {
        if (rows != 1) {
            throw new AuthorityUnavailableException();
        }
    }

    record Prefix(long head, boolean contiguous, long unresolved, long committed) { }
}
