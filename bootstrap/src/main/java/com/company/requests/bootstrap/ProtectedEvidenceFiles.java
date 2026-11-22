package com.company.requests.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** Fixed-size, authenticated local files. The file name binds a frame to its spool position. */
final class ProtectedEvidenceFiles {
    static final int FRAME_BYTES = 4096;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int PLAINTEXT_BYTES = FRAME_BYTES - NONCE_BYTES - TAG_BYTES;

    private final Path root;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    ProtectedEvidenceFiles(Path root, byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("Protected evidence requires a 256-bit local key.");
        }
        this.root = root;
        this.key = new SecretKeySpec(key, "AES");
    }

    byte[] read(String name) throws IOException {
        var file = root.resolve(name);
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) != FRAME_BYTES) {
            throw new IOException("Invalid protected evidence file size or type.");
        }
        byte[] sealed;
        try (var input = Files.newInputStream(file)) {
            sealed = input.readNBytes(FRAME_BYTES + 1);
        }
        if (sealed.length != FRAME_BYTES) {
            throw new IOException("Invalid protected evidence frame size.");
        }
        try {
            var nonce = Arrays.copyOf(sealed, NONCE_BYTES);
            byte[] plain = cipher(Cipher.DECRYPT_MODE, nonce, name).doFinal(sealed, NONCE_BYTES,
                    FRAME_BYTES - NONCE_BYTES);
            var content = ByteBuffer.wrap(plain);
            int length = content.getInt();
            if (length < 0 || length > content.remaining()) {
                throw new IOException("Invalid protected evidence frame length.");
            }
            var bytes = new byte[length];
            content.get(bytes);
            return bytes;
        } catch (GeneralSecurityException failure) {
            throw new IOException("Protected evidence authentication failed.");
        }
    }

    void write(String name, byte[] bytes) throws IOException {
        // A caller cancellation still needs its terminal audit frame.
        boolean interrupted = Thread.interrupted();
        try {
            writeFrame(name, bytes);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeFrame(String name, byte[] bytes) throws IOException {
        if (bytes.length > PLAINTEXT_BYTES - Integer.BYTES) {
            throw new IOException("Protected evidence exceeds its reserved frame.");
        }
        var plain = ByteBuffer.allocate(PLAINTEXT_BYTES).putInt(bytes.length).put(bytes);
        var nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed;
        try {
            sealed = cipher(Cipher.ENCRYPT_MODE, nonce, name).doFinal(plain.array());
        } catch (GeneralSecurityException failure) {
            throw new IOException("Protected evidence encryption failed.");
        }
        var temporary = root.resolve(name + ".tmp");
        try {
            try (var file = FileChannel.open(temporary, CREATE_NEW, WRITE)) {
                var buffer = ByteBuffer.allocate(FRAME_BYTES).put(nonce).put(sealed).flip();
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                file.force(true);
            }
            Files.move(temporary, root.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String name) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * Byte.SIZE, nonce));
        cipher.updateAAD(("requests-evidence-v1/" + name).getBytes(US_ASCII));
        return cipher;
    }
}
