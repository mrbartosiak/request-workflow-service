package com.company.requests.bootstrap;

import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Fixture-only JDBC decorator. Spring still uses its normal command and proof transaction paths. */
final class B32DataSource extends AbstractDataSource implements AutoCloseable {
    private final B32Pool pool;
    private final Store store;
    private final B32Gate gates;

    B32DataSource(Store store, String application, String password, B32Gate gates) {
        this.store = store;
        this.gates = gates;
        pool = new B32Pool(store, application, password);
    }

    @Override
    public Connection getConnection() throws SQLException {
        B32Pool.Lease lease = pool.acquire();
        try {
            return new CommandConnection(lease).watch();
        } catch (SQLException | RuntimeException failure) {
            try {
                lease.close();
            } catch (SQLException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("The fixture uses its provisioned role.");
    }

    @Override
    public void close() throws SQLException {
        pool.close();
    }

    private final class CommandConnection {
        private final B32Pool.Lease lease;
        private final Connection raw;
        private final int backend;
        private boolean claimed;
        private boolean finalized;

        private CommandConnection(B32Pool.Lease lease) throws SQLException {
            this.lease = lease;
            raw = lease.connection;
            backend = raw.unwrap(PGConnection.class).getBackendPID();
        }

        private Connection watch() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this::invokeConnection);
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("close")) {
                lease.close();
                return null;
            }
            if (method.getName().equals("commit") && finalized) {
                gates.hit(Point.BEFORE_COMMIT, backend);
            }
            Object result = invoke(raw, method, args);
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                var statement = (PreparedStatement) result;
                if (store == Store.PROOF && sql.contains("INSERT INTO request_authority.release_prepared")) {
                    return watchUpdate(statement, Point.BEFORE_RELEASE, null, false);
                }
                if (store == Store.WORKFLOW && sql.contains("INSERT INTO requests.operation_receipt")
                        && sql.contains("DO NOTHING")) {
                    return watchUpdate(statement, Point.BEFORE_CLAIM, Point.AFTER_CLAIM, false);
                }
                if (store == Store.WORKFLOW && sql.contains("UPDATE requests.operation_receipt")) {
                    return watchUpdate(statement, null, null, true);
                }
            }
            return result;
        }

        private PreparedStatement watchUpdate(PreparedStatement statement, Point before, Point after,
                                               boolean marksFinalized) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("executeUpdate") || method.getParameterCount() != 0) {
                            return invoke(statement, method, args);
                        }
                        if (before != null) {
                            gates.hit(before, backend);
                        }
                        Object changed = invoke(statement, method, args);
                        if (Integer.valueOf(1).equals(changed)) {
                            if (after != null) {
                                claimed = true;
                                gates.hit(after, backend);
                            }
                            if (marksFinalized) {
                                if (!claimed) {
                                    throw new SQLException("B32 finalization did not use the claim's JDBC lease.", "XX000");
                                }
                                finalized = true;
                            }
                        }
                        return changed;
                    });
        }
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
