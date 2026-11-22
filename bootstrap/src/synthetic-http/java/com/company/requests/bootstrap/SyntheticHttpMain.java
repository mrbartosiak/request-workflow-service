package com.company.requests.bootstrap;

import com.company.requests.adapter.http.RequestEvidenceFilter;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.WriterEpoch;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import jakarta.servlet.DispatcherType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** Local B08 application for a provisioned synthetic workflow and its independent proof store. */
public final class SyntheticHttpMain {
    private SyntheticHttpMain() { }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 4) {
            System.err.println("Usage: SyntheticHttpMain <port (0 for automatic)> <empty scratch directory>"
                    + " [<private spool directory> <private sink directory>]");
            System.exit(2);
        }
        // The packaged standalone profile uses JUL for both Spring and Tomcat. Its default
        // request diagnostics can contain the raw target or exception text.
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            int port = Integer.parseInt(args[0]);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid local HTTP port.");
            }
            Path scratch = Path.of(args[1]).toRealPath();
            try (var entries = Files.list(scratch)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("An empty HTTP scratch directory is required.");
                }
            }
            var settings = settings(input);
            if (args.length == 4) {
                serveProtected(port, scratch, settings, input, Path.of(args[2]), Path.of(args[3]), secret(input));
            } else {
                serve(port, scratch, settings, input);
            }
        } catch (Exception failure) {
            System.err.println("Synthetic HTTP application failed (" + failure.getClass().getSimpleName() + ").");
            System.exit(1);
        }
    }

    private static void serveProtected(int port, Path scratch, SyntheticCreateConfiguration.Settings settings,
                                       BufferedReader input, Path spoolDirectory, Path sinkDirectory, byte[] key)
            throws Exception {
        if (Files.isSameFile(spoolDirectory, sinkDirectory) || Files.isSameFile(scratch, spoolDirectory)
                || Files.isSameFile(scratch, sinkDirectory)) {
            throw new IllegalArgumentException("The protected spool, sink and HTTP scratch must be separate.");
        }
        try (var spool = ProtectedEvidenceSpool.open(spoolDirectory, key);
             var sink = new ProtectedLocalEvidenceSink(sinkDirectory, key);
             var drain = Executors.newSingleThreadScheduledExecutor(
                     Thread.ofPlatform().daemon().name("synthetic-evidence-drain").factory())) {
            drain.scheduleWithFixedDelay(new Runnable() {
                private ProtectedEvidenceSpool.Snapshot previous;

                @Override
                public void run() {
                    spool.drain(sink, ProtectedEvidenceSpool.MAX_DRAIN_FRAMES);
                    var current = spool.snapshot();
                    if (previous == null || pressureChanged(previous, current)) {
                        System.out.println("evidence_spool " + current);
                    }
                    previous = current;
                }
            }, 0, 250, TimeUnit.MILLISECONDS);
            serve(port, scratch, settings, input, new RequestEvidenceRecorder(Clock.systemUTC(), spool));
        }
    }

    private static boolean pressureChanged(ProtectedEvidenceSpool.Snapshot old,
                                           ProtectedEvidenceSpool.Snapshot current) {
        return old.alert() != current.alert() || old.restricted() != current.restricted()
                || old.resuming() != current.resuming() || old.stopRouting() != current.stopRouting()
                || old.ingressGaps() != current.ingressGaps() || old.outboundGaps() != current.outboundGaps()
                || old.gapStateDurable() != current.gapStateDurable()
                || (old.sinkFailures() == 0) != (current.sinkFailures() == 0)
                || (old.spoolFailures() == 0) != (current.spoolFailures() == 0);
    }

    private static void serve(int port, Path scratch, SyntheticCreateConfiguration.Settings settings,
                              BufferedReader input) throws Exception {
        var evidence = new RequestEvidenceRecorder(Clock.systemUTC(),
                event -> System.out.println("request_evidence " + event.json()));
        serve(port, scratch, settings, input, evidence);
    }

    private static void serve(int port, Path scratch, SyntheticCreateConfiguration.Settings settings,
                              BufferedReader input, RequestEvidenceRecorder evidence) throws Exception {
        var connector = new Connector(Http11NioProtocol.class.getName());
        connector.setPort(port);
        connector.setScheme("http");
        connector.setSecure(false);
        var http = (Http11NioProtocol) connector.getProtocolHandler();
        http.setAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        http.setSSLEnabled(false);

        var server = new StandardServer();
        server.setPort(-1);
        server.setCatalinaBase(scratch.toFile());
        server.setCatalinaHome(scratch.toFile());
        var service = new StandardService();
        service.setName("B08");
        server.addService(service);
        var engine = new StandardEngine();
        engine.setName("B08");
        engine.setDefaultHost("127.0.0.1");
        engine.getPipeline().addValve(new SyntheticConnectorEvidence(evidence));
        service.setContainer(engine);
        var host = new StandardHost();
        host.setName("127.0.0.1");
        host.setAppBase(scratch.toString());
        host.setAutoDeploy(false);
        host.setDeployOnStartup(false);
        host.setCreateDirs(false);
        var errors = new ErrorReportValve();
        errors.setShowReport(false);
        errors.setShowServerInfo(false);
        host.getPipeline().addValve(errors);
        engine.addChild(host);
        var web = new StandardContext();
        web.setName("");
        web.setPath("");
        web.setDocBase(Files.createDirectory(scratch.resolve("web")).toString());
        web.setWorkDir(Files.createDirectory(scratch.resolve("work")).toString());
        web.setUseNaming(false);
        web.addLifecycleListener(event -> {
            if (Lifecycle.CONFIGURE_START_EVENT.equals(event.getType())) {
                web.setConfigured(true);
            }
        });
        host.addChild(web);
        addEvidenceFilter(web, evidence);

        try (var spring = new AnnotationConfigWebApplicationContext()) {
            spring.setServletContext(web.getServletContext());
            spring.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("b08SyntheticSettings", settings));
            spring.register(SyntheticCreateConfiguration.class);
            spring.refresh();
            var requests = spring.getBean(DispatcherServlet.class);
            requests.setEnableLoggingRequestDetails(false);
            var dispatcher = Tomcat.addServlet(web, "requests", requests);
            dispatcher.setLoadOnStartup(1);
            web.addServletMapping("/", "requests", false);
            service.addConnector(connector);
            try {
                server.start();
                int boundPort = connector.getLocalPort();
                if (boundPort <= 0 || !connector.getState().isAvailable() || !web.getState().isAvailable()) {
                    throw new IllegalStateException("Synthetic MVC connector failed to start.");
                }
                System.out.println("READY http://127.0.0.1:" + boundPort);
                System.out.flush();
                if (input.read() != -1) {
                    throw new IOException("Close the configuration stream to stop the application.");
                }
            } finally {
                try {
                    server.stop();
                } finally {
                    server.destroy();
                }
            }
        }
    }

    private static void addEvidenceFilter(StandardContext web, RequestEvidenceRecorder recorder) {
        String name = "requestEvidence";
        var definition = new FilterDef();
        definition.setFilterName(name);
        definition.setFilterClass(RequestEvidenceFilter.class.getName());
        definition.setFilter(new RequestEvidenceFilter(recorder));
        web.addFilterDef(definition);
        var mapping = new FilterMap();
        mapping.setFilterName(name);
        mapping.setDispatcher(DispatcherType.REQUEST.name());
        mapping.addURLPattern("/*");
        web.addFilterMap(mapping);
    }

    private static SyntheticCreateConfiguration.Settings settings(BufferedReader input) throws IOException {
        var command = localDataSource(55480, "workflow_db", "b04_workflow", requiredLine(input));
        var proof = localDataSource(55481, "proof_db", "b04_proof", requiredLine(input));
        var writer = new WriterEpoch(UUID.fromString(requiredLine(input)), Long.parseLong(requiredLine(input)));
        var secrets = new OperationSecrets(1, Map.of(1, secret(input)), 1, Map.of(1, secret(input)));
        var actors = B08SyntheticScope.actors(requiredLine(input), requiredLine(input), requiredLine(input),
                requiredLine(input));
        return new SyntheticCreateConfiguration.Settings(command, proof, B08SyntheticScope.POLICY, writer,
                actors, Instant::now, secrets);
    }

    private static String requiredLine(BufferedReader input) throws IOException {
        String line = input.readLine();
        if (line == null || line.isBlank()) {
            throw new IOException("Missing synthetic configuration field.");
        }
        return line;
    }

    private static byte[] secret(BufferedReader input) throws IOException {
        byte[] decoded = Base64.getUrlDecoder().decode(requiredLine(input));
        if (decoded.length != 32) {
            throw new IOException("Expected a 32-byte synthetic secret.");
        }
        return decoded;
    }

    private static DriverManagerDataSource localDataSource(int port, String database, String user, String password) {
        var properties = new Properties();
        properties.setProperty("sslmode", "disable");
        properties.setProperty("gssEncMode", "disable");
        properties.setProperty("requireAuth", "scram-sha-256");
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "6");
        properties.setProperty("options",
                "-c statement_timeout=4000 -c lock_timeout=2500 -c idle_in_transaction_session_timeout=12000");
        var source = new DriverManagerDataSource();
        source.setUrl("jdbc:postgresql://127.0.0.1:" + port + "/" + database);
        source.setUsername(user);
        source.setPassword(password);
        source.setConnectionProperties(properties);
        return source;
    }
}
