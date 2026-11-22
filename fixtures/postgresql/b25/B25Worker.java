import com.company.requests.bootstrap.B25Bootstrap;
import com.company.requests.bootstrap.B32Gate;
import com.company.requests.bootstrap.B33Runtime;
import com.company.requests.bootstrap.B42FixtureEvidence;
import com.company.requests.domain.OrganizationId;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.OutboundEvidenceRecorder.Destination;
import com.company.requests.integration.SyntheticDelivery.Action;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** One scheduled delivery worker and effective access REST service in an owned process. */
public final class B25Worker {
    private static final Map<String, Destination> B42_ALIASES = Map.of(
            B25Postgresql.SLOW, Destination.FAKE_ALPHA, B25Postgresql.OUTAGE, Destination.FAKE_BETA,
            B25Postgresql.FAST, Destination.FAKE_GAMMA, B25Postgresql.FILLER, Destination.FAKE_DELTA,
            B25Postgresql.RETRY, Destination.FAKE_EPSILON, B25Postgresql.THROTTLE, Destination.FAKE_ZETA,
            B25Postgresql.UNSAFE, Destination.FAKE_ETA);

    private B25Worker() { }

    public static void main(String[] args) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            B25Postgresql.require(args.length == 2 && args[1].matches("[ab]"), "Invalid B25 child arguments.");
            var root = Path.of(args[0]).toRealPath();
            var control = root.resolve("control").toRealPath();
            var shared = root.getParent().resolve("pressure").toRealPath();
            serve(input, B25Profile.read(input), root, control, new B25Events(shared), args[1]);
        } catch (Exception | AssertionError failure) {
            System.err.println("B25 child " + failure.getClass().getSimpleName() + " at " + failure.getStackTrace()[0]);
            System.exit(1);
        }
    }

    private static void serve(BufferedReader input, B25Profile.Child child, Path root, Path control,
                              B25Events events, String name) throws Exception {
        String app = "b25-" + name + "-rest";
        var rest = child.rest();
        try (var command = new B25RoleSessions.Command(rest.source(B33Profile.Role.COMMAND, app));
             var evidence = Boolean.getBoolean("requests.b42.protected") ? new B42FixtureEvidence(root, control) : null) {
            var proof = rest.source(B33Profile.Role.CREATE, "b25-rest-proof-" + name);
            var journal = rest.source(B33Profile.Role.JOURNAL, "b25-rest-journal-" + name);
            var original = B25RoleSessions.observer(rest.source(B33Profile.Role.ORIGINAL, "b25-observer-" + name),
                    root.getParent().resolve("pressure/observer.lock"));
            var settings = child.settings(app, command, proof, journal, original);
            var stopping = new AtomicBoolean();
            var failure = new AtomicReference<Throwable>();
            Runnable deliver = () -> {
                try {
                    delivery(child, settings, control, events, name, stopping, evidence);
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    System.err.println("B25 dispatch " + error.getClass().getSimpleName() + " at "
                            + error.getStackTrace()[0]);
                }
            };
            var dispatch = Thread.ofPlatform().name("b25-fixture-dispatch-control").start(deliver);
            try {
                var http = Files.createDirectory(root.resolve("http"));
                if (evidence == null) {
                    B33Runtime.serve(http, settings, input);
                } else {
                    B33Runtime.serve(http, settings, input, evidence.recorder());
                }
            } finally {
                stopping.set(true);
                if (!dispatch.join(Duration.ofSeconds(8))) {
                    throw new IllegalStateException("B25 dispatcher did not stop.");
                }
            }
            if (failure.get() != null) {
                throw new IllegalStateException("B25 dispatch control failed.", failure.get());
            }
        }
    }

    private static void delivery(B25Profile.Child child,
                                 com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings settings,
                                  Path control, B25Events events, String name, AtomicBoolean stopping,
                                  B42FixtureEvidence evidence) throws Exception {
        while (!stopping.get() && !Files.exists(control.resolve("enable"))) {
            Thread.sleep(25);
        }
        if (stopping.get()) {
            return;
        }
        var source = child.worker(name);
        var proof = child.proof(name);
        var remote = B25RemoteDatabase.runtime(child.remote());
        var routes = B25Postgresql.routes();
        try (var worker = evidence == null
                ? B25Bootstrap.start(settings, source, proof, remote, routes, B25Postgresql.CONTRACTS,
                        endpoint -> events.connector(name, endpoint))
                : B25Bootstrap.startProtected(settings, source, proof, remote, routes, B25Postgresql.CONTRACTS,
                        endpoint -> events.connector(name, endpoint), evidence, B42_ALIASES)) {
            var recovery = control.resolve("recover");
            var result = control.resolve("recovered");
            String previous = "";
            while (!stopping.get()) {
                var snapshot = worker.snapshot();
                var state = source.opened() + " " + snapshot.largestBatch() + " " + snapshot.failures();
                if (!state.equals(previous)) {
                    B32Gate.atomic(control.resolve("snapshot"), state);
                    previous = state;
                }
                if (Files.exists(recovery) && !Files.exists(result)) {
                    var fields = Files.readString(recovery).strip().split(" ");
                    var key = new EffectKey(B25Postgresql.UNSAFE, new OrganizationId(fields[0]), fields[1]);
                    var attempt = new AttemptId(key, Long.parseLong(fields[2]));
                    B32Gate.atomic(result, worker.recover(B10Postgresql.OWNER, attempt, Action.RETRY).name());
                }
                Thread.sleep(25);
            }
        }
    }
}
