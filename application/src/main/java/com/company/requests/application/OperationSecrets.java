package com.company.requests.application;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent signing and digest keys, indexed by immutable version within each purpose. */
public final class OperationSecrets {
    private static final String HMAC = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final byte[] SIGNING_PURPOSE =
            "requests.operation-key.v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIGEST_PURPOSE =
            "requests.intent-digest.v1\0".getBytes(StandardCharsets.US_ASCII);

    private final int signingVersion;
    private final int digestVersion;
    private final Map<Integer, SecretKeySpec> signingKeys;
    private final Map<Integer, SecretKeySpec> digestKeys;

    public OperationSecrets(int signingVersion, Map<Integer, byte[]> signingKeys,
                            int digestVersion, Map<Integer, byte[]> digestKeys) {
        var distinct = new HashSet<SecretKeySpec>();
        this.signingKeys = copyKeys(signingKeys, signingVersion, distinct);
        this.digestKeys = copyKeys(digestKeys, digestVersion, distinct);
        this.signingVersion = signingVersion;
        this.digestVersion = digestVersion;
    }

    int signingVersion() {
        return signingVersion;
    }

    int digestVersion() {
        return digestVersion;
    }

    Mac signingMac(int version) {
        var key = signingKeys.get(version);
        return key == null ? null : mac(key, SIGNING_PURPOSE);
    }

    Mac digestMac(int version) {
        var key = digestKeys.get(version);
        if (key == null) {
            throw new AuthorityUnavailableException();
        }
        return mac(key, DIGEST_PURPOSE);
    }

    private static Map<Integer, SecretKeySpec> copyKeys(Map<Integer, byte[]> supplied, int active,
                                                        Set<SecretKeySpec> distinct) {
        if (supplied == null || active < 1 || !supplied.containsKey(active)) {
            throw invalidConfiguration();
        }
        var keys = new HashMap<Integer, SecretKeySpec>();
        for (var entry : supplied.entrySet()) {
            byte[] material = entry.getValue();
            if (entry.getKey() == null || entry.getKey() < 1 || material == null || material.length != KEY_BYTES) {
                throw invalidConfiguration();
            }
            // SecretKeySpec makes a defensive copy of material.
            var key = new SecretKeySpec(material, HMAC);
            if (!distinct.add(key)) {
                throw invalidConfiguration();
            }
            keys.put(entry.getKey(), key);
        }
        return Map.copyOf(keys);
    }

    private static Mac mac(SecretKeySpec key, byte[] purpose) {
        try {
            var mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(purpose);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException failure) {
            throw new IllegalStateException("Operation cryptography is unavailable.");
        }
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Invalid operation key configuration.");
    }

    @Override
    public String toString() {
        return "OperationSecrets[protected]";
    }
}
