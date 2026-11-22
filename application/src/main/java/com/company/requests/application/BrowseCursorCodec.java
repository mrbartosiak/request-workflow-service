package com.company.requests.application;

import com.company.requests.application.RequestBrowseQuery.Selection;
import com.company.requests.application.RequestBrowseStore.Candidate;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.RequestRef;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Purpose-separated AES-256-GCM continuation for the live browse. */
public final class BrowseCursorCodec {
    private static final String FORMAT = "browse1";
    private static final String PURPOSE = "requests.browse.cursor.v1\0";
    private static final byte ORDER_VERSION = 1;
    private static final int MAX_TOKEN_BYTES = 2048;
    private static final int MAX_REF_BYTES = 128;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int INSTANT_BYTES = Long.BYTES + Integer.BYTES;
    private static final int FIXED_CLAIM_BYTES = 3 * Long.BYTES + 2 * INSTANT_BYTES;
    private static final Pattern SYNTAX = Pattern.compile("browse1\\.([1-9][0-9]{0,9})\\.([A-Za-z0-9_-]+)");
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();

    private final Keys keys;
    private final SecureRandom random = new SecureRandom();

    public BrowseCursorCodec(Keys keys, OperationSecrets operations, Optional<ObservationCodec.Keys> observations) {
        this.keys = Objects.requireNonNull(keys);
        if (keys.entries.values().stream().anyMatch(key -> operations.containsMaterial(key.getEncoded())
                || observations.filter(configured -> configured.containsMaterial(key.getEncoded())).isPresent())) {
            throw new IllegalArgumentException("Browse cursor keys must be independently provisioned.");
        }
    }

    /** Provision distinct keys and immutable IDs. Retain a verification key until its last cursor expires. */
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
            return new IllegalArgumentException("Invalid browse cursor key configuration.");
        }

        @Override
        public String toString() {
            return "BrowseCursorKeys[protected]";
        }
    }

    public static final class InvalidCursor extends RuntimeException {
        InvalidCursor() {
            super("Invalid browse cursor.");
        }
    }

    record Window(Instant issued, Instant expires) {
        private static final Duration LIFETIME = Duration.ofMinutes(15);

        static Window starting(Instant now) {
            return new Window(now, now.plus(LIFETIME));
        }

        boolean contains(Instant now) {
            return !now.isBefore(issued) && now.isBefore(expires);
        }
    }

    record Position(long generation, Candidate below, Window window) {
        @Override
        public String toString() {
            return "BrowseCursorPosition[protected]";
        }
    }

    String seal(AuthenticatedActor actor, Selection selection, Position position) {
        byte[] prefix = binding(actor, selection);
        byte[] ref = ExactOperationText.encode(position.below().ref().value(), MAX_REF_BYTES);
        var claims = ByteBuffer.allocate(prefix.length + FIXED_CLAIM_BYTES + ref.length)
                .put(prefix).putLong(position.generation())
                .putLong(position.below().order().epoch()).putLong(position.below().order().ordinal());
        putInstant(claims, position.window().issued());
        putInstant(claims, position.window().expires());
        claims.put(ref);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String header = FORMAT + "." + keys.active + ".";
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, keys.entries.get(keys.active), nonce, header)
                    .doFinal(claims.array());
            byte[] envelope = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
            return header + ENCODE.encodeToString(envelope);
        } catch (GeneralSecurityException unavailable) {
            throw new AuthorityUnavailableException();
        }
    }

    Position open(String token, AuthenticatedActor actor, Selection selection) {
        if (token.length() > MAX_TOKEN_BYTES) {
            throw new InvalidCursor();
        }
        var syntax = SYNTAX.matcher(token);
        if (!syntax.matches()) {
            throw new InvalidCursor();
        }
        try {
            var key = keys.entries.get(Integer.parseInt(syntax.group(1)));
            String encoded = syntax.group(2);
            byte[] envelope = DECODE.decode(encoded);
            byte[] expected = binding(actor, selection);
            if (key == null || envelope.length < NONCE_BYTES + TAG_BYTES + expected.length + FIXED_CLAIM_BYTES + 1
                    || envelope.length > NONCE_BYTES + TAG_BYTES + expected.length + FIXED_CLAIM_BYTES + MAX_REF_BYTES
                    || !ENCODE.encodeToString(envelope).equals(encoded)) {
                throw new InvalidCursor();
            }
            byte[] nonce = new byte[NONCE_BYTES];
            ByteBuffer.wrap(envelope).get(nonce);
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, key, nonce, token.substring(0, syntax.start(2)))
                    .doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
            return position(plaintext, expected);
        } catch (AEADBadTagException | IllegalArgumentException | BufferUnderflowException
                 | DateTimeException | ArithmeticException | CharacterCodingException invalid) {
            throw new InvalidCursor();
        } catch (GeneralSecurityException unavailable) {
            throw new AuthorityUnavailableException();
        }
    }

    private static Position position(byte[] plaintext, byte[] expected) throws CharacterCodingException {
        var claims = ByteBuffer.wrap(plaintext);
        byte[] actual = new byte[expected.length];
        claims.get(actual);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidCursor();
        }
        long generation = claims.getLong();
        var order = new CreationOrder(claims.getLong(), claims.getLong());
        var window = new Window(getInstant(claims), getInstant(claims));
        if (generation < 1 || !Window.starting(window.issued()).equals(window)) {
            throw new InvalidCursor();
        }
        String ref = StandardCharsets.UTF_8.newDecoder().decode(claims).toString();
        ExactOperationText.encode(ref, MAX_REF_BYTES);
        return new Position(generation, new Candidate(order, new RequestRef(ref)), window);
    }

    private static byte[] binding(AuthenticatedActor actor, Selection selection) {
        byte[] actorScope = actorScope(actor);
        byte[] name = selection.name().map(value -> ExactOperationText.encode(value, 256)).orElseGet(() -> new byte[0]);
        byte[] state = selection.state().map(value -> value.name().getBytes(StandardCharsets.US_ASCII))
                .orElseGet(() -> new byte[0]);
        return ByteBuffer.allocate(1 + actorScope.length + Short.BYTES + name.length + 1 + state.length + 1)
                .put(ORDER_VERSION).put(actorScope).putShort((short) name.length).put(name)
                .put((byte) state.length).put(state).put((byte) selection.size()).array();
    }

    private static byte[] actorScope(AuthenticatedActor actor) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {actor.issuer(), actor.organization().value(), actor.principal(),
                    actor.kind().name(), actor.person().map(person -> person.value()).orElse("")}) {
                byte[] exact = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(exact.length).array());
                digest.update(exact);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AuthorityUnavailableException();
        }
    }

    private static Cipher cipher(int mode, SecretKeySpec key, byte[] nonce, String header)
            throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
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
            throw new DateTimeException("Noncanonical cursor time.");
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }
}
