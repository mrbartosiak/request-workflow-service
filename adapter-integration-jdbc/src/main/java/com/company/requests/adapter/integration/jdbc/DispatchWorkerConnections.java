package com.company.requests.adapter.integration.jdbc;

import com.company.requests.integration.DispatchScheduler;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Four dedicated physical sessions, with one session reused across a worker's separate transactions. */
public final class DispatchWorkerConnections extends AbstractDataSource implements AutoCloseable {
    private final ConnectionPoolDataSource source;
    private final List<Slot> slots = IntStream.range(0, DispatchScheduler.WORKERS).mapToObj(index -> new Slot()).toList();
    private final ArrayBlockingQueue<Slot> idle = new ArrayBlockingQueue<>(slots.size(), true, slots);
    private final ThreadLocal<Session> active = new ThreadLocal<>();
    private volatile boolean closed;

    public DispatchWorkerConnections(ConnectionPoolDataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public <T> T inSession(Supplier<T> work) {
        if (closed || active.get() != null) {
            throw new IllegalStateException("Dispatch requires an open, unbound worker session.");
        }
        Slot slot;
        try {
            slot = idle.poll(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a worker connection.");
        }
        if (slot == null) {
            throw new IllegalStateException("Dispatch worker connection limit reached.");
        }
        try (var connection = slot.open()) {
            var shared = new SingleConnectionDataSource(connection, true).getConnection();
            active.set(new Session(shared, slot));
            return work.get();
        } catch (SQLException failure) {
            slot.broken = true;
            throw new IllegalStateException("Dispatch worker connection failed.", failure);
        } finally {
            active.remove();
            try {
                if (slot.broken) {
                    slot.discard();
                }
            } finally {
                idle.add(slot);
            }
        }
    }

    void discardCurrentSession() {
        active.get().slot().broken = true;
    }

    @Override
    public Connection getConnection() throws SQLException {
        var session = active.get();
        if (session == null) {
            throw new SQLException("Dispatch connections are available only within the owning worker session.");
        }
        return session.connection();
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLException("Dispatch worker credentials are fixed at provisioning.");
    }

    @Override
    public synchronized void close() {
        if (idle.size() != slots.size()) {
            throw new IllegalStateException("Dispatch connections are still in use.");
        }
        closed = true;
        for (var slot : slots) {
            slot.discard();
        }
    }

    private final class Slot implements ConnectionEventListener {
        private PooledConnection physical;
        private volatile boolean broken;

        synchronized Connection open() throws SQLException {
            if (closed) {
                throw new SQLException("Dispatch worker connection pool is closed.");
            }
            if (physical == null) {
                physical = source.getPooledConnection();
                physical.addConnectionEventListener(this);
            }
            return physical.getConnection();
        }

        synchronized void discard() {
            var prior = physical;
            physical = null;
            broken = false;
            if (prior != null) {
                try {
                    prior.close();
                } catch (SQLException failure) {
                    throw new IllegalStateException("Failed to discard a dispatch worker connection.", failure);
                }
            }
        }

        @Override
        public void connectionClosed(ConnectionEvent event) { }

        @Override
        public void connectionErrorOccurred(ConnectionEvent event) {
            broken = true;
        }
    }

    private record Session(Connection connection, Slot slot) { }
}
