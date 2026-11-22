package com.company.requests.bootstrap;

import com.company.requests.adapter.http.RequestEvidenceFilter;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate.Point;
import jakarta.servlet.DispatcherType;
import java.io.BufferedReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Properties;
import javax.sql.DataSource;
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
import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** Fixture-only local listener for the packaged effective access and B14 MVC configuration. */
public final class B33Runtime {
    private B33Runtime() { }

    public static void serve(Path scratch, SyntheticEffectiveAccessConfiguration.Settings settings,
                             BufferedReader input) throws Exception {
        var connector = new Connector(Http11NioProtocol.class.getName());
        connector.setPort(0);
        connector.setScheme("http");
        connector.setSecure(false);
        var http = (Http11NioProtocol) connector.getProtocolHandler();
        http.setAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        http.setSSLEnabled(false);

        var server = new StandardServer();
        server.setPort(-1);
        server.setCatalinaBase(scratch.toFile());
        server.setCatalinaHome(scratch.toFile());
        var service = new StandardService();
        service.setName("B33");
        server.addService(service);
        var engine = new StandardEngine();
        engine.setName("B33");
        engine.setDefaultHost("127.0.0.1");
        var evidence = new RequestEvidenceRecorder(Clock.systemUTC(),
                event -> System.out.println("request_evidence " + event.json()));
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
        var definition = new FilterDef();
        definition.setFilterName("requestEvidence");
        definition.setFilterClass(RequestEvidenceFilter.class.getName());
        definition.setFilter(new RequestEvidenceFilter(evidence));
        web.addFilterDef(definition);
        var mapping = new FilterMap();
        mapping.setFilterName("requestEvidence");
        mapping.setDispatcher(DispatcherType.REQUEST.name());
        mapping.addURLPattern("/*");
        web.addFilterMap(mapping);
        try (var spring = new AnnotationConfigWebApplicationContext()) {
            spring.setServletContext(web.getServletContext());
            spring.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("b33SyntheticSettings", settings));
            spring.register(SyntheticEffectiveAccessConfiguration.class);
            spring.refresh();
            var requests = spring.getBean(DispatcherServlet.class);
            requests.setEnableLoggingRequestDetails(false);
            Tomcat.addServlet(web, "requests", requests).setLoadOnStartup(1);
            web.addServletMapping("/", "requests", false);
            service.addConnector(connector);
            try {
                server.start();
                int port = connector.getLocalPort();
                if (port <= 0 || !connector.getState().isAvailable() || !web.getState().isAvailable()) {
                    throw new IllegalStateException("B33 listener failed to start.");
                }
                System.out.println("READY http://127.0.0.1:" + port);
                System.out.flush();
                if (input.read() != -1) {
                    throw new IllegalStateException("B33 listener shutdown requires EOF.");
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

    public static DataSource role(Store store, String user, String password, String app) {
        var source = new DriverManagerDataSource();
        source.setUrl("jdbc:postgresql://127.0.0.1:" + store.port + "/" + store.database);
        source.setUsername(user);
        source.setPassword(password);
        var options = new Properties();
        options.setProperty("sslmode", "disable");
        options.setProperty("gssEncMode", "disable");
        options.setProperty("requireAuth", "scram-sha-256");
        options.setProperty("connectTimeout", "3");
        options.setProperty("loginTimeout", "5");
        options.setProperty("socketTimeout", "6");
        options.setProperty("ApplicationName", app);
        options.setProperty("currentSchema", store.schema);
        options.setProperty("options",
                "-c statement_timeout=4000 -c lock_timeout=2500 -c idle_in_transaction_session_timeout=12000");
        source.setConnectionProperties(options);
        return source;
    }

    public static final class Command extends AbstractDataSource implements AutoCloseable {
        private final B32DataSource delegate;
        private final B32Gate gate;

        public Command(String app, String password, Path controls) {
            gate = new B32Gate(controls);
            delegate = new B32DataSource(Store.WORKFLOW, app, password, gate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            try {
                int backend = connection.unwrap(PGConnection.class).getBackendPID();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (self, method, args) -> {
                            Object result = invoke(connection, method, args);
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                                String statement = sql.stripLeading();
                                if (statement.startsWith("INSERT INTO requests.operation_receipt")
                                        && statement.contains("DO NOTHING")) {
                                    return observe((PreparedStatement) result, backend, true);
                                }
                                if (statement.startsWith("SELECT intent_digest_version")) {
                                    return observe((PreparedStatement) result, backend, false);
                                }
                            }
                            return result;
                        });
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.close();
                } catch (SQLException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            }
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return delegate.getConnection(user, password);
        }

        private PreparedStatement observe(PreparedStatement statement, int backend, boolean claim) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (self, method, args) -> {
                        String operation = claim ? "executeUpdate" : "executeQuery";
                        if (!method.getName().equals(operation) || method.getParameterCount() != 0) {
                            return invoke(statement, method, args);
                        }
                        long started = System.nanoTime();
                        Object result;
                        try {
                            result = invoke(statement, method, args);
                        } catch (SQLException failure) {
                            trace(claim ? "CLAIM_ERROR" : "LOOKUP_ERROR", backend, started, failure.getSQLState());
                            throw failure;
                        }
                        if (!claim) {
                            trace("LOOKUP", backend, started, "-");
                        } else if (Integer.valueOf(1).equals(result)) {
                            trace("CLAIM_WON", backend, started, "-");
                        } else if (Integer.valueOf(0).equals(result)) {
                            trace("CLAIM_DUPLICATE", backend, started, "-");
                            // The B32 prerelease point is unused by this source. Here the losing INSERT has
                            // returned 0 and B does not yet hold the organization guard for result lookup.
                            gate.hit(Point.BEFORE_RELEASE, backend);
                        } else {
                            throw new AssertionError("Unexpected JDBC receipt claim count.");
                        }
                        return result;
                    });
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }
    }

    private static void trace(String event, int backend, long started, String code) {
        System.out.printf("B33_JDBC %s %d %s %d %s%n", event, backend, Instant.now(),
                (System.nanoTime() - started) / 1_000_000L, code);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
