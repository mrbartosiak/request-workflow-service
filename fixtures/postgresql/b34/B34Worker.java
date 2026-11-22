import com.company.requests.bootstrap.B32Database;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B33Runtime;
import com.company.requests.bootstrap.B34Probe;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.PublishOutboxTrigger;
import com.company.requests.domain.RequestType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import javax.sql.DataSource;

public final class B34Worker {
    static final RequestType TYPE = RequestType.HR_TICKET;
    static final String DESTINATION = "fixture-durability";

    private B34Worker() { }

    public static void main(String[] arguments) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            B33Evidence.require(arguments.length == 2 && arguments[1].matches("b34-[a-z0-9-]{1,16}"),
                    "Invalid B34 worker arguments.");
            var child = B33Profile.Child.read(input);
            var root = Path.of(arguments[0]).toRealPath();
            var control = root.resolve("control").toRealPath();
            var app = arguments[1];
            try (var command = new B33Runtime.Command(app, child.passwords().get(B33Profile.Role.COMMAND), control);
                 var sources = new B10FixtureSources()) {
                var probe = new B34Probe(command, control.resolve("durability"));
                var proof = pool(child, app, B33Profile.Role.CREATE, sources);
                var journal = pool(child, app, B33Profile.Role.JOURNAL, sources);
                var original = pool(child, app, B33Profile.Role.ORIGINAL, sources);
                verifyPool(command, Store.WORKFLOW, control);
                verifyPool(proof, Store.PROOF, control);
                var settings = settings(child, app, probe, proof, journal, original, () -> clock(control));
                B33Runtime.serve(Files.createDirectory(root.resolve("http")), settings, input);
            }
        } catch (Exception | AssertionError failure) {
            B33Evidence.failure(failure);
            System.exit(1);
        }
    }

    static SyntheticEffectiveAccessConfiguration.Settings settings(B33Profile.Child child, String app,
            DataSource command, DataSource proof, DataSource journal, DataSource original, Supplier<Instant> clock) {
        var inherited = child.settings(app, command, proof, journal, original, clock);
        var create = inherited.create();
        var scoped = new SyntheticCreateConfiguration.Settings(command, proof,
                create.policy().withAssigneeOnlyOnPublish(Set.of(TYPE)), create.writer(), create.actors(),
                create.clock(), create.secrets(), create.guardedReplay());
        return new SyntheticEffectiveAccessConfiguration.Settings(scoped, journal, original,
                inherited.projection(), inherited.resolver(), inherited.operators(),
                inherited.observationKeys(), true, Optional.of(new PublishOutboxTrigger(TYPE, DESTINATION)));
    }

    private static DataSource pool(B33Profile.Child child, String app, B33Profile.Role role,
                                   B10FixtureSources sources) throws Exception {
        return sources.source(child.source(role, app)::getConnection);
    }

    private static Instant clock(Path control) {
        try {
            return Instant.parse(Files.readString(control.resolve("trusted-clock")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Missing B34 trusted clock.");
        }
    }

    private static void verifyPool(DataSource pool, Store store, Path control) throws Exception {
        try (var first = pool.getConnection(); var second = pool.getConnection()) {
            var identity = B32Database.read(first, store);
            var peer = B32Database.read(second, store);
            B33Evidence.require(identity.pid() != peer.pid() && identity.user().equals(peer.user()),
                    "Expected separate qualified B34 backends.");
            try (var unexpected = pool.getConnection()) {
                throw new AssertionError("Fixture exceeded two pool slots.");
            } catch (SQLTimeoutException expected) {
                System.out.printf("B34_POOL %s bounded two-slot limit at %s%n", store, Instant.now());
            }
            String prefix = store.name().toLowerCase(Locale.ROOT);
            identity.save(control.resolve(prefix + ".identity"));
            Files.writeString(control.resolve(prefix + ".pool"), identity.pid() + " " + peer.pid());
        }
    }
}
