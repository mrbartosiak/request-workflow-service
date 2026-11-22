import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.ds.PGPooledConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Leaves room for live worker slots and owner observations within the qualified 16-connection fixture. */
final class B25RoleSessions {
    private B25RoleSessions() { }

    static final class Command extends RoleSource implements AutoCloseable {
        private final Semaphore available = new Semaphore(1, true);
        private final PGPooledConnection pooled;

        Command(DataSource source) throws SQLException {
            pooled = new PGPooledConnection(source.getConnection(), true);
        }

        @Override
        public Connection getConnection() throws SQLException {
            acquire(available);
            try {
                return managed(pooled.getConnection(), available::release);
            } catch (SQLException | RuntimeException failure) {
                available.release();
                throw failure;
            }
        }

        @Override
        public void close() throws SQLException {
            if (!available.tryAcquire()) {
                throw new SQLException("REST command session was still leased at teardown.");
            }
            pooled.close();
        }
    }

    /** The two JVMs take turns opening an actual restricted original-workflow observer connection. */
    static DataSource observer(DataSource role, Path gate) {
        return new RoleSource() {
            private final Semaphore local = new Semaphore(1, true);

            @Override
            public Connection getConnection() throws SQLException {
                acquire(local);
                FileChannel channel = null;
                try {
                    channel = FileChannel.open(gate, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = permit(channel);
                    return managed(role.getConnection(), () -> release(lock, local));
                } catch (IOException | InterruptedException | SQLException | RuntimeException failure) {
                    try {
                        if (channel != null) {
                            channel.close();
                        }
                    } catch (IOException closing) {
                        failure.addSuppressed(closing);
                    } finally {
                        local.release();
                    }
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SQLException("Could not lease the original-workflow observer.", failure);
                }
            }
        };
    }

    private static FileLock permit(FileChannel channel) throws IOException, InterruptedException, SQLException {
        long stop = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
        while (System.nanoTime() < stop) {
            var acquired = channel.tryLock();
            if (acquired != null) {
                return acquired;
            }
            Thread.sleep(5);
        }
        throw new SQLTimeoutException("B25 observer connection admission timed out.");
    }

    private static void release(FileLock lock, Semaphore local) throws SQLException {
        try {
            lock.channel().close();
        } catch (IOException failure) {
            throw new SQLException("Could not release the original-workflow observer slot.", failure);
        } finally {
            local.release();
        }
    }

    private static void acquire(Semaphore slots) throws SQLException {
        try {
            if (!slots.tryAcquire(600, TimeUnit.MILLISECONDS)) {
                throw new SQLTimeoutException("B25 private connection pool is busy.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("B25 private connection wait was interrupted.", interrupted);
        }
    }

    private static Connection managed(Connection delegate, CloseAction release) {
        var closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                delegate.close();
                            } finally {
                                release.close();
                            }
                        }
                        return null;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException wrapper) {
                        throw wrapper.getCause();
                    }
                });
    }

    @FunctionalInterface
    private interface CloseAction {
        void close() throws SQLException;
    }

    private abstract static class RoleSource extends AbstractDataSource {
        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("B25 fixture connection roles are fixed.");
        }
    }
}
