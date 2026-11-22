import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Native connection and transaction evidence plus isolated test cut controls. */
final class B23WorkerTrace {
    enum Step { POLL, CLAIM, GUARD, ARM, FINISH }

    record Mark(Step step, String user, int pid, String xid) { }

    private final List<Mark> marks = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> beforeClaimInsert = new AtomicReference<>();
    private final AtomicBoolean loseArmCommitReply = new AtomicBoolean();

    void beforeNextClaimInsert(Runnable callback) {
        beforeClaimInsert.set(callback);
    }

    void loseNextArmCommitReply() {
        loseArmCommitReply.set(true);
    }

    Mark last(Step step) {
        return marks.reversed().stream().filter(mark -> mark.step() == step).findFirst().orElseThrow();
    }

    Connection observe(Connection connection) {
        var armPrepared = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        var step = classify(sql);
                        if (step != null) {
                            marks.add(inspect(connection, step));
                            if (step == Step.CLAIM) {
                                var callback = beforeClaimInsert.getAndSet(null);
                                if (callback != null) {
                                    callback.run();
                                }
                            } else if (step == Step.ARM) {
                                armPrepared.set(true);
                            }
                        }
                    }
                    try {
                        Object result = method.invoke(connection, args);
                        if (method.getName().equals("commit") && armPrepared.getAndSet(false)
                                && loseArmCommitReply.compareAndSet(true, false)) {
                            throw new SQLException("B23 committed arm reply withheld.", "08006");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Mark inspect(Connection connection, Step step) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new AssertionError("Dispatch SQL used an unmanaged database connection.");
        }
        try (var query = connection.createStatement(); var row = query.executeQuery("""
                SELECT session_user, pg_backend_pid(), mod(pg_current_xact_id()::text::numeric, 4294967296)::text
                """)) {
            row.next();
            return new Mark(step, row.getString(1), row.getInt(2), row.getString(3));
        }
    }

    private static Step classify(String sql) {
        if (sql.contains("WHERE o.status = 'READY'")) {
            return Step.POLL;
        }
        if (sql.contains("INSERT INTO requests.outbox_attempt")) {
            return Step.CLAIM;
        }
        if (sql.contains("requests.lock_dispatch_guard")) {
            return Step.GUARD;
        }
        if (sql.contains("UPDATE requests.outbox_attempt SET state = 'ARMED'")) {
            return Step.ARM;
        }
        if (sql.contains("UPDATE requests.outbox_attempt SET state = ?")) {
            return Step.FINISH;
        }
        return null;
    }
}
