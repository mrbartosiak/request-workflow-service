package com.company.requests.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The v1 AES-256-GCM envelope carries exact binding, issuance and expiry proof. */
public final class ObservationCodec {
    static final Duration WINDOW = Duration.ofMinutes(15);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String FORMAT = "obs1";
    private static final String PURPOSE = "requests.action-context.observation.v1\0";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int DIGEST_BYTES = 32;
    private static final int INSTANT_BYTES = Long.BYTES + Integer.BYTES;
    private static final int CLAIM_BYTES = DIGEST_BYTES + 2 * INSTANT_BYTES;
    private static final int SEALED_BYTES = NONCE_BYTES + CLAIM_BYTES + TAG_BYTES;
    private static final int ENCODED_BYTES = (SEALED_BYTES * 8 + 5) / 6;
    private static final Pattern SYNTAX =
            Pattern.compile("obs1\\.([1-9][0-9]{0,9})\\.([A-Za-z0-9_-]{" + ENCODED_BYTES + "})");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final Keys keys;
    private final SecureRandom random = new SecureRandom();

    public ObservationCodec(Keys keys, OperationSecrets operations) {
        this.keys = Objects.requireNonNull(keys, "observation keys");
        if (keys.entries.values().stream().anyMatch(key -> operations.containsMaterial(key.getEncoded()))) {
            throw new IllegalArgumentException("Observation keys must be independently provisioned.");
        }
    }

    /**
     * Install one issuing key and any retained verification keys. The provisioning owner keeps
     * IDs immutable, rotates before 2^32 aggregate seals under an AES key, and retains the
     * retiring key through the last observation's validity window. Never restore retired keys to issuance.
     */
    public static final class Keys {
        private final int active;
        private final Map<Integer, SecretKeySpec> entries;

        public Keys(int active, Map<Integer, byte[]> supplied) {
            if (active < 1 || supplied == null || !supplied.containsKey(active)) {
                throw invalidKeys();
            }
            this.active = active;
            var copied = new HashMap<Integer, SecretKeySpec>();
            var distinct = new HashSet<SecretKeySpec>();
            for (var entry : supplied.entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 1
                        || entry.getValue() == null || entry.getValue().length != 32) {
                    throw invalidKeys();
                }
                var key = new SecretKeySpec(entry.getValue(), "AES");
                if (!distinct.add(key)) {
                    throw invalidKeys();
                }
                copied.put(entry.getKey(), key);
            }
            entries = Map.copyOf(copied);
        }

        private static IllegalArgumentException invalidKeys() {
            return new IllegalArgumentException("Invalid observation key configuration.");
        }

        boolean containsMaterial(byte[] material) {
            return entries.containsValue(new SecretKeySpec(material, "AES"));
        }

        @Override
        public String toString() {
            return "ObservationKeys[protected]";
        }
    }

    enum Verification { MATCH, MALFORMED, STALE }

    String seal(byte[] binding, Instant issuedAt) {
        if (binding.length != DIGEST_BYTES) {
            throw new IllegalArgumentException("An observation requires a SHA-256 binding.");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String header = FORMAT + "." + keys.active + ".";
        var claims = ByteBuffer.allocate(CLAIM_BYTES).put(binding);
        putInstant(claims, issuedAt);
        putInstant(claims, issuedAt.plus(WINDOW));
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, keys.entries.get(keys.active), nonce, header)
                    .doFinal(claims.array());
            return header + ENCODER.encodeToString(ByteBuffer.allocate(SEALED_BYTES).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    Verification verify(String token, byte[] binding, Instant now) {
        if (token == null || token.length() > FORMAT.length() + 12 + ENCODED_BYTES) {
            return Verification.MALFORMED;
        }
        var format = SYNTAX.matcher(token);
        if (!format.matches()) {
            return Verification.MALFORMED;
        }
        int id;
        try {
            id = Integer.parseInt(format.group(1));
        } catch (NumberFormatException outOfRange) {
            return Verification.MALFORMED;
        }
        String encoded = format.group(2);
        byte[] envelope = DECODER.decode(encoded);
        if (envelope.length != SEALED_BYTES || !ENCODER.encodeToString(envelope).equals(encoded)) {
            return Verification.MALFORMED;
        }
        var key = keys.entries.get(id);
        if (key == null) {
            return Verification.STALE;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        ByteBuffer.wrap(envelope).get(nonce);
        try {
            var plaintext = cipher(Cipher.DECRYPT_MODE, key, nonce, token.substring(0, format.start(2)))
                    .doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
            return checkClaims(plaintext, binding, now);
        } catch (AEADBadTagException tampered) {
            return Verification.STALE;
        } catch (GeneralSecurityException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private static Verification checkClaims(byte[] plaintext, byte[] binding, Instant now) {
        var claims = ByteBuffer.wrap(plaintext);
        byte[] actualBinding = new byte[DIGEST_BYTES];
        claims.get(actualBinding);
        try {
            var issued = getInstant(claims);
            var expires = getInstant(claims);
            if (MessageDigest.isEqual(binding, actualBinding) && issued.plus(WINDOW).equals(expires)
                    && !now.isBefore(issued) && now.isBefore(expires)) {
                return Verification.MATCH;
            }
        } catch (DateTimeException | ArithmeticException invalidClaims) {
            return Verification.STALE;
        }
        return Verification.STALE;
    }

    private static Cipher cipher(int mode, SecretKeySpec key, byte[] nonce, String header)
            throws GeneralSecurityException {
        var cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * Byte.SIZE, nonce));
        cipher.updateAAD((PURPOSE + header).getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    private static void putInstant(ByteBuffer output, Instant value) {
        output.putLong(value.getEpochSecond()).putInt(value.getNano());
    }

    private static Instant getInstant(ByteBuffer input) {
        long seconds = input.getLong();
        int nanos = input.getInt();
        if (nanos < 0 || nanos >= 1_000_000_000) {
            throw new DateTimeException("Noncanonical observation time.");
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
