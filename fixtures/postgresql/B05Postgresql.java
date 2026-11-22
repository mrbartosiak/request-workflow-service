import com.company.requests.adapter.jdbc.B05PostgresqlChecks;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Launches the adapter test checks against a disposable, workflow-only fixture. */
public final class B05Postgresql {
    public static void main(String[] args) {
        try {
            if (args.length != 5 || !(args[4].equals("empty") || args[4].equals("prior"))) {
                throw new IllegalArgumentException("Usage: run|cleanup BIN RUNS_DIRECTORY b05-RUN_ID empty|prior");
            }
            QualifyPostgresql.workflowOnly(java.util.Arrays.copyOf(args, 4), (migration, command, observer) ->
                    B05PostgresqlChecks.verify(dataSource(migration), dataSource(command), dataSource(observer),
                            args[4].equals("prior")));
        } catch (Exception | AssertionError failure) {
            report(failure);
            System.exit(1);
        }
    }

    private static DataSource dataSource(QualifyPostgresql.SqlConnection factory) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return factory.open();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new java.sql.SQLFeatureNotSupportedException("Fixture role is preconfigured.");
            }
        };
    }

    private static void report(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String detail = switch (cause) {
                case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                case AssertionError assertion -> " " + assertion.getMessage();
                default -> "";
            };
            System.err.println("B05 failed: " + cause.getClass().getName() + detail);
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().contains("B05") || frame.getClassName().contains("QualifyPostgresql")) {
                    System.err.println("  at " + frame);
                }
            }
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            report(suppressed);
        }
    }
}
