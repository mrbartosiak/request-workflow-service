import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.PooledConnection;
import org.postgresql.ds.PGConnectionPoolDataSource;
import org.postgresql.ds.PGPooledConnection;

/** Opens role-restricted backends through the qualified fixture and records real database sessions. */
final class B25PoolSource extends PGConnectionPoolDataSource {
    private final QualifyPostgresql.SqlConnection login;
    private final String name;
    private final Set<Integer> opened = ConcurrentHashMap.newKeySet();

    B25PoolSource(QualifyPostgresql.SqlConnection login, String name) {
        this.login = login;
        this.name = name;
    }

    @Override
    public PooledConnection getPooledConnection() throws SQLException {
        var connection = login.open();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            connection.setClientInfo("ApplicationName", name);
            if (!rows.next()) {
                throw new SQLException("Worker fixture did not obtain the backend identity.");
            }
            opened.add(rows.getInt(1));
            return new PGPooledConnection(connection, true);
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    int opened() {
        return opened.size();
    }
}
