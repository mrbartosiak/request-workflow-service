import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Intercepts only the private fixture's next workflow COMMIT. */
final class B26CommitCut {
    enum Point { PAUSE_BEFORE_COMMIT, LOSE_COMMIT_REPLY }

    record Observation(int backend, String transaction, boolean synchronous, long receipts, long processing) { }

    static final class Barrier {
        final Point point;
        final CountDownLatch staged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Observation observation;
        volatile String commitSqlState;

        Barrier(Point point) {
            this.point = point;
        }

        Observation await() throws InterruptedException {
            if (!staged.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Workflow did not reach the armed COMMIT cut.");
            }
            return observation;
        }
    }

    private final AtomicReference<Barrier> next = new AtomicReference<>();

    Barrier arm(Point point) {
        var barrier = new Barrier(point);
        if (!next.compareAndSet(null, barrier)) {
            throw new IllegalStateException("An earlier B26 fixture cut is still armed.");
        }
        return barrier;
    }

    Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (ignored, method, arguments) -> {
                    if (method.getName().equals("commit")) {
                        var barrier = next.getAndSet(null);
                        if (barrier != null) {
                            commit(delegate, barrier);
                            return null;
                        }
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static void commit(Connection connection, Barrier barrier) throws SQLException, InterruptedException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT pg_backend_pid(), pg_current_xact_id()::text, current_setting('synchronous_commit') = 'on',
                       (SELECT count(source_system) FROM requests.inbound_inbox),
                       (SELECT count(source_system) FROM requests.inbound_processing)
                """)) {
            rows.next();
            barrier.observation = new Observation(rows.getInt(1), rows.getString(2), rows.getBoolean(3),
                    rows.getLong(4), rows.getLong(5));
        }
        barrier.staged.countDown();
        if (barrier.point == Point.PAUSE_BEFORE_COMMIT) {
            if (!barrier.release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("The B26 commit barrier did not release.");
            }
            try {
                connection.commit();
            } catch (SQLException failure) {
                barrier.commitSqlState = failure.getSQLState();
                throw failure;
            }
        } else {
            connection.commit();
            throw new SQLException("Synthetic loss of the successful COMMIT reply.", "08006");
        }
    }
}
