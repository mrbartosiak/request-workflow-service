package com.company.requests.adapter.jdbc;

import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.TransactionRunner;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTransactionRunner implements TransactionRunner {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcTransactionRunner(DataSource commandDataSource) {
        dataSource = Objects.requireNonNull(commandDataSource, "command data source");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T run(Supplier<T> work) {
        Objects.requireNonNull(work, "transaction work");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("An owned command transaction cannot join an ambient transaction or connection.");
        }

        // A callback failure is handled before Spring attempts to commit.
        boolean[] workReturned = {false};
        try {
            return transaction.execute(status -> {
                T result = work.get();
                if (status.isRollbackOnly()) {
                    throw new IllegalStateException("Command transaction was marked rollback-only.");
                }
                // PostgreSQL can turn COMMIT into ROLLBACK after a caught statement error.
                jdbc.queryForObject("SELECT 1", Integer.class);
                workReturned[0] = true;
                return result;
            });
        } catch (RuntimeException failure) {
            if (workReturned[0]) {
                throw new CommitOutcomeUnknownException(failure);
            }
            throw failure;
        }
    }

    static void requireBoundConnection(DataSource dataSource) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Workflow records require a transaction on the command data source.");
        }
    }
}
