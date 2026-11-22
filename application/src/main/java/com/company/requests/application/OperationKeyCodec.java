package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static com.company.requests.application.OperationKeyException.Reason.INVALID;

/** Encodes only the operation-key purpose and authenticates the exact payload before parsing claims. */
final class OperationKeyCodec {
    private static final String PREFIX = "opk1";
    private static final Pattern TOKEN = Pattern.compile(PREFIX + "\\.([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]{43})");
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int MAX_TOKEN_LENGTH = 1024;
    private static final int SCOPE_FIELD_BYTES = 224;
    static final int TOKEN_ID_BYTES = 16;
    private static final int INSTANT_BYTES = Long.BYTES + Integer.BYTES;
    private static final int FIXED_PAYLOAD_BYTES =
            Integer.BYTES + TOKEN_ID_BYTES + Long.BYTES + 2 * INSTANT_BYTES + 3 * Short.BYTES;

    private final OperationSecrets secrets;

    OperationKeyCodec(OperationSecrets secrets) {
        this.secrets = secrets;
    }

    String encode(AuthenticatedActor actor, long generation, Instant issuedAt, Instant expiresAt, byte[] tokenId) {
        byte[] issuer;
        byte[] organization;
        byte[] principal;
        try {
            issuer = ExactOperationText.encode(actor.issuer(), SCOPE_FIELD_BYTES);
            organization = ExactOperationText.encode(actor.organization().value(), SCOPE_FIELD_BYTES);
            principal = ExactOperationText.encode(actor.principal(), SCOPE_FIELD_BYTES);
        } catch (IllegalArgumentException invalidDirectoryBinding) {
            throw new AuthorityUnavailableException();
        }
        int version = secrets.signingVersion();
        var payload = ByteBuffer.allocate(FIXED_PAYLOAD_BYTES + issuer.length + organization.length + principal.length);
        payload.putInt(version).put(tokenId).putLong(generation);
        putInstant(payload, issuedAt);
        putInstant(payload, expiresAt);
        putText(payload, issuer);
        putText(payload, organization);
        putText(payload, principal);
        byte[] bytes = payload.array();
        return PREFIX + "." + BASE64.encodeToString(bytes) + "."
                + BASE64.encodeToString(secrets.signingMac(version).doFinal(bytes));
    }

    Claims verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw invalid();
        }
        var match = TOKEN.matcher(token);
        if (!match.matches()) {
            throw invalid();
        }
        byte[] bytes = decodeCanonical(match.group(1));
        byte[] signature = decodeCanonical(match.group(2));
        if (bytes.length < FIXED_PAYLOAD_BYTES + 3 || bytes.length > FIXED_PAYLOAD_BYTES + 3 * SCOPE_FIELD_BYTES) {
            throw invalid();
        }
        var payload = ByteBuffer.wrap(bytes);
        var mac = secrets.signingMac(payload.getInt());
        if (mac == null || !MessageDigest.isEqual(mac.doFinal(bytes), signature)) {
            throw invalid();
        }
        return readClaims(payload);
    }

    private static Claims readClaims(ByteBuffer payload) {
        try {
            var tokenId = new byte[TOKEN_ID_BYTES];
            payload.get(tokenId);
            long generation = payload.getLong();
            Instant issuedAt = readInstant(payload);
            Instant expiresAt = readInstant(payload);
            String issuer = readText(payload);
            var organization = new OrganizationId(readText(payload));
            String principal = readText(payload);
            if (payload.hasRemaining() || generation < 1
                    || !expiresAt.equals(issuedAt.plus(OperationKeyIssuer.VALIDITY))) {
                throw invalid();
            }
            var key = new ReceiptKey(issuer, organization, principal, HexFormat.of().formatHex(tokenId));
            return new Claims(key, generation, issuedAt, expiresAt);
        } catch (BufferUnderflowException | CharacterCodingException | DateTimeException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static byte[] decodeCanonical(String value) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(value);
        } catch (IllegalArgumentException invalidBase64) {
            throw invalid();
        }
        if (!BASE64.encodeToString(bytes).equals(value)) {
            throw invalid();
        }
        return bytes;
    }

    private static void putText(ByteBuffer payload, byte[] text) {
        payload.putShort((short) text.length).put(text);
    }

    private static String readText(ByteBuffer payload) throws CharacterCodingException {
        int size = Short.toUnsignedInt(payload.getShort());
        if (size < 1 || size > SCOPE_FIELD_BYTES) {
            throw invalid();
        }
        byte[] text = new byte[size];
        payload.get(text);
        String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(text)).toString();
        ExactOperationText.encode(value, SCOPE_FIELD_BYTES);
        return value;
    }

    private static void putInstant(ByteBuffer payload, Instant instant) {
        payload.putLong(instant.getEpochSecond()).putInt(instant.getNano());
    }

    private static Instant readInstant(ByteBuffer payload) {
        long epochSecond = payload.getLong();
        int nano = payload.getInt();
        if (nano < 0 || nano >= 1_000_000_000) {
            throw invalid();
        }
        return Instant.ofEpochSecond(epochSecond, nano);
    }

    private static OperationKeyException invalid() {
        return new OperationKeyException(INVALID);
    }

    record Claims(ReceiptKey receiptKey, long generation, Instant issuedAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "OperationKeyClaims[protected]";
        }
    }
}
