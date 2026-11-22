package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityResolution;
import com.company.requests.application.WriterLineage.Identity;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Private named resolution. The service role sees only database-side integrity checks for protected deltas. */
public final class JdbcScopeAuthorityResolver implements ScopeAuthorityResolution {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final ProofTransactions journal;
    private final OriginalWorkflowProof original;

    public JdbcScopeAuthorityResolver(DataSource independentResolver, DataSource originalWorkflowObserver) {
        journal = new ProofTransactions(independentResolver);
        original = new OriginalWorkflowProof(originalWorkflowObserver);
    }

    @Override
    public Optional<Work> authorize(OperatorCredential credential, Admission admission, UUID attemptId,
                                    Operation operation) {
        var work = new Work(UUID.randomUUID(), admission, attemptId, operation);
        byte[] fingerprint = credentialDigest(credential);
        return journal.run(false, connection -> {
            var authorization = authorization(connection, work, fingerprint);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.scope_resolver_audit
                        (audit_id, operator_id, organization, attempt_id, operation, authorized)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setObject(1, work.auditId());
                insert.setObject(2, authorization.operator());
                insert.setString(3, organization(work));
                insert.setObject(4, attemptId);
                insert.setString(5, operation.name());
                insert.setBoolean(6, authorization.allowed());
                JdbcScopeAuthorityJournal.requireOne(insert.executeUpdate());
            }
            return authorization.allowed() ? Optional.of(work) : Optional.empty();
        });
    }

    @Override
    public Terminal resolve(Work work, Identity guardedWriter, PolicyGuard guard) {
        return journal.run(false, connection -> {
            requireAuthorized(connection, work);
            var admission = work.admission();
            JdbcScopeAuthorityJournal.lockAttemptAndScope(connection, admission, work.attemptId());
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            JdbcCreateProof.requireWorkflow(connection, admission.writer(), guardedWriter);
            var entry = JdbcScopeAuthorityJournal.findScoped(connection, admission, work.attemptId())
                    .orElseThrow(AuthorityUnavailableException::new);
            if (entry.sequence().isPresent()) {
                requireStoredDelta(connection, entry);
            }
            var resolved = switch (work.operation()) {
                case CONFIRM -> confirm(connection, entry);
                case ABORT -> abort(connection, entry);
            };
            var checkpoint = ScopeJournalCheckpoint.read(connection, admission);
            checkpoint.requireGuard(guard);
            try (var update = connection.prepareStatement("""
                    UPDATE request_authority.scope_resolver_audit SET terminal_state = ? WHERE audit_id = ?
                    """)) {
                update.setString(1, resolved.state().name());
                update.setObject(2, work.auditId());
                JdbcScopeAuthorityJournal.requireOne(update.executeUpdate());
            }
            return new Terminal(resolved.state(), checkpoint);
        });
    }

    @Override
    public void finish(Work work, Outcome outcome) {
        journal.run(false, connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE request_authority.scope_resolver_audit SET outcome = ?
                    WHERE audit_id = ? AND organization = ? AND attempt_id = ? AND operation = ?
                      AND authorized AND outcome IS NULL
                    """)) {
                update.setString(1, outcome.name());
                update.setObject(2, work.auditId());
                update.setString(3, organization(work));
                update.setObject(4, work.attemptId());
                update.setString(5, work.operation().name());
                JdbcScopeAuthorityJournal.requireOne(update.executeUpdate());
            }
            return null;
        });
    }

    private Entry confirm(Connection connection, Entry entry) throws SQLException {
        if (entry.state() != State.PENDING && entry.state() != State.COMMITTED) {
            throw new AuthorityUnavailableException();
        }
        original.requireWitness(connection, entry);
        if (entry.state() == State.COMMITTED) {
            return entry;
        }
        var committed = JdbcScopeAuthorityJournal.transition(connection, entry, State.COMMITTED);
        try (var insert = connection.prepareStatement("""
                INSERT INTO request_authority.scope_journal_confirmation (attempt_id, evidence_digest) VALUES (?, ?)
                """)) {
            insert.setObject(1, entry.attempt().id());
            insert.setString(2, ScopeJournalCheckpoint.fingerprint(committed));
            JdbcScopeAuthorityJournal.requireOne(insert.executeUpdate());
        }
        return committed;
    }

    private Entry abort(Connection connection, Entry entry) throws SQLException {
        if (entry.state() == State.ABORTED) {
            return entry;
        }
        if (entry.state() == State.COMMITTED || !"aborted".equals(original.status(connection, entry.attempt()))) {
            throw new AuthorityUnavailableException();
        }
        return JdbcScopeAuthorityJournal.transition(connection, entry, State.ABORTED);
    }

    private static void requireStoredDelta(Connection connection, Entry entry) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM request_authority.scope_resolver_delta_check
                WHERE attempt_id = ? AND delta_digest = ? AND exact
                """)) {
            query.setObject(1, entry.attempt().id());
            query.setBytes(2, HexFormat.of().parseHex(entry.attempt().deltaDigest()));
            requireRow(query);
        }
    }

    private static Authorization authorization(Connection connection, Work work, byte[] fingerprint) throws SQLException {
        if (fingerprint == null) {
            return new Authorization(null, false);
        }
        try (var query = connection.prepareStatement("""
                SELECT o.operator_id, g.operator_id IS NOT NULL
                FROM request_authority.scope_resolver_operator o
                LEFT JOIN request_authority.scope_resolver_grant g
                    ON g.operator_id = o.operator_id AND g.organization = ? AND g.operation = ?
                WHERE o.credential_digest = ?
                """)) {
            query.setString(1, organization(work));
            query.setString(2, work.operation().name());
            query.setBytes(3, fingerprint);
            try (var rows = query.executeQuery()) {
                return rows.next() ? new Authorization(rows.getObject(1, UUID.class), rows.getBoolean(2))
                        : new Authorization(null, false);
            }
        }
    }

    private static void requireAuthorized(Connection connection, Work work) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM request_authority.scope_resolver_audit a
                JOIN request_authority.scope_resolver_grant g USING (operator_id, organization, operation)
                WHERE a.audit_id = ? AND a.organization = ? AND a.attempt_id = ? AND a.operation = ?
                  AND a.authorized AND a.terminal_state IS NULL AND a.outcome IS NULL
                FOR UPDATE OF a
                """)) {
            query.setObject(1, work.auditId());
            query.setString(2, organization(work));
            query.setObject(3, work.attemptId());
            query.setString(4, work.operation().name());
            requireRow(query);
        }
    }

    private static void requireRow(java.sql.PreparedStatement query) throws SQLException {
        try (var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new AuthorityUnavailableException();
            }
        }
    }

    static byte[] credentialDigest(OperatorCredential credential) {
        if (credential == null || credential.secret() == null || !TOKEN.matcher(credential.secret()).matches()) {
            return null;
        }
        return ScopeJournalCheckpoint.digest().digest(credential.secret().getBytes(StandardCharsets.US_ASCII));
    }

    private static String organization(Work work) {
        return JdbcScopeAuthorityJournal.organization(work.admission());
    }

    private record Authorization(UUID operator, boolean allowed) { }
}
