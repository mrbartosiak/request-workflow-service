package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Independent proof work never takes part in the bound workflow transaction. */
final class MappingProofTransactions {
    private final DataSource dataSource;

    MappingProofTransactions(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    <T> T run(boolean readOnly, Work<T> work) {
        try (var connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new AuthorityUnavailableException();
            }
            connection.setReadOnly(readOnly);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '150ms'");
                    statement.execute("SET LOCAL lock_timeout = '150ms'");
                    if (!readOnly) {
                        statement.execute("SET LOCAL synchronous_commit = on");
                    }
                }
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }
}
