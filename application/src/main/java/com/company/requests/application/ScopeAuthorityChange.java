package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.WriterLineage.Identity;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal scope-proof coordinates. The journal protects the exact, canonically encoded delta. */
public final class ScopeAuthorityChange {
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

    private ScopeAuthorityChange() { }

    public static final class Delta {
        private final byte[] bytes;
        private final String digest;

        public Delta(byte[] exactBytes) {
            if (exactBytes.length == 0) {
                throw new IllegalArgumentException("Scope delta must be present.");
            }
            bytes = exactBytes.clone();
            try {
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 unavailable", failure);
            }
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public String digest() {
            return digest;
        }
    }

    /** The full workflow transaction ID is meaningful only on the certified writer lineage. */
    public record Attempt(UUID id, Admission admission, long beforeVersion, long afterVersion,
                          String deltaDigest, String workflowTransaction) {
        public Attempt {
            Objects.requireNonNull(id, "scope attempt ID");
            Objects.requireNonNull(admission, "scope admission");
            if (beforeVersion < 1 || afterVersion <= beforeVersion || afterVersion - beforeVersion != 1
                    || !DIGEST.matcher(deltaDigest).matches()) {
                throw new IllegalArgumentException("Invalid scope version or digest.");
            }
            long transactionId = Long.parseUnsignedLong(workflowTransaction);
            if (transactionId == 0 || !Long.toUnsignedString(transactionId).equals(workflowTransaction)) {
                throw new IllegalArgumentException("Invalid workflow transaction ID.");
            }
        }
    }

    public record LocalAttempt(Attempt attempt, Identity writer) {
        public LocalAttempt {
            Objects.requireNonNull(attempt, "scope attempt");
            Objects.requireNonNull(writer, "workflow writer");
        }
    }

    public enum State { REGISTERED, PENDING, ABORTED, COMMITTED }

    public record Entry(Attempt attempt, OptionalLong sequence, State state) {
        public Entry {
            Objects.requireNonNull(attempt, "scope attempt");
            Objects.requireNonNull(sequence, "scope sequence");
            Objects.requireNonNull(state, "scope state");
            if (sequence.isPresent() ? sequence.getAsLong() < 1 || state == State.REGISTERED
                    : state == State.PENDING || state == State.COMMITTED) {
                throw new IllegalArgumentException("Invalid scope journal position.");
            }
        }
    }
}
