package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.InboundInbox;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores the immutable receipt, processing obligation and quarantine on the bound workflow connection. */
public final class JdbcInboundInbox implements InboundInbox {
    private static final String KEY_COLUMNS = "source_system, source_tenant, identity_domain, source_identity";
    private static final String RECEIPT_COLUMNS = KEY_COLUMNS + """
            , declared_type, organization, request_type, ref, source_version, target_revision, semantic_digest
            """;
    private static final String RECEIPT_VALUES = "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private final DataSource workflow;
    private final JdbcTemplate jdbc;

    public JdbcInboundInbox(DataSource workflow) {
        this.workflow = Objects.requireNonNull(workflow);
        jdbc = new JdbcTemplate(workflow);
    }

    @Override
    public Prior classifyAndLock(PolicyGuard guard, Receipt submitted) {
        requireOrganization(guard, submitted.target());
        return onWorkflow(connection -> {
            lockSourceKey(connection, submitted.key());
            try (var query = connection.prepareStatement("""
                    SELECT CASE
                        WHEN p.source_identity IS NULL THEN NULL
                        WHEN (i.declared_type, i.organization, i.request_type, i.ref, i.source_version,
                              i.target_revision, i.semantic_digest) = ROW(?, ?, ?, ?, ?, ?, ?) THEN 'EXACT'
                        ELSE 'CONFLICT'
                    END
                    FROM requests.inbound_inbox i LEFT JOIN requests.inbound_processing p USING (
                    """ + KEY_COLUMNS + """
                    )
                    WHERE i.source_system = ? AND i.source_tenant = ? AND i.identity_domain = ? AND i.source_identity = ?
                    """)) {
                binding(query, 1, submitted);
                key(query, 8, submitted.key());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Prior.ABSENT;
                    }
                    String disposition = rows.getString(1);
                    if (disposition == null) {
                        throw new AuthorityUnavailableException();
                    }
                    return Prior.valueOf(disposition);
                }
            }
        });
    }

    private static void lockSourceKey(Connection connection, Key key) throws SQLException {
        // Scope coordinates exclude ':' and the binary identity is hex encoded. The hash only serializes contenders.
        try (var lock = connection.prepareStatement("""
                SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ? || ':' || ? || ':' || encode(?, 'hex'), 1919250809))
                """)) {
            key(lock, 1, key);
            lock.execute();
        }
    }

    @Override
    public long lastSourceVersion(PolicyGuard guard, Scope scope, Target target) {
        requireOrganization(guard, target);
        return onWorkflow(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT source_version FROM requests.inbound_inbox
                    WHERE source_system = ? AND source_tenant = ? AND identity_domain = ?
                      AND organization = ? AND request_type = ? AND ref = ?
                    ORDER BY source_version DESC LIMIT 1
                    """)) {
                scope(query, 1, scope);
                target(query, 4, target);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? rows.getLong("source_version") : 0L;
                }
            }
        });
    }

    @Override
    public void accept(PolicyGuard guard, Processing processing) {
        var receipt = processing.receipt();
        requireOrganization(guard, receipt.target());
        onWorkflow(connection -> {
            requireSynchronousCommit(connection);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO requests.inbound_inbox (" + RECEIPT_COLUMNS + ") " + RECEIPT_VALUES)) {
                receipt(insert, receipt);
                requireInsert(insert);
            }
            try (var insert = connection.prepareStatement("INSERT INTO requests.inbound_processing (" + KEY_COLUMNS
                    + ", identifier_type, external_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                key(insert, 1, receipt.key());
                insert.setString(5, processing.identifierType());
                insert.setBytes(6, processing.externalId().bytes());
                requireInsert(insert);
            }
            return null;
        });
    }

    @Override
    public void quarantine(PolicyGuard guard, Receipt discrepancy) {
        requireOrganization(guard, discrepancy.target());
        onWorkflow(connection -> {
            requireSynchronousCommit(connection);
            // The primary key is the only unique constraint. Keep this table insert-only for the runtime role.
            try (var insert = connection.prepareStatement("INSERT INTO requests.inbound_quarantine (" + RECEIPT_COLUMNS
                    + ") " + RECEIPT_VALUES + " ON CONFLICT DO NOTHING")) {
                receipt(insert, discrepancy);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private <T> T onWorkflow(ConnectionCallback<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !(TransactionSynchronizationManager.getResource(workflow) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction()) {
            throw new IllegalStateException("Inbound ledger work requires the owned workflow transaction.");
        }
        try {
            return jdbc.execute((ConnectionCallback<T>) connection -> {
                if (connection.getAutoCommit()) {
                    throw new IllegalStateException("Inbound ledger work requires the owned workflow transaction.");
                }
                return work.doInConnection(connection);
            });
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private static void requireSynchronousCommit(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL synchronous_commit = on");
        }
    }

    private static void requireInsert(PreparedStatement insert) throws SQLException {
        if (insert.executeUpdate() != 1) {
            throw new AuthorityUnavailableException();
        }
    }

    private static void requireOrganization(PolicyGuard guard, Target target) {
        if (!guard.organization().equals(target.organization())) {
            throw new AuthorityUnavailableException();
        }
    }

    private static void scope(PreparedStatement statement, int first, Scope scope) throws SQLException {
        statement.setString(first, scope.sourceSystem());
        statement.setString(first + 1, scope.tenant());
        statement.setString(first + 2, scope.identityDomain());
    }

    private static void key(PreparedStatement statement, int first, Key key) throws SQLException {
        scope(statement, first, key.scope());
        statement.setBytes(first + 3, key.identity().bytes());
    }

    private static void target(PreparedStatement statement, int first, Target target) throws SQLException {
        statement.setString(first, target.organization().value());
        statement.setString(first + 1, target.type().name());
        statement.setString(first + 2, target.ref().value());
    }

    private static void receipt(PreparedStatement statement, Receipt receipt) throws SQLException {
        key(statement, 1, receipt.key());
        binding(statement, 5, receipt);
    }

    private static void binding(PreparedStatement statement, int first, Receipt receipt) throws SQLException {
        statement.setString(first, receipt.type().name());
        target(statement, first + 1, receipt.target());
        statement.setLong(first + 4, receipt.sourceVersion());
        statement.setLong(first + 5, receipt.targetRevision());
        statement.setString(first + 6, receipt.semanticDigest());
    }
}
