import com.company.requests.adapter.authority.JdbcReleaseEvidence;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B33Runtime;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.PublishOutboxTrigger;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private process configuration built from the same roles and certified writer as B17. */
record B25Profile(B33Profile.Child rest, String workerA, String workerB, String proof, String remote) {
    private static final Instant CLOCK = Instant.parse("2026-11-22T21:00:00Z");

    static B25Profile prepare(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections authority,
                              B17Postgresql base, String remotePassword) {
        var owner = new JdbcTemplate(B08MvcSession.dataSource(authority.migration()));
        var passwords = new EnumMap<B33Profile.Role, String>(B33Profile.Role.class);
        passwords.put(B33Profile.Role.COMMAND, workflow.runtimeLogin().password());
        passwords.put(B33Profile.Role.JOURNAL, authority.runtimeLogin().password());
        for (var role : B33Profile.Role.values()) {
            if (!passwords.containsKey(role)) {
                passwords.put(role, rotate(role.store == Store.WORKFLOW ? base.sql : owner, role.login));
            }
        }
        var rest = new B33Profile.Child(Map.copyOf(passwords), base.settings.create().writer(), B32Fixture.token(),
                B32Fixture.token(), B32Fixture.token(), base.ownerBearer, B32Fixture.token(),
                base.publisherBearer, B32Fixture.token());
        return new B25Profile(rest, rotate(base.sql, "b23_worker_a"), rotate(base.sql, "b23_worker_b"),
                rotate(owner, "b23_proof_reader"), remotePassword);
    }

    private static String rotate(JdbcTemplate db, String role) {
        var secret = B32Fixture.token();
        db.execute("ALTER ROLE " + role + " PASSWORD '" + secret + "'");
        return secret;
    }

    List<String> fields(String name) {
        var fields = new ArrayList<>(rest.fields());
        fields.addAll(List.of(name.equals("a") ? workerA : workerB, proof, remote));
        return List.copyOf(fields);
    }

    static Child read(BufferedReader input) throws IOException {
        return new Child(B33Profile.Child.read(input), field(input), field(input), field(input));
    }

    private static String field(BufferedReader input) throws IOException {
        var line = input.readLine();
        if (line == null || !line.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new IOException("Invalid private B25 process configuration.");
        }
        return line;
    }

    Settings parentSettings(Settings settings, String destination) {
        var create = settings.create();
        var replay = new SyntheticCreateConfiguration.GuardedReplay(OriginalReceiptProof.unavailable(),
                JdbcReleaseEvidence.preparation(rest.source(B33Profile.Role.PREPARE, "b25-parent-preparation")),
                JdbcReleaseEvidence.handoffs(rest.source(B33Profile.Role.HANDOFF, "b25-parent-handoff")));
        var updated = new SyntheticCreateConfiguration.Settings(create.command(), create.proof(), create.policy(),
                create.writer(), create.actors(), create.clock(), create.secrets(), Optional.of(replay));
        return publishing(settings, updated, destination);
    }

    private static Settings publishing(Settings source, SyntheticCreateConfiguration.Settings create,
                                       String destination) {
        return new Settings(create, source.preparation(), source.originalObserver(), source.projection(),
                source.resolver(), source.operators(), source.observationKeys(), true,
                Optional.of(new PublishOutboxTrigger(B25Postgresql.TYPE, destination)));
    }

    record Child(B33Profile.Child rest, String worker, String proof, String remote) {
        Settings settings(String app, DataSource command, DataSource createProof, DataSource journal,
                          DataSource original) {
            var base = rest.settings(app, command, createProof, journal, original, () -> CLOCK);
            var create = base.create();
            var publishPolicy = create.policy().withAssigneeOnlyOnPublish(Set.of(B25Postgresql.TYPE));
            var scoped = new SyntheticCreateConfiguration.Settings(command, createProof, publishPolicy,
                    create.writer(), create.actors(), create.clock(), create.secrets(), create.guardedReplay());
            return publishing(base, scoped, B25Postgresql.FAST);
        }

        B25PoolSource worker(String name) {
            var source = B33Runtime.role(Store.WORKFLOW, "b23_worker_" + name, worker, "b25-instance-" + name);
            return new B25PoolSource(source::getConnection, "b25-instance-" + name);
        }

        DataSource proof(String name) {
            return B33Runtime.role(Store.PROOF, "b23_proof_reader", proof, "b25-worker-proof-" + name);
        }

        @Override
        public String toString() {
            return "B25 child settings";
        }
    }

    @Override
    public String toString() {
        return "B25 private profile";
    }
}
