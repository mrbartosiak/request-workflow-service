package com.company.requests.integration;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.integration.InboundInbox.Scope;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Explicitly configured credentials for the synthetic source protocol. */
public final class SyntheticInboundSources {
    private static final String MAC = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private final Map<String, Route> byCredential;

    public record Route(String credential, byte[] secret, Scope scope, String identifierType, AuthenticatedActor actor) {
        public Route {
            InboundNotice.requireCoordinate(credential);
            InboundNotice.requireCoordinate(identifierType);
            secret = Objects.requireNonNull(secret).clone();
            Objects.requireNonNull(scope);
            if (secret.length != KEY_BYTES || Objects.requireNonNull(actor).kind() != AuthenticatedActor.Kind.SERVICE) {
                throw new IllegalArgumentException("Synthetic inbound routes require a 256-bit key and a service principal.");
            }
        }

        @Override public byte[] secret() { return secret.clone(); }

        @Override public String toString() { return "SyntheticInboundRoute[protected]"; }
    }

    public SyntheticInboundSources(Collection<Route> routes) {
        var byCredential = new HashMap<String, Route>();
        var scopes = new HashSet<Scope>();
        var secrets = new HashSet<SecretKeySpec>();
        for (var route : routes) {
            if (byCredential.putIfAbsent(route.credential(), route) != null || !scopes.add(route.scope())
                    || !secrets.add(new SecretKeySpec(route.secret, MAC))) {
                throw new IllegalArgumentException("Each synthetic inbound route requires unique credentials and scope.");
            }
        }
        this.byCredential = Map.copyOf(byCredential);
    }

    Optional<Route> verify(String credential, InboundNotice notice, byte[] proof) {
        if (credential == null || notice == null || proof == null || proof.length != KEY_BYTES) {
            return Optional.empty();
        }
        var route = byCredential.get(credential);
        if (route == null || !route.scope().sourceSystem().equals(notice.sourceSystem())
                || !route.scope().tenant().equals(notice.tenant())) {
            return Optional.empty();
        }
        try {
            var mac = Mac.getInstance(MAC);
            mac.init(new SecretKeySpec(route.secret, MAC));
            return MessageDigest.isEqual(mac.doFinal(signingBytes(credential, notice)), proof)
                    ? Optional.of(route) : Optional.empty();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Synthetic inbound authentication is unavailable.", unavailable);
        }
    }

    /** Bytes authenticated by the sender. The identity domain is selected by the verified route. */
    public static byte[] signingBytes(String credential, InboundNotice notice) {
        InboundNotice.requireCoordinate(credential);
        return encoded(out -> {
            out.writeUTF("requests.inbound.notice.v1");
            out.writeUTF(credential);
            out.writeUTF(notice.sourceSystem());
            out.writeUTF(notice.tenant());
            writeIdentity(out, notice.sourceIdentity());
            out.writeUTF(notice.type().name());
            out.writeLong(notice.sourceVersion());
            out.writeLong(notice.targetRevision());
            writeIdentity(out, notice.externalId());
            out.writeUTF(notice.hint().name());
        });
    }

    /** Type, versions and the resolved target are independently compared as receipt fields. */
    static String semanticDigest(Route route, InboundNotice notice) {
        byte[] semantic = encoded(out -> {
            out.writeUTF("requests.inbound.semantic.v1");
            out.writeUTF(route.identifierType());
            writeIdentity(out, notice.externalId());
            out.writeUTF(notice.hint().name());
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(semantic));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Synthetic inbound semantic digest is unavailable.", unavailable);
        }
    }

    private static byte[] encoded(Encoding encoding) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            encoding.write(out);
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode the bounded synthetic notice.", impossible);
        }
        return bytes.toByteArray();
    }

    private static void writeIdentity(DataOutputStream out, InboundNotice.Identity identity) throws IOException {
        byte[] bytes = identity.bytes();
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    @FunctionalInterface
    private interface Encoding {
        void write(DataOutputStream out) throws IOException;
    }
}
