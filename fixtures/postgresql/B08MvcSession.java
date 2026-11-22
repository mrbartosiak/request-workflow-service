import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.bootstrap.B08SyntheticScope;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticCreateConfiguration.Settings;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.TrustedTime;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestType;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Private synthetic MVC fixture, also usable in a short-lived independent app JVM. */
final class B08MvcSession implements AutoCloseable {
    static final OrganizationId ORG = B08SyntheticScope.ORGANIZATION;
    static final String ISSUER = B08SyntheticScope.ISSUER;
    static final String AUDIENCE = B08SyntheticScope.AUDIENCE;
    static final long GENERATION = 7;
    static final RequestType VISIBLE = B08SyntheticScope.VISIBLE;
    static final RequestType CONCEALED = B08SyntheticScope.CONCEALED;
    static final AuthenticatedActor OWNER = B08SyntheticScope.OWNER;
    static final SyntheticScopePolicy POLICY = B08SyntheticScope.POLICY;
    private static final JsonMapper JSON = new JsonMapper();

    private final AnnotationConfigWebApplicationContext context;
    private final MockMvc mvc;

    B08MvcSession(DataSource workflow, DataSource proof, TrustedTime clock, Parameters parameters) {
        this(new Settings(workflow, proof, POLICY, parameters.writer(), parameters.actors(),
                clock, parameters.secrets()), SyntheticCreateConfiguration.class);
    }

    B08MvcSession(SyntheticEffectiveAccessConfiguration.Settings settings) {
        this(settings, SyntheticEffectiveAccessConfiguration.class);
    }

    private B08MvcSession(Object settings, Class<?> configuration) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("b08SyntheticSettings", settings));
        context.register(configuration);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    Reply invoke(Call call) throws Exception {
        var request = MockMvcRequestBuilders.post(call.route());
        if (!call.key().isEmpty()) {
            request.header("Idempotency-Key", call.key());
        }
        if (!call.body().isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return invoke(request, call.bearer());
    }

    Reply invoke(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        request.accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        if (!bearer.isEmpty()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), header(response.getHeader(HttpHeaders.LOCATION)),
                header(response.getHeader(HttpHeaders.CACHE_CONTROL)),
                header(response.getHeader(HttpHeaders.CONTENT_TYPE)), response.getContentAsString());
    }

    @Override
    public void close() {
        context.close();
    }

    static DataSource dataSource(QualifyPostgresql.SqlConnection login) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return login.open();
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("The fixture role is already provisioned.");
            }
        };
    }

    static ChildReply child(QualifyPostgresql.RuntimeLogin workflow, QualifyPostgresql.RuntimeLogin proof,
                            Parameters parameters, Instant now, Call call, Path log) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                B08MvcSession.class.getName()).redirectError(log.toFile()).start();
        try {
            try (var output = new DataOutputStream(process.getOutputStream())) {
                send(output, workflow);
                send(output, proof);
                output.writeUTF(parameters.writer().writerId().toString());
                output.write(parameters.signing());
                output.write(parameters.intent());
                for (String bearer : parameters.bearers()) {
                    output.writeUTF(bearer);
                }
                output.writeUTF(now.toString());
                output.writeUTF(call.route());
                output.writeUTF(call.bearer());
                output.writeUTF(call.key());
                output.writeUTF(call.body());
            }
            if (!process.waitFor(35, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new AssertionError("Independent MVC worker did not exit successfully");
            }
            try (var input = new DataInputStream(process.getInputStream())) {
                return new ChildReply(process.pid(), new Reply(input.readInt(), input.readUTF(), input.readUTF(),
                        input.readUTF(), input.readUTF()));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] args) {
        try (var input = new DataInputStream(System.in)) {
            var workflow = receive(input);
            var proof = receive(input);
            var writer = new WriterEpoch(UUID.fromString(input.readUTF()), GENERATION);
            var signing = input.readNBytes(32);
            var intent = input.readNBytes(32);
            var params = new Parameters(writer, signing, intent,
                    List.of(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF()));
            var now = Instant.parse(input.readUTF());
            var call = new Call(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF());
            try (var mvc = new B08MvcSession(dataSource(workflow::open), dataSource(proof::open), () -> now, params)) {
                Reply result = mvc.invoke(call);
                var output = new DataOutputStream(System.out);
                output.writeInt(result.status());
                output.writeUTF(result.location());
                output.writeUTF(result.cache());
                output.writeUTF(result.contentType());
                output.writeUTF(result.body());
                output.flush();
            }
        } catch (Exception | AssertionError failure) {
            System.err.println("B08 MVC worker failed with " + failure.getClass().getName());
            System.exit(1);
        }
    }

    private static void send(DataOutputStream output, QualifyPostgresql.RuntimeLogin login) throws Exception {
        output.writeInt(login.port());
        output.writeUTF(login.database());
        output.writeUTF(login.username());
        output.writeUTF(login.password());
    }

    private static QualifyPostgresql.RuntimeLogin receive(DataInputStream input) throws Exception {
        return new QualifyPostgresql.RuntimeLogin(input.readInt(), input.readUTF(), input.readUTF(), input.readUTF());
    }

    private static String header(String value) {
        return value == null ? "" : value;
    }

    record Call(String route, String bearer, String key, String body) {
        static Call issue(String bearer) {
            return new Call("/operation-keys", bearer, "", "");
        }

        static Call create(String bearer, String key, String body) {
            return new Call("/requests", bearer, key, body);
        }

        @Override
        public String toString() {
            return "Call[protected]";
        }
    }

    record Reply(int status, String location, String cache, String contentType, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        @Override
        public String toString() {
            return "Reply[status=" + status + "]";
        }
    }

    record ChildReply(long pid, Reply reply) { }

    record Parameters(WriterEpoch writer, byte[] signing, byte[] intent, List<String> bearers) {
        static Parameters generate() {
            var random = new SecureRandom();
            return new Parameters(new WriterEpoch(UUID.randomUUID(), GENERATION), randomBytes(random), randomBytes(random),
                    java.util.stream.IntStream.range(0, 4).mapToObj(ignored ->
                            Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(random))).toList());
        }

        private static byte[] randomBytes(SecureRandom random) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return bytes;
        }

        OperationSecrets secrets() {
            return new OperationSecrets(1, Map.of(1, signing), 1, Map.of(1, intent));
        }

        SyntheticActorResolver actors() {
            return B08SyntheticScope.actors(bearers.get(0), bearers.get(1), bearers.get(2), bearers.get(3));
        }

        @Override
        public String toString() {
            return "Parameters[protected]";
        }
    }
}
