package com.company.requests.integration;

import java.util.Arrays;
import java.util.Objects;

/** Typed input for the local synthetic reconciliation protocol. */
public record InboundNotice(String sourceSystem, String tenant, Identity sourceIdentity,
                            Type type, Identity externalId, long sourceVersion,
                            long targetRevision, Hint hint) {
    public enum Type { STATUS_CHANGED, DETAILS_CHANGED }

    public enum Hint { CHANGE, CORRECTION }

    public InboundNotice {
        requireCoordinate(sourceSystem);
        requireCoordinate(tenant);
        Objects.requireNonNull(sourceIdentity);
        Objects.requireNonNull(type);
        Objects.requireNonNull(externalId);
        Objects.requireNonNull(hint);
        if (sourceVersion < 1 || targetRevision < 1) {
            throw new IllegalArgumentException("Inbound versions must be positive.");
        }
    }

    /** Binary provider identity, with no normalization or text conversion. */
    public record Identity(byte[] bytes) {
        public Identity {
            bytes = Objects.requireNonNull(bytes).clone();
            if (bytes.length < 1 || bytes.length > 256) {
                throw new IllegalArgumentException("An inbound identity must contain 1 to 256 bytes.");
            }
        }

        @Override public byte[] bytes() { return bytes.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && Arrays.equals(bytes, identity.bytes);
        }

        @Override public int hashCode() { return Arrays.hashCode(bytes); }

        @Override public String toString() { return "InboundIdentity[protected]"; }
    }

    static void requireCoordinate(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid synthetic inbound coordinate.");
        }
    }

    @Override public String toString() { return "InboundNotice[protected]"; }
}
