package com.company.requests.bootstrap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Opt-in local sink used by the standalone synthetic HTTP profile. */
final class ProtectedLocalEvidenceSink implements ProtectedEvidenceSpool.Sink, AutoCloseable {
    private final Path directory;
    private final ProtectedEvidenceFiles files;
    private final FileChannel channel;
    private final FileLock ownership;

    ProtectedLocalEvidenceSink(Path directory, byte[] key) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw new IOException("A private local sink directory must already exist.");
        }
        this.directory = directory.toRealPath();
        files = new ProtectedEvidenceFiles(this.directory, key);
        channel = FileChannel.open(this.directory.resolve("sink.lock"), CREATE, WRITE, NOFOLLOW_LINKS);
        try {
            ownership = channel.tryLock();
            if (ownership == null) {
                throw new IOException("The protected local sink already has an owner.");
            }
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("The protected local sink already has an owner.");
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException release) {
                failure.addSuppressed(release);
            }
            throw failure;
        }
    }

    @Override
    public synchronized boolean acknowledge(ProtectedEvidenceSpool.Delivery delivery) throws IOException {
        String identity = String.format(Locale.ROOT, "%019d-%s-%s.record", delivery.sequence(),
                delivery.correlationId(), delivery.part());
        byte[] bytes = String.join("\n", delivery.records()).getBytes(UTF_8);
        if (Files.exists(directory.resolve(identity), NOFOLLOW_LINKS)) {
            return Arrays.equals(bytes, files.read(identity));
        }
        Files.deleteIfExists(directory.resolve(identity + ".tmp"));
        files.write(identity, bytes);
        return true;
    }

    @Override
    public void close() throws IOException {
        try (channel) {
            ownership.close();
        }
    }
}
