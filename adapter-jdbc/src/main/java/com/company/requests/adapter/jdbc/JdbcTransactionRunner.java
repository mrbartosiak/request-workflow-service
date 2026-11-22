package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.TransactionBusyException;
import com.company.requests.application.TransactionResourceUnavailableException;
import com.company.requests.application.TransactionRunner;
import com.company.requests.domain.OrganizationId;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTransactionRunner implements TransactionRunner {
    private static final ThreadLocal<OwnedConnection> ACTIVE = new ThreadLocal<>();

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean bounded;
    private final boolean worker;

    public JdbcTransactionRunner(DataSource commandDataSource) {
        this(commandDataSource, false, false);
    }

    public static JdbcTransactionRunner forCreate(DataSource commandDataSource) {
        return new JdbcTransactionRunner(commandDataSource, true, false);
    }

    /** Dedicated manager and guard context for dispatch. Workflow write gates remain command-only. */
    public static JdbcTransactionRunner forWorker(DataSource workerDataSource) {
        return new JdbcTransactionRunner(workerDataSource, true, true);
    }

    private JdbcTransactionRunner(DataSource source, boolean bounded, boolean worker) {
        dataSource = Objects.requireNonNull(source, "workflow data source");
        this.bounded = bounded;
        this.worker = worker;
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public <T> T run(Supplier<T> work) {
        Objects.requireNonNull(work, "transaction work");
        if (ACTIVE.get() != null || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("An owned workflow transaction cannot join an ambient transaction or connection.");
        }

        // A callback failure is handled before Spring attempts to commit.
        boolean[] workReturned = {false};
        try {
            return transaction.execute(status -> {
                Object resource = TransactionSynchronizationManager.getResource(dataSource);
                if (!status.isNewTransaction() || !(resource instanceof ConnectionHolder holder)) {
                    throw new IllegalStateException("The workflow transaction must own a new connection binding.");
                }
                T result;
                ACTIVE.set(new OwnedConnection(dataSource, holder, worker));
                try {
                    if (worker) {
                        jdbc.execute("SET LOCAL synchronous_commit = on");
                    }
                    if (bounded) {
                        jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                        jdbc.execute("SET LOCAL statement_timeout = '400ms'");
                    }
                    result = work.get();
                } finally {
                    ACTIVE.remove();
                }
                if (status.isRollbackOnly()) {
                    throw new IllegalStateException("Workflow transaction was marked rollback-only.");
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
            if (bounded && isWaitFailure(failure)) {
                throw new TransactionBusyException(failure);
            }
            if (bounded && failure instanceof CannotCreateTransactionException) {
                throw new AuthorityUnavailableException();
            }
            throw failure;
        }
    }

    @Override
    public <T> T runGuardedRelease(Supplier<T> work) {
        try {
            return run(work);
        } catch (RuntimeException failure) {
            if (bounded && isResourceFailure(failure)) {
                throw new TransactionResourceUnavailableException(failure);
            }
            throw failure;
        }
    }

    private static boolean isResourceFailure(RuntimeException failure) {
        if (failure instanceof DataAccessResourceFailureException) {
            return true;
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionSystemException) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().startsWith("08")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWaitFailure(RuntimeException failure) {
        if (failure instanceof PessimisticLockingFailureException || failure instanceof QueryTimeoutException) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "57014".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    static void requireBoundConnection(DataSource dataSource) {
        requireCommand(dataSource);
    }

    static void requireWorkflowReadConnection(DataSource dataSource) {
        requireOwned(dataSource);
    }

    static void requireWorkerConnection(DataSource dataSource) {
        if (!requireOwned(dataSource).worker) {
            throw new IllegalStateException("Dispatch locks require a worker transaction.");
        }
    }

    static void rememberSharedGuard(DataSource dataSource, PolicyGuard guard) {
        requireOwned(dataSource).guards.put(guard.organization(), guard);
    }

    static void requireGuard(DataSource dataSource, PolicyGuard guard) {
        if (!guard.equals(requireOwned(dataSource).guards.get(guard.organization()))) {
            throw new IllegalStateException("Effective authority requires this transaction's organization guard.");
        }
    }

    static void rememberExclusiveGuard(DataSource dataSource, PolicyGuard guard) {
        var command = requireCommand(dataSource);
        command.guards.put(guard.organization(), guard);
        command.exclusiveGuards.put(guard.organization(), guard);
    }

    static void requireExclusiveGuard(DataSource dataSource, PolicyGuard guard) {
        if (!guard.equals(requireCommand(dataSource).exclusiveGuards.get(guard.organization()))) {
            throw new IllegalStateException("Scope witnesses require this transaction's exclusive organization guard.");
        }
    }

    static void rememberScopeAttempt(DataSource dataSource, Attempt attempt) {
        var command = requireCommand(dataSource);
        if (command.scopeAttempt != null) {
            throw new IllegalStateException("A command transaction may begin only one scope attempt.");
        }
        command.scopeAttempt = attempt;
    }

    static void requireScopeAttempt(DataSource dataSource, Attempt attempt) {
        var command = requireCommand(dataSource);
        if (!attempt.equals(command.scopeAttempt)
                || !command.exclusiveGuards.containsKey(attempt.admission().genesis().organization())) {
            throw new IllegalStateException("Scope witnesses require this transaction's exact scope attempt.");
        }
    }

    private static OwnedConnection requireCommand(DataSource dataSource) {
        var connection = requireOwned(dataSource);
        if (connection.worker) {
            throw new IllegalStateException("Workflow writes require a command transaction.");
        }
        return connection;
    }

    private static OwnedConnection requireOwned(DataSource dataSource) {
        var connection = ACTIVE.get();
        // Spring may also synchronize a connection opened on another data source during the callback.
        if (connection == null || connection.dataSource != dataSource
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.getResource(dataSource) != connection.holder) {
            throw new IllegalStateException("Workflow records require the owning transaction's data source.");
        }
        return connection;
    }

    private static final class OwnedConnection {
        private final DataSource dataSource;
        private final ConnectionHolder holder;
        private final boolean worker;
        private final Map<OrganizationId, PolicyGuard> guards = new HashMap<>();
        private final Map<OrganizationId, PolicyGuard> exclusiveGuards = new HashMap<>();
        private Attempt scopeAttempt;

        private OwnedConnection(DataSource dataSource, ConnectionHolder holder, boolean worker) {
            this.dataSource = dataSource;
            this.holder = holder;
            this.worker = worker;
        }

    }
}
