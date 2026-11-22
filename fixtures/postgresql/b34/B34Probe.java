package com.company.requests.bootstrap;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Intercepts only the owned command lease. The wrapped pool and transaction manager stay in use. */
public final class B34Probe extends AbstractDataSource {
    public enum Mode { CREATE_ROLLBACK, APPEND_ROLLBACK, COMMIT_BEFORE, COMMIT_AFTER, COMMIT_PAUSE }

    private final B33Runtime.Command delegate;
    private final Path controls;
    private final B32Gate gates;
    private final Set<String> used = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean withholdCreateLookup = new AtomicBoolean();

    public B34Probe(B33Runtime.Command delegate, Path controls) {
        this.delegate = delegate;
        this.controls = controls;
        gates = new B32Gate(controls);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = delegate.getConnection();
        try {
            return new Lease(connection).wrap();
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate.getConnection(username, password);
    }

    private final class Lease {
        private final Connection connection;
        private final int backend;
        private String xid;
        private boolean finalized;
        private boolean appended;

        Lease(Connection connection) throws SQLException {
            this.connection = connection;
            backend = connection.unwrap(PGConnection.class).getBackendPID();
        }

        Connection wrap() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this::call);
        }

        private Object call(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getName().equals("commit") && finalized) {
                return commit(method, arguments);
            }
            Object result = invoke(connection, method, arguments);
            if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql) {
                String statement = sql.stripLeading();
                if (statement.startsWith("UPDATE requests.operation_receipt")) {
                    return watch((PreparedStatement) result, false);
                }
                if (statement.startsWith("INSERT INTO requests.outbox")) {
                    return watch((PreparedStatement) result, true);
                }
                if (statement.startsWith("SELECT intent_digest_version") && withholdCreateLookup.get()) {
                    return watchLookup((PreparedStatement) result);
                }
            }
            return result;
        }

        private PreparedStatement watch(PreparedStatement statement, boolean append) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        Object result = invoke(statement, method, arguments);
                        if (method.getName().equals("executeUpdate") && method.getParameterCount() == 0
                                && Integer.valueOf(1).equals(result)) {
                            String current = B32Database.scalar(connection, "SELECT pg_current_xact_id()::text");
                            if (append && (!finalized || !current.equals(xid))) {
                                throw new SQLException("Append differs from finalized receipt lease.", "XX000");
                            }
                            xid = current;
                            finalized = true;
                            appended = append;
                            String phase = append ? "append" : "finalized";
                            trace(phase);
                            Mode cut = take(append ? Set.of(Mode.APPEND_ROLLBACK) : Set.of(Mode.CREATE_ROLLBACK));
                            if (cut != null) {
                                pause(cut);
                                throw new SQLException("Private B34 callback failure.", "XX000");
                            }
                        }
                        return result;
                    });
        }

        private Object commit(Method method, Object[] arguments) throws Throwable {
            Mode cut = take(Set.of(Mode.COMMIT_BEFORE, Mode.COMMIT_AFTER, Mode.COMMIT_PAUSE));
            if (cut == Mode.COMMIT_BEFORE) {
                connection.rollback();
                trace("native-rollback");
                pause(cut);
                throw unknown();
            }
            Object result = invoke(connection, method, arguments);
            trace("native-commit");
            if (cut != null) {
                pause(cut);
                if (cut == Mode.COMMIT_AFTER) {
                    if (!appended) {
                        withholdCreateLookup.set(true);
                    }
                    throw unknown();
                }
            }
            return result;
        }

        private PreparedStatement watchLookup(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("executeQuery") && method.getParameterCount() == 0
                                && withholdCreateLookup.compareAndSet(true, false)) {
                            System.out.printf("B34_JDBC withheld-create-lookup %d 0 %s%n", backend, Instant.now());
                            throw unknown();
                        }
                        return invoke(statement, method, arguments);
                    });
        }

        private void pause(Mode cut) throws SQLException {
            trace(cut.name());
            gates.hit(B32Gate.Point.BEFORE_COMMIT, backend);
        }

        private void trace(String event) {
            System.out.printf("B34_JDBC %s %d %s %s%n", event, backend, xid, Instant.now());
        }
    }

    private Mode take(Set<Mode> matching) throws SQLException {
        try {
            Path active = controls.resolve("mode");
            if (!Files.exists(active)) {
                return null;
            }
            String[] fields = Files.readString(active).split("\t");
            Mode mode = Mode.valueOf(fields[1]);
            return matching.contains(mode) && used.add(fields[0]) ? mode : null;
        } catch (IOException | RuntimeException failure) {
            throw new SQLException("Private B34 control failed.", "XX000", failure);
        }
    }

    private static SQLException unknown() {
        return new SQLException("Private B34 commit acknowledgement unavailable.", "08006");
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
