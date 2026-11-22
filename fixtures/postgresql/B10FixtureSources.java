import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.postgresql.ds.PGPooledConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Two prewarmed physical PgJDBC connections per role for the bounded B10 lock-order oracle. */
final class B10FixtureSources implements AutoCloseable {
    private final List<Source> sources = new ArrayList<>();

    Source source(QualifyPostgresql.SqlConnection opener) throws SQLException {
        return source(opener, UnaryOperator.identity());
    }

    Source source(QualifyPostgresql.SqlConnection opener, UnaryOperator<Connection> inject) throws SQLException {
        var source = new Source(opener, inject);
        sources.add(source);
        return source;
    }

    @Override
    public void close() throws SQLException {
        SQLException problem = null;
        for (var source : sources) {
            try {
                source.close();
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

    static final class Source extends AbstractDataSource {
        private final List<PGPooledConnection> physical = new ArrayList<>();
        private final ArrayBlockingQueue<PGPooledConnection> available = new ArrayBlockingQueue<>(2);
        private final UnaryOperator<Connection> inject;

        private Source(QualifyPostgresql.SqlConnection opener, UnaryOperator<Connection> inject) throws SQLException {
            this.inject = inject;
            try {
                for (int index = 0; index < 2; index++) {
                    var connection = new PGPooledConnection(opener.open(), true);
                    physical.add(connection);
                    available.add(connection);
                }
            } catch (SQLException failure) {
                try {
                    close();
                } catch (SQLException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            PGPooledConnection slot;
            try {
                slot = available.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new SQLException("B10 connection lease interrupted.", failure);
            }
            if (slot == null) {
                throw new SQLTimeoutException("B10 role connection lease expired.");
            }
            Connection logical;
            try {
                logical = slot.getConnection();
            } catch (SQLException failure) {
                available.add(slot);
                throw failure;
            }
            var target = inject.apply(logical);
            var closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            if (closed.compareAndSet(false, true)) {
                                release(slot, logical);
                            }
                            return null;
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private void release(PGPooledConnection slot, Connection logical) throws SQLException {
            try (logical) {
                if (!logical.getAutoCommit()) {
                    logical.rollback();
                    logical.setAutoCommit(true);
                }
                if (logical.isReadOnly()) {
                    logical.setReadOnly(false);
                }
            } finally {
                available.add(slot);
            }
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        private void close() throws SQLException {
            SQLException problem = available.size() != physical.size()
                    ? new SQLException("B10 fixture closed with an active role connection lease.") : null;
            for (var slot : physical) {
                try {
                    slot.close();
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
}
