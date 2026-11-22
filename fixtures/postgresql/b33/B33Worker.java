import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B33Runtime;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** One service JVM with the unchanged B14 Spring controllers and independently owned pools. */
public final class B33Worker {
    private B33Worker() { }

    public static void main(String[] args) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            B33Evidence.require(args.length == 2 && args[1].matches("b33-[ab]"), "Invalid B33 worker arguments.");
            var child = B33Profile.Child.read(input);
            var root = Path.of(args[0]).toRealPath();
            var control = root.resolve("control").toRealPath();
            var app = args[1];
            var createProof = child.source(B33Profile.Role.CREATE, app);
            var journal = child.source(B33Profile.Role.JOURNAL, app);
            var original = child.source(B33Profile.Role.ORIGINAL, app);
            try (var command = new B33Runtime.Command(app, child.passwords().get(B33Profile.Role.COMMAND), control);
                 var sources = new B10FixtureSources()) {
                var proof = sources.source(createProof::getConnection);
                var scopeJournal = sources.source(journal::getConnection);
                var originalObserver = sources.source(original::getConnection);
                verifyPool(command, Store.WORKFLOW, control);
                verifyPool(proof, Store.PROOF, control);
                var settings = child.settings(app, command, proof, scopeJournal, originalObserver, () -> clock(control));
                B33Runtime.serve(Files.createDirectory(root.resolve("http")), settings, input);
            }
        } catch (Exception | AssertionError failure) {
            B33Evidence.failure(failure);
            System.exit(1);
        }
    }

    private static Instant clock(Path control) {
        try {
            return Instant.parse(Files.readString(control.resolve("trusted-clock")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Missing B33 trusted clock.");
        }
    }

    private static void verifyPool(DataSource pool, Store store, Path control) throws Exception {
        try (var first = pool.getConnection(); var second = pool.getConnection()) {
            var identity = B32Database.read(first, store);
            var peer = B32Database.read(second, store);
            B33Evidence.require(identity.pid() != peer.pid() && identity.user().equals(peer.user()),
                    "Concurrent leases must use separate qualified backends.");
            try (var unexpected = pool.getConnection()) {
                throw new AssertionError("Fixture exceeded two pool slots.");
            } catch (SQLTimeoutException expected) {
                System.out.printf("B33_POOL %s two-slot limit at %s%n", store, Instant.now());
            }
            first.setReadOnly(true);
            first.setAutoCommit(false);
            B32Database.scalar(first, "SELECT 1");
            first.close();
            try (var reused = pool.getConnection()) {
                B33Evidence.require(B32Database.read(reused, store).equals(identity)
                        && reused.getAutoCommit() && !reused.isReadOnly(), "Pool did not reuse a reset physical backend.");
            }
            String prefix = store.name().toLowerCase(java.util.Locale.ROOT);
            identity.save(control.resolve(prefix + ".identity"));
            Files.writeString(control.resolve(prefix + ".pool"), identity.pid() + " " + peer.pid());
        }
    }
}
