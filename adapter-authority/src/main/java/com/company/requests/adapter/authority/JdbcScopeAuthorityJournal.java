package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.ScopeAuthority;
import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.LocalAttempt;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityEvidence;
import com.company.requests.application.ScopeAuthorityJournal;
import com.company.requests.application.WriterLineage.Identity;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;

/** Independent ordered journal and readiness gate for an explicitly enabled scope-proof deployment. */
public final class JdbcScopeAuthorityJournal implements ScopeAuthorityJournal, ScopeAuthorityEvidence, ScopeAuthority {
    private static final HexFormat HEX = HexFormat.of();
    private static final String ENTRY = """
            SELECT attempt_id, organization, generation, workflow_transaction::text,
                   before_version, after_version, delta_digest, sequence, state
            FROM request_authority.scope_journal_attempt
            """;

    private final ProofTransactions journal;
    private final ProofTransactions originalWorkflow;

    public JdbcScopeAuthorityJournal(DataSource independentJournal, DataSource originalWorkflowObserver) {
        journal = new ProofTransactions(independentJournal);
        originalWorkflow = new ProofTransactions(originalWorkflowObserver);
    }

    @Override
    public void requireCurrent(Admission admission) {
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
            var prefix = prefix(connection, admission);
            if (!prefix.contiguous() || prefix.unresolved() != 0 || prefix.committed() != 0
                    || attempt.beforeVersion() != admission.genesis().policyVersion()
                    || !"in progress".equals(originalStatus(connection, attempt))) {
                throw new AuthorityUnavailableException();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_journal_attempt (
                        attempt_id, organization, generation, workflow_transaction,
                        before_version, after_version, delta_digest, state
                    ) VALUES (?, ?, ?, CAST(? AS xid8), ?, ?, ?, 'REGISTERED')
                    """)) {
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
            if (!prefix.contiguous() || prefix.unresolved() != 1 || prefix.committed() != 0
                    || prefix.head() == Long.MAX_VALUE) {
                throw new AuthorityUnavailableException();
            }
            long sequence = prefix.head() + 1;
            try (var payload = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_journal_delta(attempt_id, delta_digest, exact_delta)
                    VALUES (?, ?, ?)
                    """);
                 var pending = connection.prepareStatement("""
                    UPDATE request_authority.scope_journal_attempt SET sequence = ?, state = 'PENDING'
                    WHERE attempt_id = ? AND state = 'REGISTERED'
                    """);
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
        return journal.run(false, connection -> {
            lockAttemptAndScope(connection, admission, attemptId);
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            Entry entry = find(connection, admission, attemptId).orElseThrow(AuthorityUnavailableException::new);
            if (entry.state() == State.ABORTED) {
                return entry;
            }
            if (entry.state() == State.COMMITTED || !"aborted".equals(originalStatus(connection, entry.attempt()))) {
                throw new AuthorityUnavailableException();
            }
            try (var update = connection.prepareStatement("""
                    UPDATE request_authority.scope_journal_attempt SET state = 'ABORTED'
                    WHERE attempt_id = ? AND state IN ('REGISTERED', 'PENDING')
                    """)) {
                update.setObject(1, attemptId);
                requireOne(update.executeUpdate());
            }
            return new Entry(entry.attempt(), entry.sequence(), State.ABORTED);
        });
    }

    private String originalStatus(Connection proof, Attempt attempt) {
        return originalWorkflow.run(true, workflow -> {
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
                JdbcCreateProof.requireWorkflow(proof, attempt.admission().writer(), identity);
            }
            try (var query = workflow.prepareStatement("SELECT pg_xact_status(CAST(? AS xid8))")) {
                query.setString(1, attempt.workflowTransaction());
                try (var rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }

    private static Optional<Entry> find(Connection connection, Admission admission, UUID id) throws SQLException {
        try (var query = connection.prepareStatement(ENTRY + "WHERE attempt_id = ?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(readEntry(rows, admission)) : Optional.empty();
            }
        }
    }

    private static Entry readEntry(ResultSet row, Admission admission) throws SQLException {
        if (!organization(admission).equals(row.getString("organization"))
                || admission.writer().generation() != row.getLong("generation")) {
            throw new AuthorityUnavailableException();
        }
        var attempt = new Attempt(row.getObject("attempt_id", UUID.class), admission, row.getLong("before_version"),
                row.getLong("after_version"), HEX.formatHex(row.getBytes("delta_digest")),
                row.getString("workflow_transaction"));
        long sequence = row.getLong("sequence");
        return new Entry(attempt, row.wasNull() ? OptionalLong.empty() : OptionalLong.of(sequence),
                State.valueOf(row.getString("state")));
    }

    private static Prefix prefix(Connection connection, Admission admission) throws SQLException {
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

    private static void lockAttemptAndScope(Connection connection, Admission admission, UUID id) throws SQLException {
        // The bigint and two-int advisory lock namespaces do not overlap.
        try (var attempt = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 19009))");
             var scope = connection.prepareStatement("SELECT pg_advisory_xact_lock(19009, hashtext(?))")) {
            attempt.setString(1, id.toString());
            attempt.execute();
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

    private static String organization(Admission admission) {
        return admission.genesis().organization().value();
    }

    private static void requireOne(int rows) {
        if (rows != 1) {
            throw new AuthorityUnavailableException();
        }
    }

    private record Prefix(long head, boolean contiguous, long unresolved, long committed) { }
}
