package com.company.requests.bootstrap;

import com.company.requests.application.OperationSecrets;
import com.company.requests.application.WriterEpoch;
import com.company.requests.bootstrap.B32Database.Store;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Adds only fixture Settings to the existing B08 listening application. */
public final class B32Worker {
    private B32Worker() { }

    public static void main(String[] args) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            if (args.length != 2 || !args[1].matches("b32-[a-z][a-z0-9-]{0,18}")) {
                throw new IllegalArgumentException("Expected owned scratch and B32 app name.");
            }
            Path root = Path.of(args[0]).toRealPath();
            Path controls = root.resolve("control").toRealPath();
            Path scratch = Files.createDirectory(root.resolve("http"));
            var gates = new B32Gate(controls);
            var command = new B32DataSource(Store.WORKFLOW, args[1], field(input), gates);
            var proof = new B32DataSource(Store.PROOF, args[1], field(input), gates);
            var writer = new WriterEpoch(UUID.fromString(field(input)), Long.parseLong(field(input)));
            var secrets = new OperationSecrets(1, Map.of(1, secret(input)), 1, Map.of(1, secret(input)));
            var actors = B08SyntheticScope.actors(field(input), field(input), field(input), field(input));
            var settings = new SyntheticCreateConfiguration.Settings(command, proof, B08SyntheticScope.POLICY,
                    writer, actors, () -> clock(controls), secrets);
            try (var connection = command.getConnection()) {
                B32Database.read(connection, Store.WORKFLOW).save(controls.resolve("workflow.identity"));
            }
            try (var connection = proof.getConnection()) {
                B32Database.read(connection, Store.PROOF).save(controls.resolve("proof.identity"));
            }
            var serve = SyntheticHttpMain.class.getDeclaredMethod("serve", int.class, Path.class,
                    SyntheticCreateConfiguration.Settings.class, BufferedReader.class);
            serve.setAccessible(true);
            serve.invoke(null, 0, scratch, settings, input);
        } catch (Exception | AssertionError failure) {
            Throwable actual = failure instanceof java.lang.reflect.InvocationTargetException invocation
                    ? invocation.getCause() : failure;
            System.err.println("B32 worker failed (" + actual.getClass().getSimpleName() + ") at "
                    + actual.getStackTrace()[0]);
            System.exit(1);
        }
    }

    private static Instant clock(Path control) {
        try {
            return Instant.parse(Files.readString(control.resolve("trusted-clock")));
        } catch (IOException failure) {
            throw new IllegalStateException("B32 fixture clock unavailable.");
        }
    }

    private static String field(BufferedReader input) throws IOException {
        String line = input.readLine();
        if (line == null || line.isBlank()) {
            throw new IOException("Missing B32 fixture field.");
        }
        return line;
    }

    private static byte[] secret(BufferedReader input) throws IOException {
        byte[] bytes = Base64.getUrlDecoder().decode(field(input));
        if (bytes.length != 32) {
            throw new IOException("Invalid B32 fixture secret length.");
        }
        return bytes;
    }
}
