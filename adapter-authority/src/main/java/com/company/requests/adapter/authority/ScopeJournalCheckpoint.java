package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeCheckpoint;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

/** Checks the complete terminal prefix and binds its exact identity to the current projection. */
final class ScopeJournalCheckpoint {
    private ScopeJournalCheckpoint() { }

    static ScopeCheckpoint read(Connection proof, Admission admission) throws SQLException {
        var prefix = JdbcScopeAuthorityJournal.prefix(proof, admission);
        if (!prefix.contiguous() || prefix.unresolved() != 0) {
            throw new AuthorityUnavailableException();
        }
        long version = admission.genesis().policyVersion();
        var hash = digest();
        add(hash, "requests.scope-checkpoint.v1", JdbcScopeAuthorityJournal.organization(admission),
                Long.toString(version), admission.genesis().policyDigest(), admission.writer().writerId().toString(),
                Long.toString(admission.writer().generation()), Long.toString(prefix.head()));
        try (var query = proof.prepareStatement("""
                SELECT a.*, a.workflow_transaction::text AS workflow_transaction_text, c.evidence_digest,
                       t.attempt_id IS NOT NULL AS terminal_proved
                FROM request_authority.scope_journal_attempt a
                LEFT JOIN request_authority.scope_journal_confirmation c USING (attempt_id)
                LEFT JOIN request_authority.scope_journal_terminal_check t USING (attempt_id)
                WHERE a.organization = ? ORDER BY a.sequence NULLS LAST
                """)) {
            query.setString(1, JdbcScopeAuthorityJournal.organization(admission));
            try (var rows = query.executeQuery()) {
                long position = 0;
                while (rows.next()) {
                    var entry = JdbcScopeAuthorityJournal.readEntry(rows, admission);
                    if (!rows.getBoolean("terminal_proved")) {
                        throw new AuthorityUnavailableException();
                    }
                    if (entry.sequence().isEmpty()) {
                        continue;
                    }
                    if (entry.sequence().orElseThrow() != ++position || entry.attempt().beforeVersion() != version) {
                        throw new AuthorityUnavailableException();
                    }
                    var evidence = fingerprint(entry);
                    if (entry.state() == State.COMMITTED && evidence.equals(rows.getString("evidence_digest"))) {
                        version = entry.attempt().afterVersion();
                    } else if (entry.state() != State.ABORTED || rows.getString("evidence_digest") != null) {
                        throw new AuthorityUnavailableException();
                    }
                    add(hash, evidence);
                }
                if (position != prefix.head()) {
                    throw new AuthorityUnavailableException();
                }
            }
        }
        return new ScopeCheckpoint(admission, prefix.head(), version, HexFormat.of().formatHex(hash.digest()));
    }

    static String fingerprint(Entry entry) {
        var attempt = entry.attempt();
        var admission = attempt.admission();
        var hash = digest();
        add(hash, "requests.scope-terminal.v1", JdbcScopeAuthorityJournal.organization(admission),
                attempt.id().toString(), Long.toString(entry.sequence().orElseThrow()), entry.state().name(),
                admission.writer().writerId().toString(), Long.toString(admission.writer().generation()),
                attempt.workflowTransaction(), Long.toString(attempt.beforeVersion()), Long.toString(attempt.afterVersion()),
                attempt.deltaDigest());
        return HexFormat.of().formatHex(hash.digest());
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void add(MessageDigest digest, String... fields) {
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }
}
