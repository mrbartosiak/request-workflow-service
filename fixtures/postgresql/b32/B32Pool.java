package com.company.requests.bootstrap;

import com.company.requests.bootstrap.B32Database.Store;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.PooledConnection;
import org.postgresql.ds.PGConnectionPoolDataSource;

/** Two physical slots per fixture store, with pgJDBC logical leases and a bounded slot wait. */
final class B32Pool implements AutoCloseable {
    static final int WAIT_MILLIS = 200;
    private final PGConnectionPoolDataSource source = new PGConnectionPoolDataSource();
    private final List<Slot> slots = List.of(new Slot(), new Slot());
    private final ArrayBlockingQueue<Slot> idle = new ArrayBlockingQueue<>(slots.size(), false, slots);
    private volatile boolean closed;

    B32Pool(Store store, String application, String password) {
        source.setServerNames(new String[]{"127.0.0.1"});
        source.setPortNumbers(new int[]{store.port});
        source.setDatabaseName(store.database);
        source.setUser(store.user);
        source.setPassword(password);
        source.setApplicationName(application);
        source.setCurrentSchema(store.schema);
        source.setSslMode("disable");
        source.setGssEncMode("disable");
        source.setRequireAuth("scram-sha-256");
        source.setConnectTimeout(3);
        source.setLoginTimeout(5);
        source.setSocketTimeout(6);
        source.setOptions("-c statement_timeout=4000 -c lock_timeout=2500 -c idle_in_transaction_session_timeout=12000");
    }

    Lease acquire() throws SQLException {
        if (closed) {
            throw new SQLException("B32 pool is closed.");
        }
        Slot slot;
        try {
            slot = idle.poll(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a B32 pool lease.", failure);
        }
        if (slot == null) {
            throw new SQLTimeoutException("B32 pool lease wait expired.", "HYT00");
        }
        try {
            return new Lease(slot, slot.open());
        } catch (SQLException | RuntimeException failure) {
            try {
                slot.discard();
            } catch (SQLException closing) {
                failure.addSuppressed(closing);
            } finally {
                idle.add(slot);
            }
            throw failure;
        }
    }

    final class Lease implements AutoCloseable {
        private final Slot slot;
        final Connection connection;
        private boolean closed;

        Lease(Slot slot, Connection connection) {
            this.slot = slot;
            this.connection = connection;
        }

        @Override
        public void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try (Connection logical = connection) {
                if (logical.isClosed()) {
                    slot.broken = true;
                } else if (!slot.broken) {
                    if (!logical.getAutoCommit()) {
                        logical.rollback();
                        logical.setAutoCommit(true);
                    }
                    if (logical.isReadOnly()) {
                        logical.setReadOnly(false);
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                slot.broken = true;
                try {
                    slot.discard();
                } catch (SQLException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            } finally {
                idle.add(slot);
            }
        }
    }

    private final class Slot implements ConnectionEventListener {
        private PooledConnection physical;
        private volatile boolean broken;

        synchronized Connection open() throws SQLException {
            if (closed) {
                throw new SQLException("B32 pool is closed.");
            }
            if (broken) {
                discard();
            }
            if (physical == null) {
                physical = source.getPooledConnection();
                physical.addConnectionEventListener(this);
            }
            return physical.getConnection();
        }

        synchronized void discard() throws SQLException {
            PooledConnection old = physical;
            physical = null;
            broken = false;
            if (old != null) {
                old.close();
            }
        }

        @Override
        public void connectionClosed(ConnectionEvent event) { }

        @Override
        public void connectionErrorOccurred(ConnectionEvent event) {
            broken = true;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        SQLException problem = idle.size() == slots.size() ? null : new SQLException("B32 pool closed with an active lease.");
        for (Slot slot : slots) {
            try {
                slot.discard();
            } catch (SQLException failure) {
                if (problem == null) {
                    problem = failure;
                } else {
                    problem.addSuppressed(failure);
                }
            }
        }
        if (problem != null) {
            throw problem;
        }
    }
}
