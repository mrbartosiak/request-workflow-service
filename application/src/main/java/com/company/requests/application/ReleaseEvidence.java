package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Private release evidence for a correlated committed result. */
public final class ReleaseEvidence {
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private ReleaseEvidence() { }

    /** Scoped correlation and digest of the exact immutable result tuple. */
    public record Correlation(UUID id, OrganizationId organization, String resultDigest) {
        public Correlation {
            Objects.requireNonNull(id, "release correlation");
            Objects.requireNonNull(organization, "release scope");
            if (resultDigest == null || !DIGEST.matcher(resultDigest).matches()) {
                throw new IllegalArgumentException("Invalid release evidence digest.");
            }
        }

        @Override
        public String toString() {
            return "ReleaseCorrelation[protected]";
        }
    }

    /** Authorized enqueue evidence input. Call only after the accepted slot is drainable. */
    @FunctionalInterface
    public interface Handoffs {
        void record(Correlation correlation);
    }

    /** Independently authorized input from a source that verified the client received this exact result. */
    @FunctionalInterface
    public interface Acknowledgments {
        void record(Correlation correlation);
    }

    public enum Handoff { PROVED, UNKNOWN }
    public enum ClientReceipt { CONFIRMED, UNKNOWN }
    public enum RestoredEffect { VERIFIED_PRESENT, VERIFIED_MISSING, UNKNOWN }

    /** Preparation is present for these states. Absence of a record is reported by the reader separately. */
    public record PreparedStatus(Handoff handoff, ClientReceipt clientReceipt) {
        public PreparedStatus {
            Objects.requireNonNull(handoff, "handoff evidence state");
            Objects.requireNonNull(clientReceipt, "client receipt evidence state");
        }

        public boolean lostAcknowledged(RestoredEffect effect) {
            return effect == RestoredEffect.VERIFIED_MISSING && clientReceipt == ClientReceipt.CONFIRMED;
        }
    }
}
