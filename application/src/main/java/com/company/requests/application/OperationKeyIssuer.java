package com.company.requests.application;

import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static com.company.requests.application.OperationKeyException.Reason.EXPIRED;
import static com.company.requests.application.OperationKeyException.Reason.INVALID;

/** Authorized issue and verified retry claims. Callers own submitted access and receipt arbitration. */
public final class OperationKeyIssuer {
    public static final Duration VALIDITY = Duration.ofHours(24);
    public static final Duration REFERENCE_ADMISSION_MARGIN = Duration.ofSeconds(3);

    private final CurrentAccess access;
    private final TrustedTime trustedTime;
    private final OperationKeyCodec codec;
    private final Duration margin;
    private final SecureRandom random = new SecureRandom();

    public OperationKeyIssuer(CurrentAccess access, TrustedTime trustedTime, OperationSecrets secrets, Duration margin) {
        this.access = Objects.requireNonNull(access, "current access");
        this.trustedTime = Objects.requireNonNull(trustedTime, "trusted time");
        this.codec = new OperationKeyCodec(Objects.requireNonNull(secrets, "operation secrets"));
        this.margin = Objects.requireNonNull(margin, "admission margin");
        if (margin.isZero() || margin.isNegative() || margin.compareTo(VALIDITY) >= 0) {
            throw new IllegalArgumentException("Invalid operation effect-admission margin.");
        }
    }

    public IssuedKey issue(AuthenticatedActor actor) {
        return access.withKeyIssue(actor, admission -> issue(actor, admission));
    }

    IssuedKey issue(AuthenticatedActor actor, ScopeAuthority.Admission admission) {
        Instant issuedAt = now();
        Instant expiresAt;
        try {
            expiresAt = issuedAt.plus(VALIDITY);
        } catch (DateTimeException outOfRange) {
            throw new AuthorityUnavailableException();
        }
        var tokenId = new byte[OperationKeyCodec.TOKEN_ID_BYTES];
        random.nextBytes(tokenId);
        return new IssuedKey(codec.encode(actor, admission.writer().generation(), issuedAt, expiresAt, tokenId),
                expiresAt);
    }

    /** Use after proving submitted access with the same actor and current admission. */
    public VerifiedKey verify(String token, AuthenticatedActor actor, ScopeAuthority.Admission admission) {
        var claims = codec.verify(token);
        return new VerifiedKey(this, claims, eligibility(claims, actor, admission));
    }

    /** Reuse authenticated claims after obtaining fresh submitted access proof at this boundary. */
    public Eligibility recheck(VerifiedKey key, AuthenticatedActor actor, ScopeAuthority.Admission admission) {
        return eligibility(verifiedClaims(key), actor, admission);
    }

    /**
     * Release-time clock check for use beside a fresh submitted-access proof.
     * True before signed expiry regardless of the effect margin. An unavailable clock throws.
     */
    public boolean isFresh(VerifiedKey key) {
        var claims = verifiedClaims(key);
        return currentTime(claims).isBefore(claims.expiresAt());
    }

    /** Reads the occurrence time on the same trusted clock used for the signed cutoff. */
    Instant requireFreshInstant(VerifiedKey key) {
        var claims = verifiedClaims(key);
        var now = currentTime(claims);
        if (!now.isBefore(claims.expiresAt())) {
            throw new OperationKeyException(EXPIRED);
        }
        return now;
    }

    private Eligibility eligibility(OperationKeyCodec.Claims claims, AuthenticatedActor actor,
                                    ScopeAuthority.Admission admission) {
        ReceiptKey scope = claims.receiptKey();
        long currentGeneration = admission.writer().generation();
        if (!scope.issuer().equals(actor.issuer()) || !scope.organization().equals(actor.organization())
                || !scope.organization().equals(admission.genesis().organization())
                || !scope.principal().equals(actor.principal()) || claims.generation() > currentGeneration) {
            throw new OperationKeyException(INVALID);
        }
        Instant now = currentTime(claims);
        if (!now.isBefore(claims.expiresAt())) {
            throw new OperationKeyException(EXPIRED);
        }
        if (claims.generation() < currentGeneration) {
            return Eligibility.PAST_GENERATION_LOOKUP_ONLY;
        }
        return Duration.between(now, claims.expiresAt()).compareTo(margin) >= 0
                ? Eligibility.ACTIVE_CAN_EXECUTE : Eligibility.ACTIVE_LOOKUP_ONLY;
    }

    private OperationKeyCodec.Claims verifiedClaims(VerifiedKey key) {
        if (key == null || key.verifier != this) {
            throw new OperationKeyException(INVALID);
        }
        return key.claims;
    }

    private Instant currentTime(OperationKeyCodec.Claims claims) {
        Instant now = now();
        if (now.isBefore(claims.issuedAt())) {
            throw new AuthorityUnavailableException();
        }
        return now;
    }

    private Instant now() {
        try {
            Instant reading = trustedTime.now();
            if (reading != null) {
                return reading;
            }
        } catch (RuntimeException unavailable) {
            // Adapter diagnostics can contain protected configuration. Report only the safe outcome.
        }
        throw new AuthorityUnavailableException();
    }

    public enum Eligibility { ACTIVE_CAN_EXECUTE, ACTIVE_LOOKUP_ONLY, PAST_GENERATION_LOOKUP_ONLY }

    public record IssuedKey(String key, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedOperationKey[protected]";
        }
    }

    public static final class VerifiedKey {
        private final OperationKeyIssuer verifier;
        private final OperationKeyCodec.Claims claims;
        private final Eligibility eligibility;

        private VerifiedKey(OperationKeyIssuer verifier, OperationKeyCodec.Claims claims, Eligibility eligibility) {
            this.verifier = verifier;
            this.claims = claims;
            this.eligibility = eligibility;
        }

        public ReceiptKey receiptKey() {
            return claims.receiptKey();
        }

        public long generation() {
            return claims.generation();
        }

        public Instant issuedAt() {
            return claims.issuedAt();
        }

        public Instant expiresAt() {
            return claims.expiresAt();
        }

        /** Snapshot from verification. Recheck before making a later protected decision. */
        public Eligibility eligibility() {
            return eligibility;
        }

        @Override
        public String toString() {
            return "VerifiedOperationKey[protected]";
        }
    }
}
