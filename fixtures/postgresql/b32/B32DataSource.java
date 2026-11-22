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
import java.util.Properties;
import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Fixture-only JDBC decorator. Spring still uses its normal command and proof transaction paths. */
final class B32DataSource extends AbstractDataSource {
    private final DriverManagerDataSource source = new DriverManagerDataSource();
    private final Store store;
    private final B32Gate gates;

    B32DataSource(Store store, String application, String password, B32Gate gates) {
        this.store = store;
        this.gates = gates;
        var properties = new Properties();
        properties.setProperty("ApplicationName", application);
        properties.setProperty("currentSchema", store.schema);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("gssEncMode", "disable");
        properties.setProperty("requireAuth", "scram-sha-256");
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "6");
        properties.setProperty("options",
                "-c statement_timeout=4000 -c lock_timeout=2500 -c idle_in_transaction_session_timeout=12000");
        source.setConnectionProperties(properties);
        source.setUrl("jdbc:postgresql://127.0.0.1:" + store.port + "/" + store.database);
        source.setUsername(store.user);
        source.setPassword(password);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = source.getConnection();
        try {
            return new CommandConnection(raw).watch();
        } catch (SQLException | RuntimeException failure) {
            try {
                raw.close();
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

    private final class CommandConnection {
        private final Connection raw;
        private final int backend;
        private boolean finalized;

        private CommandConnection(Connection raw) throws SQLException {
            this.raw = raw;
            backend = raw.unwrap(PGConnection.class).getBackendPID();
        }

        private Connection watch() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this::invokeConnection);
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws Throwable {
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
                                gates.hit(after, backend);
                            }
                            finalized |= marksFinalized;
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
