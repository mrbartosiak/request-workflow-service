package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Uses only explicitly opened proof connections, including while an owned workflow callback is active. */
final class ProofTransactions {
    private final DataSource proofDataSource;

    ProofTransactions(DataSource proofDataSource) {
        this.proofDataSource = Objects.requireNonNull(proofDataSource, "independent proof data source");
    }

    <T> T run(boolean readOnly, Work<T> work) {
        try (Connection proof = proofDataSource.getConnection()) {
            if (!proof.getAutoCommit()) {
                throw new AuthorityUnavailableException();
            }
            proof.setReadOnly(readOnly);
            proof.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            proof.setAutoCommit(false);
            try {
                try (var statement = proof.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '150ms'");
                    statement.execute("SET LOCAL lock_timeout = '150ms'");
                    if (!readOnly) {
                        statement.execute("SET LOCAL synchronous_commit = on");
                    }
                }
                T result = work.apply(proof);
                proof.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                proof.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            // Driver errors can include protected SQL values. Keep them off public exception paths.
            throw new AuthorityUnavailableException();
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }
}
