import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Disposable B04 environment probe. Setup and fault controls live outside the application. */
public final class QualifyPostgresql {
    private static final String HOST = "127.0.0.1";
    private static final String SERVER_VERSION = "18.7";
    private static final String DRIVER_VERSION = "42.7.14";
    private static final String SEED = "Zażółć gęślą jaźń — 東京 — 😀";
    private static final String WORKFLOW_AFTER = "workflow committed 818 after backup";
    private static final String PROOF_AFTER = "independent proof for 818 after backup";
    private static final String CREATE_MARKER = "CREATE TABLE fixture.marker (id integer PRIMARY KEY, value text NOT NULL)";
    private static final String INSERT_MARKER = "INSERT INTO fixture.marker VALUES (?, ?)";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> TOOLS = List.of("postgres", "initdb", "pg_ctl", "psql", "pg_isready",
            "pg_dump", "pg_restore", "pg_basebackup", "pg_verifybackup", "pg_waldump", "pg_controldata");

    private final Path bin;
    private final Path run;
    private final Role workflowAdmin = new Role("b04_admin");
    private final Role proofAdmin = new Role("b04_admin");
    private final Role workflowWriter = new Role("b04_workflow");
    private final Role proofWriter = new Role("b04_proof");
    private final Role workflowObserver = new Role("b04_observer");
    private final Role proofObserver = new Role("b04_observer");
    private final Role workflowBackup = new Role("b04_backup");
    private final Role workflowResolver = new Role("b11_projection");
    private final Role proofResolver = new Role("b11_resolver");
    private final Cluster workflow;
    private final Cluster proof;
    private final Cluster restore;
    private int commandNumber;

    private QualifyPostgresql(Path bin, Path run) {
        this.bin = bin;
        this.run = run;
        workflow = new Cluster(run.resolve("workflow"), 55480, "workflow_db", workflowAdmin);
        proof = new Cluster(run.resolve("proof"), 55481, "proof_db", proofAdmin);
        restore = new Cluster(run.resolve("restored"), 55482, "workflow_db", workflowAdmin);
    }

    public static void main(String[] args) {
        try {
            require(args.length == 4 && List.of("run", "cleanup").contains(args[0]),
                    "Usage: QualifyPostgresql.java run|cleanup BIN RUNS_DIRECTORY b04-RUN_ID");
            require(args[3].matches("b04-[a-z0-9-]{1,32}"), "Run ID must be b04- and 1–32 lowercase letters, digits or hyphens");
            Path bin = Path.of(args[1]).toRealPath();
            Path runs = Path.of(args[2]).toRealPath();
            require(Files.isDirectory(runs) && runs.getFileName().toString().equals("runs"), "Use the fixture scratch runs directory");
            Path run = runs.resolve(args[3]);
            Path owner = run.resolve("b04-run-id");
            QualifyPostgresql probe = new QualifyPostgresql(bin, run);
            if (args[0].equals("cleanup")) {
                require(run.equals(run.toRealPath()) && owner.equals(owner.toRealPath())
                        && Files.readString(owner).equals(args[3]), "Cleanup requires the original B04 run directory");
                probe.cleanup();
                return;
            }
            for (Cluster cluster : probe.clusters()) {
                requirePortFree(cluster.port);
            }
            Files.createDirectory(run);
            Files.writeString(owner, args[3], StandardOpenOption.CREATE_NEW);
            try {
                probe.qualify();
            } catch (Exception | Error failure) {
                try {
                    probe.cleanup();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            probe.cleanup();
            System.out.println("PASS B04 PostgreSQL qualification and teardown " + Instant.now());
        } catch (SQLException e) {
            System.err.println("B04 failed with JDBC SQLSTATE " + e.getSQLState() + " at " + e.getStackTrace()[0]);
            for (StackTraceElement frame : e.getStackTrace()) {
                if (frame.getClassName().startsWith("QualifyPostgresql")) {
                    System.err.println("  at " + frame);
                }
            }
            System.exit(1);
        } catch (Exception e) {
            System.err.println("B04 failed: " + e);
            System.exit(1);
        }
    }

    private List<Cluster> clusters() {
        return List.of(workflow, proof, restore);
    }

    /** Reuse the qualified setup and teardown for a workflow-only fixture check. */
    static void workflowOnly(String[] args, WorkflowCheck check) throws Exception {
        require(args.length == 4 && List.of("run", "cleanup").contains(args[0]),
                "Usage: run|cleanup BIN RUNS_DIRECTORY b05-RUN_ID");
        require(args[3].matches("b05-[a-z0-9-]{1,32}"), "Invalid B05 run ID");
        require(Runtime.version().feature() == 25, "Java 25 required");
        Path bin = Path.of(args[1]).toRealPath();
        Path runs = Path.of(args[2]).toRealPath();
        require(Files.isDirectory(runs) && runs.getFileName().toString().equals("runs"), "Use the fixture scratch runs directory");
        Path run = runs.resolve(args[3]);
        Path owner = run.resolve("b05-run-id");
        QualifyPostgresql probe = new QualifyPostgresql(bin, run);
        if (args[0].equals("cleanup")) {
            require(run.equals(run.toRealPath()) && owner.equals(owner.toRealPath())
                    && Files.readString(owner).equals(args[3]), "Cleanup requires the original B05 run directory");
            probe.stopWorkflowOnly();
            return;
        }
        requirePortFree(probe.workflow.port);
        Files.createDirectory(run);
        Files.writeString(owner, args[3], StandardOpenOption.CREATE_NEW);
        System.out.printf("B05 workflow run=%s Java=%s UTC start=%s%n", run, Runtime.version(), Instant.now());
        try {
            probe.initialize(probe.workflow, probe.workflowWriter, probe.workflowObserver, null);
            probe.inspect(probe.workflow, probe.workflowWriter, probe.workflowObserver);
            check.run(() -> connect(probe.workflow, probe.workflowAdmin),
                    () -> connect(probe.workflow, probe.workflowWriter),
                    () -> connect(probe.workflow, probe.workflowObserver));
        } catch (Exception | Error failure) {
            try {
                probe.stopWorkflowOnly();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        probe.stopWorkflowOnly();
        System.out.println("PASS B05 workflow and teardown " + Instant.now());
    }

    private void stopWorkflowOnly() throws Exception {
        Files.deleteIfExists(initPasswordFile(workflow));
        if (Files.exists(workflow.data.resolve("PG_VERSION"), LinkOption.NOFOLLOW_LINKS)) {
            stop(workflow);
        }
    }

    @FunctionalInterface
    interface WorkflowCheck {
        void run(SqlConnection migration, SqlConnection command, SqlConnection observer) throws Exception;
    }

    @FunctionalInterface
    interface SqlConnection {
        Connection open() throws SQLException;
    }

    /** Run a two-cluster check with independently restricted runtime and observer identities. */
    static void workflowAndProof(String[] args, WorkflowAndProofCheck check) throws Exception {
        workflowAndProof(args, "b06", check);
    }

    static void workflowAndProof(String[] args, String ticket, WorkflowAndProofCheck check) throws Exception {
        workflowAndProof(args, ticket, check, null, null);
    }

    static void scopeProofAndRestore(String[] args, ScopeProofCheck check) throws Exception {
        workflowAndProof(args, "b09", null, check, null);
    }

    static void scopedResolverAndRestore(String[] args, ScopeResolverCheck check) throws Exception {
        workflowAndProof(args, "b11", null, null, check);
    }

    static void publicationProofAndRestore(String[] args, ScopeResolverCheck check) throws Exception {
        workflowAndProof(args, "b16", null, null, check);
    }

    private static void workflowAndProof(String[] args, String ticket, WorkflowAndProofCheck check,
                                         ScopeProofCheck scopeCheck, ScopeResolverCheck resolverCheck) throws Exception {
        boolean withResolver = resolverCheck != null;
        boolean withRestore = scopeCheck != null || withResolver;
        require(List.of("b06", "b08", "b09", "b11", "b16").contains(ticket), "Unexpected dual-cluster fixture ticket");
        String label = ticket.toUpperCase(Locale.ROOT);
        require(args.length == 4 && List.of("run", "cleanup").contains(args[0]),
                "Usage: run|cleanup BIN RUNS_DIRECTORY " + ticket + "-RUN_ID");
        require(args[3].matches(ticket + "-[a-z0-9-]{1,32}"), "Invalid " + label + " run ID");
        require(Runtime.version().feature() == 25, "Java 25 required");
        Path bin = Path.of(args[1]).toRealPath();
        Path runs = Path.of(args[2]).toRealPath();
        require(Files.isDirectory(runs) && runs.getFileName().toString().equals("runs"), "Use the fixture scratch runs directory");
        Path run = runs.resolve(args[3]);
        Path owner = run.resolve(ticket + "-run-id");
        QualifyPostgresql probe = new QualifyPostgresql(bin, run);
        if (args[0].equals("cleanup")) {
            require(run.equals(run.toRealPath()) && owner.equals(owner.toRealPath())
                    && Files.readString(owner).equals(args[3]), "Cleanup requires the original " + label + " run directory");
            probe.dualCleanup(withRestore);
            return;
        }
        requirePortFree(probe.workflow.port);
        requirePortFree(probe.proof.port);
        if (withRestore) {
            requirePortFree(probe.restore.port);
        }
        Files.createDirectory(run);
        Files.writeString(owner, args[3], StandardOpenOption.CREATE_NEW);
        System.out.printf("%s run=%s Java=%s UTC start=%s%n", label, run, Runtime.version(), Instant.now());
        try {
            WorkflowAndProofCheck operation = check;
            if (withResolver) {
                operation = (workflow, proof) -> resolverCheck.run(workflow, proof, probe::restoreWorkflowSnapshot,
                        () -> connect(probe.workflow, probe.workflowResolver),
                        () -> connect(probe.proof, probe.proofResolver));
            } else if (withRestore) {
                operation = (workflow, proof) -> scopeCheck.run(workflow, proof, probe::restoreWorkflowSnapshot);
            }
            probe.runDualCheck(operation, withRestore, withResolver);
        } catch (Exception | Error failure) {
            try {
                probe.dualCleanup(withRestore);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        probe.dualCleanup(withRestore);
        System.out.println("PASS " + label + " workflow/proof verification and teardown " + Instant.now());
    }

    private void runDualCheck(WorkflowAndProofCheck check, boolean withRestore, boolean withResolver) throws Exception {
        initialize(workflow, workflowWriter, workflowObserver, withRestore ? workflowBackup : null,
                withResolver ? workflowResolver : null);
        initialize(proof, proofWriter, proofObserver, null, withResolver ? proofResolver : null);
        inspect(workflow, workflowWriter, workflowObserver);
        inspect(proof, proofWriter, proofObserver);
        denyConnection(workflow, proofWriter.name, proofWriter.password, "28000");
        denyConnection(proof, workflowWriter.name, workflowWriter.password, "28000");
        String workflowId = systemId(workflow);
        String proofId = systemId(proof);
        require(!workflowId.equals(proofId), "Workflow and proof system identifiers must differ");
        System.out.printf("PASS independent system identifiers workflow=%s proof=%s%n", workflowId, proofId);
        check.run(connections(workflow, workflowWriter, workflowObserver, workflowId),
                connections(proof, proofWriter, proofObserver, proofId));
    }

    private SqlConnection restoreWorkflowSnapshot() throws Exception {
        Path backup = run.resolve("scope-workflow-backup");
        NativeResult baseBackup = nativeCommand("pg_basebackup", 75, workflowBackup.password,
                "-h", HOST, "-p", "" + workflow.port, "-U", workflowBackup.name, "--no-password",
                "--pgdata=" + backup, "--format=plain", "--wal-method=stream", "--checkpoint=fast",
                "--manifest-checksums=SHA256");
        NativeResult verified = nativeCommand("pg_verifybackup", 45, null, backup.toString());
        require(verified.read().contains("backup successfully verified"), "Missing scope backup verification result");
        copyBackup(backup, restore.data);
        Files.writeString(restore.data.resolve("postgresql.conf"), "\nport = " + restore.port + "\n",
                StandardOpenOption.APPEND);
        start(restore);
        require(systemId(workflow).equals(systemId(restore)), "Restored scope must preserve the workflow system ID");
        System.out.printf("PASS same-system-ID physical workflow backup and isolated restore (%s, %s)%n",
                baseBackup.log.getFileName(), verified.log.getFileName());
        return () -> connect(restore, workflowObserver);
    }

    private Connections connections(Cluster cluster, Role writer, Role observer, String systemId) {
        return new Connections(() -> connect(cluster, cluster.admin), () -> connect(cluster, writer),
                () -> connect(cluster, observer), () -> restart(cluster, systemId),
                new RuntimeLogin(cluster.port, cluster.database, writer.name, writer.password));
    }

    private void restart(Cluster cluster, String expectedId) throws Exception {
        stop(cluster);
        start(cluster);
        require(expectedId.equals(systemId(cluster)), "Restart changed the fixture system identifier");
    }

    private void dualCleanup(boolean withRestore) throws Exception {
        cleanup(withRestore ? List.of(restore, proof, workflow) : List.of(proof, workflow));
    }

    record Connections(SqlConnection migration, SqlConnection runtime, SqlConnection observer,
                       Restart restart, RuntimeLogin runtimeLogin) { }

    @FunctionalInterface
    interface ScopeProofCheck {
        void run(Connections workflow, Connections proof, ScopeRestore restore) throws Exception;
    }

    @FunctionalInterface
    interface ScopeResolverCheck {
        void run(Connections workflow, Connections proof, ScopeRestore restore,
                 SqlConnection workflowResolver, SqlConnection proofResolver) throws Exception;
    }

    @FunctionalInterface
    interface ScopeRestore {
        SqlConnection snapshot() throws Exception;
    }

    record RuntimeLogin(int port, String database, String username, String password) {
        RuntimeLogin {
            require(port == 55480 && database.equals("workflow_db") && username.equals("b04_workflow")
                    || port == 55481 && database.equals("proof_db") && username.equals("b04_proof"),
                    "Child JVM may only connect as an approved runtime role");
        }

        Connection open() throws SQLException {
            return connect(port, database, username, password);
        }

        @Override
        public String toString() {
            return "RuntimeLogin[protected]";
        }
    }

    @FunctionalInterface
    interface Restart {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface WorkflowAndProofCheck {
        void run(Connections workflow, Connections proof) throws Exception;
    }

    private void qualify() throws Exception {
        require(Runtime.version().feature() == 25, "Java 25 required");
        System.out.printf("B04 run=%s Java=%s Windows=%s %s%n", run, Runtime.version(),
                System.getProperty("os.version"), System.getProperty("os.arch"));
        System.out.println("UTC start " + Instant.now());
        for (String tool : TOOLS) {
            NativeResult result = nativeCommand(tool, 10, null, "--version");
            require(result.read().strip().equals(tool + " (PostgreSQL) " + SERVER_VERSION), "Wrong binary version: " + tool);
            System.out.println(result.read().strip());
        }
        initialize(workflow, workflowWriter, workflowObserver, workflowBackup);
        initialize(proof, proofWriter, proofObserver, null);
        inspect(workflow, workflowWriter, workflowObserver);
        inspect(proof, proofWriter, proofObserver);
        denyConnection(workflow, workflowWriter.name, proofWriter.password, "28P01");
        denyConnection(proof, proofWriter.name, workflowWriter.password, "28P01");
        denyConnection(workflow, proofWriter.name, proofWriter.password, "28000");
        denyConnection(proof, workflowWriter.name, workflowWriter.password, "28000");
        denyConnection(workflow, workflowObserver.name, proofObserver.password, "28P01");
        denyConnection(proof, proofObserver.name, workflowObserver.password, "28P01");
        System.out.println("PASS workflow/proof writer and observer credential isolation");

        try (Connection writer = connect(workflow, workflowWriter)) {
            writeMarker(writer, 817, SEED);
        }
        try (Connection writer = connect(proof, proofWriter)) {
            writeMarker(writer, 817, SEED);
        }
        checkRows(workflow, workflowObserver, 1, null);
        checkRows(proof, proofObserver, 1, null);
        locking();

        String workflowId = systemId(workflow);
        String proofId = systemId(proof);
        require(!workflowId.equals(proofId), "Workflow and proof system identifiers must differ");
        Path backup = run.resolve("workflow-backup");
        NativeResult baseBackup = nativeCommand("pg_basebackup", 75, workflowBackup.password,
                "-h", HOST, "-p", "" + workflow.port, "-U", workflowBackup.name, "--no-password",
                "--pgdata=" + backup, "--format=plain", "--wal-method=stream", "--checkpoint=fast",
                "--manifest-checksums=SHA256", "--verbose");
        NativeResult verified = nativeCommand("pg_verifybackup", 45, null, backup.toString());
        require(verified.read().contains("backup successfully verified"), "Missing native backup verification result");
        System.out.printf("PASS physical workflow backup, SHA256 manifest and WAL verified (%s, %s)%n",
                baseBackup.log.getFileName(), verified.log.getFileName());
        try (Connection writer = connect(workflow, workflowWriter)) {
            writeMarker(writer, 818, WORKFLOW_AFTER);
        }
        try (Connection writer = connect(proof, proofWriter)) {
            writeMarker(writer, 818, PROOF_AFTER);
        }
        checkRows(workflow, workflowObserver, 2, WORKFLOW_AFTER);
        checkRows(proof, proofObserver, 2, PROOF_AFTER);
        int proofPid = postmasterPid(proof);
        stop(workflow);

        copyBackup(backup, restore.data);
        Files.writeString(restore.data.resolve("postgresql.conf"), "\nport = " + restore.port + "\n",
                StandardOpenOption.APPEND);
        start(restore);
        require(workflowId.equals(systemId(restore)), "Restored workflow must have original workflow system identifier");
        inspect(restore, workflowWriter, workflowObserver);
        checkRows(restore, workflowObserver, 1, null);
        checkRows(proof, proofObserver, 2, PROOF_AFTER);
        require(proofPid == postmasterPid(proof) && proofId.equals(systemId(proof)), "Proof restarted or changed during workflow restore");
        System.out.printf("PASS actual workflow restore, workflow=%s restored=%s proof=%s proof_pid=%d%n",
                workflowId, systemId(restore), proofId, proofPid);
        System.out.println("PASS live workflow/proof counts=2/2, restored workflow/proof counts=1/2, workflow marker 818 absent after restore");

        stop(proof);
        start(proof);
        require(proofId.equals(systemId(proof)), "Restarted proof must retain its system identifier");
        checkRows(proof, proofObserver, 2, PROOF_AFTER);
        System.out.printf("PASS proof marker 818 survived separate proof restart, proof_pid=%d%n", postmasterPid(proof));
    }

    private void initialize(Cluster cluster, Role writer, Role observer, Role backup) throws Exception {
        initialize(cluster, writer, observer, backup, null);
    }

    private void initialize(Cluster cluster, Role writer, Role observer, Role backup, Role resolver) throws Exception {
        Path passwordFile = initPasswordFile(cluster);
        Files.writeString(passwordFile, cluster.admin.password + "\n", StandardOpenOption.CREATE_NEW);
        try {
            nativeCommand("initdb", 75, null, "-D", cluster.data.toString(), "-U", cluster.admin.name,
                    "--pwfile=" + passwordFile, "--auth=scram-sha-256", "--encoding=UTF8", "--locale=C");
        } finally {
            Files.delete(passwordFile);
        }
        Files.writeString(cluster.data.resolve("postgresql.conf"), """

                listen_addresses = '127.0.0.1'
                unix_socket_directories = ''
                ssl = off
                max_connections = 16
                password_encryption = 'scram-sha-256'
                wal_level = replica
                max_wal_senders = 2
                fsync = on
                synchronous_commit = on
                log_statement = 'none'
                log_min_error_statement = 'panic'
                lc_messages = 'C'
                """ + "port = " + cluster.port + "\n", StandardOpenOption.APPEND);
        String hba = hbaRule("postgres", cluster.admin.name)
                + hbaRule(cluster.database, cluster.admin.name)
                + hbaRule(cluster.database, writer.name)
                + hbaRule(cluster.database, observer.name);
        if (backup != null) {
            hba += hbaRule("replication", backup.name);
        }
        if (resolver != null) {
            hba += hbaRule(cluster.database, resolver.name);
        }
        Files.writeString(cluster.data.resolve("pg_hba.conf"), hba, StandardOpenOption.TRUNCATE_EXISTING);
        start(cluster);
        NativeResult psql = nativeCommand("psql", 10, cluster.admin.password, "-X", "-w", "-h", HOST, "-p", "" + cluster.port,
                "-U", cluster.admin.name, "-d", "postgres", "-Atc", "SHOW server_version_num");
        require(psql.read().strip().equals("180007"), "psql observed unexpected server version");
        try (Connection admin = connect(cluster, "postgres", cluster.admin.name, cluster.admin.password)) {
            runSql(admin, "REVOKE ALL ON DATABASE postgres, template1 FROM PUBLIC");
            createLogin(admin, writer, "NOREPLICATION");
            createLogin(admin, observer, "NOREPLICATION");
            if (backup != null) {
                createLogin(admin, backup, "REPLICATION");
            }
            runSql(admin, "CREATE DATABASE " + cluster.database);
            runSql(admin, "REVOKE ALL ON DATABASE " + cluster.database + " FROM PUBLIC");
            runSql(admin, "GRANT CONNECT ON DATABASE " + cluster.database + " TO " + writer.name + ", " + observer.name);
            if (resolver != null) {
                createLogin(admin, resolver, "NOREPLICATION");
                runSql(admin, "GRANT CONNECT ON DATABASE " + cluster.database + " TO " + resolver.name);
                denyConnection(cluster, resolver.name, resolver.password + "wrong", "28P01");
            }
        }
        try (Connection admin = connect(cluster, cluster.admin)) {
            runSql(admin, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            runSql(admin, "CREATE SCHEMA fixture");
            runSql(admin, CREATE_MARKER);
            runSql(admin, "GRANT USAGE ON SCHEMA fixture TO " + writer.name + ", " + observer.name);
            runSql(admin, "GRANT SELECT, INSERT, UPDATE ON fixture.marker TO " + writer.name);
            runSql(admin, "GRANT SELECT ON fixture.marker TO " + observer.name);
        }
        if (backup != null) {
            denyConnection(cluster, backup.name, backup.password, "28000");
        }
    }

    private static String hbaRule(String database, String role) {
        return "host " + database + " " + role + " " + HOST + "/32 scram-sha-256\n";
    }

    private static void createLogin(Connection admin, Role role, String replication) throws SQLException {
        runSql(admin, "CREATE ROLE " + role.name + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS "
                + replication + " PASSWORD '" + role.password + "'");
    }

    private void inspect(Cluster cluster, Role writer, Role observer) throws Exception {
        try (Connection auth = connect(cluster, writer); Connection read = connect(cluster, observer)) {
            require(auth.getMetaData().getDriverVersion().equals(DRIVER_VERSION), "Unexpected pgJDBC version");
            validateSettings(auth, cluster);
            validateSettings(read, cluster);
            String flags = "SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)"
                    + " AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member = pg_roles.oid)"
                    + " FROM pg_roles WHERE rolname = current_user";
            require(scalar(auth, flags).equals("t") && scalar(read, flags).equals("t"), "Runtime or observer has elevated role rights");
            expectState("42501", () -> runSql(auth, "CREATE TABLE fixture.forbidden (id int)"));
            expectState("42501", () -> runSql(auth, "CREATE TEMP TABLE forbidden (id int)"));
            expectState("42501", () -> runSql(auth, "DELETE FROM fixture.marker"));
            expectState("42501", () -> runSql(read, "INSERT INTO fixture.marker VALUES (999, 'denied')"));
            expectState("42501", () -> runSql(read, "UPDATE fixture.marker SET value = 'denied'"));
            expectState("42501", () -> runSql(read, "DELETE FROM fixture.marker"));
            require(scalar(read, "SELECT count(*) FROM fixture.marker WHERE id = 999").equals("0"), "Denied observer write had an effect");
        }
        System.out.printf("PASS %s:%d server=%s driver=%s server/client=UTF8 SCRAM and scoped runtime/observer grants%n",
                cluster.data.getFileName(), cluster.port, SERVER_VERSION, DRIVER_VERSION);
    }

    private static void validateSettings(Connection connection, Cluster cluster) throws SQLException {
        require(scalar(connection, "SHOW server_version_num").equals("180007"), "Server version mismatch");
        require(scalar(connection, "SHOW server_encoding").equals("UTF8")
                && scalar(connection, "SHOW client_encoding").equals("UTF8"), "Server/client encoding mismatch");
        require(scalar(connection, "SELECT host(inet_server_addr())").equals(HOST)
                && scalar(connection, "SHOW listen_addresses").equals(HOST)
                && scalar(connection, "SHOW port").equals("" + cluster.port), "Server endpoint mismatch");
        require(scalar(connection, "SHOW statement_timeout").equals("4s")
                && scalar(connection, "SHOW lock_timeout").equals("2500ms")
                && scalar(connection, "SHOW idle_in_transaction_session_timeout").equals("12s"), "Session timeouts differ");
        require(scalar(connection, "SHOW fsync").equals("on")
                && scalar(connection, "SHOW synchronous_commit").equals("on"), "Durability settings differ");
    }

    private void locking() throws Exception {
        try (Connection a = connect(workflow, workflowWriter); Connection b = connect(workflow, workflowWriter);
             Connection observer = connect(workflow, workflowObserver)) {
            int aPid = Integer.parseInt(scalar(a, "SELECT pg_backend_pid()"));
            int bPid = Integer.parseInt(scalar(b, "SELECT pg_backend_pid()"));
            require(aPid != bPid, "Session PIDs must differ");
            a.setAutoCommit(false);
            require(scalar(a, "SELECT id FROM fixture.marker WHERE id = 817 FOR UPDATE").equals("817"), "Session A did not acquire the fixture row");
            String blocks = "SELECT " + aPid + " = ANY(pg_blocking_pids(" + bPid + "))";
            require(scalar(observer, blocks).equals("f"), "Blocking oracle must be false before B's update");
            FutureTask<String> waiter = new FutureTask<>(() -> sqlState(
                    () -> runSql(b, "UPDATE fixture.marker SET value = 'blocked write' WHERE id = 817")));
            Thread thread = Thread.ofVirtual().start(waiter);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!scalar(observer, blocks).equals("t")) {
                    require(System.nanoTime() < deadline && !waiter.isDone(), "Barrier timeout before pg_blocking_pids observed A blocking B");
                    Thread.sleep(20);
                }
                require(waiter.get(5, TimeUnit.SECONDS).equals("55P03"), "B's conflicting write must hit lock_timeout");
                require(scalar(observer, blocks).equals("f"), "Blocking oracle must clear after B times out");
            } finally {
                a.rollback();
                thread.join(7000);
                require(!thread.isAlive(), "Waiter did not terminate after release");
            }
            require(scalar(observer, "SELECT value FROM fixture.marker WHERE id = 817").equals(SEED), "Timed-out write committed");
            require(scalar(b, "UPDATE fixture.marker SET value = value WHERE id = 817 RETURNING id").equals("817"),
                    "Bounded B session could not update after release");
            System.out.printf("PASS separate backends A=%d B=%d pg_blocking_pids=false/true/false, lock timeout=55P03, retry after release=1 row%n", aPid, bPid);
        }
    }

    private static void checkRows(Cluster cluster, Role observer, int count, String after) throws SQLException {
        try (Connection read = connect(cluster, observer)) {
            require(scalar(read, "SELECT count(*) FROM fixture.marker").equals("" + count), "Wrong authoritative row count");
            require(scalar(read, "SELECT value FROM fixture.marker WHERE id = 817").equals(SEED), "UTF-8 seed did not round-trip");
            if (after == null) {
                require(scalar(read, "SELECT count(*) FROM fixture.marker WHERE id = 818").equals("0"), "Unexpected post-backup marker");
            } else {
                require(scalar(read, "SELECT value FROM fixture.marker WHERE id = 818").equals(after), "Missing post-backup marker");
            }
        }
    }

    private static String systemId(Cluster cluster) throws SQLException {
        try (Connection admin = connect(cluster, "postgres", cluster.admin.name, cluster.admin.password)) {
            require(Path.of(scalar(admin, "SHOW data_directory")).equals(cluster.data), "Server data directory mismatch");
            return scalar(admin, "SELECT system_identifier::text FROM pg_control_system()");
        }
    }

    private static Connection connect(Cluster cluster, Role role) throws SQLException {
        return connect(cluster, cluster.database, role.name, role.password);
    }

    private static Connection connect(Cluster cluster, String database, String username, String password) throws SQLException {
        return connect(cluster.port, database, username, password);
    }

    private static Connection connect(int port, String database, String username, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("gssEncMode", "disable");
        properties.setProperty("requireAuth", "scram-sha-256");
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("loginTimeout", "5");
        properties.setProperty("socketTimeout", "6");
        properties.setProperty("options", "-c statement_timeout=4000 -c lock_timeout=2500 -c idle_in_transaction_session_timeout=12000");
        return DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + port + "/" + database, properties);
    }

    private static void denyConnection(Cluster cluster, String username, String password, String state) throws Exception {
        expectState(state, () -> {
            try (Connection unexpected = connect(cluster, cluster.database, username, password)) {
                require(!unexpected.isClosed(), "JDBC returned a closed connection");
            }
            return true;
        });
    }

    private static void expectState(String expected, Callable<Boolean> call) throws Exception {
        String actual = sqlState(call);
        require(actual.equals(expected), "Expected SQLSTATE " + expected + " but got " + actual);
    }

    private static String sqlState(Callable<Boolean> call) throws Exception {
        try {
            call.call();
            return "success";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static boolean runSql(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            return statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            require(rows.next(), "No row returned from scalar check");
            String value = rows.getString(1);
            require(value != null && !rows.next(), "Expected one non-null scalar result");
            return value;
        }
    }

    private static void writeMarker(Connection connection, int id, String value) throws SQLException {
        try (var statement = connection.prepareStatement(INSERT_MARKER)) {
            statement.setInt(1, id);
            statement.setString(2, value);
            require(statement.executeUpdate() == 1, "Expected one committed marker insert");
        }
    }

    private static void copyBackup(Path source, Path destination) throws IOException {
        Files.createDirectory(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.skip(1).toList()) {
                require(path.equals(path.toRealPath()), "Unexpected linked path in plain backup");
                Files.copy(path, destination.resolve(source.relativize(path)), LinkOption.NOFOLLOW_LINKS);
            }
        }
    }

    private void start(Cluster cluster) throws Exception {
        assertClusterPath(cluster);
        requirePortFree(cluster.port);
        nativeCommand("pg_ctl", 35, null, "start", "-D", cluster.data.toString(), "-l",
                run.resolve(cluster.data.getFileName() + "-server.log").toString(), "-w", "-t", "25");
        int pid = postmaster(cluster).pid;
        nativeCommand("pg_isready", 8, null, "-h", HOST, "-p", "" + cluster.port, "-t", "3",
                "-U", cluster.admin.name, "-d", "postgres");
        systemId(cluster);
        System.out.printf("STARTED %s loopback=%d postmaster=%d%n", cluster.data.getFileName(), cluster.port, pid);
    }

    private void stop(Cluster cluster) throws Exception {
        assertClusterPath(cluster);
        Path pidFile = cluster.data.resolve("postmaster.pid");
        if (Files.exists(pidFile, LinkOption.NOFOLLOW_LINKS)) {
            Postmaster owner = postmaster(cluster);
            ProcessHandle process = ProcessHandle.of(owner.pid).orElse(null);
            if (process != null && process.isAlive()) {
                ProcessHandle.Info identity = process.info();
                String command = identity.command().orElse("");
                require(!command.isEmpty() && Files.isSameFile(bin.resolve("postgres.exe"), Path.of(command)),
                        "Cleanup refused unexpected executable for PID " + owner.pid);
                long launched = identity.startInstant().orElse(Instant.EPOCH).getEpochSecond();
                require(launched >= owner.startedAt - 5 && launched <= owner.startedAt + 1,
                        "Cleanup refused a PID with inconsistent startup time: " + owner.pid);
                nativeCommand("pg_ctl", 35, null, "stop", "-D", cluster.data.toString(), "-m", "fast", "-w", "-t", "25");
                require(!process.isAlive() && !Files.exists(pidFile), "Postmaster remained after pg_ctl stop");
                System.out.printf("STOPPED %s postmaster=%d%n", cluster.data.getFileName(), owner.pid);
            }
        }
        NativeResult status = invoke("pg_ctl", 8, null, "status", "-D", cluster.data.toString());
        require(status.exitCode == 3, "pg_ctl must report no server, see " + status.log);
        requirePortFree(cluster.port);
        System.out.printf("PASS %s stopped, pg_ctl status=3 and loopback port=%d free%n", cluster.data.getFileName(), cluster.port);
    }

    private void cleanup() throws Exception {
        cleanup(List.of(restore, proof, workflow));
    }

    private void cleanup(List<Cluster> clusters) throws Exception {
        List<Exception> failures = new ArrayList<>();
        for (Cluster cluster : clusters) {
            try {
                Files.deleteIfExists(initPasswordFile(cluster));
                if (Files.exists(cluster.data.resolve("PG_VERSION"), LinkOption.NOFOLLOW_LINKS)) {
                    stop(cluster);
                }
            } catch (Exception e) {
                failures.add(e);
                System.err.println("B04 cleanup " + cluster.data.getFileName() + ": " + e);
            }
        }
        require(failures.isEmpty(), "B04 cleanup incomplete; check the exact run's cleanup diagnostics");
    }

    private Path initPasswordFile(Cluster cluster) {
        return run.resolve(cluster.data.getFileName() + "-init-password");
    }

    private static void assertClusterPath(Cluster cluster) throws IOException {
        require(cluster.data.equals(cluster.data.toRealPath())
                && cluster.data.resolve("PG_VERSION").equals(cluster.data.resolve("PG_VERSION").toRealPath()),
                "Cluster paths must refer to an owned B04 directory without links");
    }

    private static int postmasterPid(Cluster cluster) throws IOException {
        return postmaster(cluster).pid;
    }

    private static Postmaster postmaster(Cluster cluster) throws IOException {
        Path pidFile = cluster.data.resolve("postmaster.pid");
        require(pidFile.equals(pidFile.toRealPath()), "Linked postmaster PID file");
        List<String> lines = Files.readAllLines(pidFile);
        require(lines.size() >= 4 && Path.of(lines.get(1)).equals(cluster.data) && lines.get(3).equals("" + cluster.port),
                "Postmaster PID file does not match B04 directory and port");
        int pid = Integer.parseInt(lines.get(0));
        require(pid > 0, "Invalid postmaster PID");
        return new Postmaster(pid, Long.parseLong(lines.get(2)));
    }

    private static void requirePortFree(int port) throws IOException {
        try (ServerSocketChannel socket = ServerSocketChannel.open()) {
            socket.setOption(StandardSocketOptions.SO_REUSEADDR, false);
            socket.bind(new InetSocketAddress(HOST, port));
        }
    }

    private NativeResult nativeCommand(String tool, int timeout, String password, String... args) throws Exception {
        NativeResult result = invoke(tool, timeout, password, args);
        require(result.exitCode == 0, tool + " exit " + result.exitCode + ", see " + result.log);
        return result;
    }

    private NativeResult invoke(String tool, int timeout, String password, String... args) throws Exception {
        Path log = Files.createTempFile(run, "%03d-%s-".formatted(++commandNumber, tool), ".log");
        List<String> command = new ArrayList<>();
        command.add(bin.resolve(tool + ".exe").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(run.toFile());
        builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("PG"));
        builder.environment().put("PGCONNECT_TIMEOUT", "5");
        builder.environment().put("LC_ALL", "C");
        if (password != null) {
            builder.environment().put("PGPASSWORD", password);
        }
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IOException(tool + " exceeded " + timeout + "s, see " + log);
        }
        return new NativeResult(process.exitValue(), log);
    }

    private static void require(boolean assertion, String message) {
        if (!assertion) {
            throw new IllegalStateException(message);
        }
    }

    private record Cluster(Path data, int port, String database, Role admin) { }

    private record Postmaster(int pid, long startedAt) { }

    private record Role(String name, String password) {
        Role(String name) {
            this(name, Base64.getUrlEncoder().withoutPadding().encodeToString(RANDOM.generateSeed(32)));
        }
    }

    private record NativeResult(int exitCode, Path log) {
        String read() throws IOException {
            return Files.readString(log);
        }
    }
}
