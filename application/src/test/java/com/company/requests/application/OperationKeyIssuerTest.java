package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static com.company.requests.application.OperationKeyException.Reason.EXPIRED;
import static com.company.requests.application.OperationKeyException.Reason.INVALID;
import static com.company.requests.application.OperationKeyIssuer.Eligibility.ACTIVE_CAN_EXECUTE;
import static com.company.requests.application.OperationKeyIssuer.Eligibility.ACTIVE_LOOKUP_ONLY;
import static com.company.requests.application.OperationKeyIssuer.Eligibility.PAST_GENERATION_LOOKUP_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationKeyIssuerTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00.123456789Z");
    private static final Duration MARGIN = Duration.ofSeconds(3);
    private static final OrganizationId ORG = new OrganizationId("org-alpha");
    private static final AuthenticatedActor ACTOR = actor("issuer-a", ORG, "stable-principal");
    private static final int GENERATION = 4;
    private static final String SIGNING_PURPOSE = "requests.operation-key.v1\0";
    private static final String DIGEST_PURPOSE = "requests.intent-digest.v1\0";
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final OperationKeyIssuer keys = issuer(GENERATION, now::get);
    private final ScopeAuthority.Admission current = submittedAccess(ACTOR, GENERATION);

    @Test
    void issueReturnsAKeyOnlyForAnAuthorizedStablePrincipal() {
        var issued = keys.issue(ACTOR);
        assertNotNull(issued, "authorized issue must return a signed operation key");
        assertEquals(NOW.plus(Duration.ofHours(24)), issued.expiresAt());
        var verified = keys.verify(issued.key(), ACTOR, current);
        assertEquals(ACTOR.issuer(), verified.receiptKey().issuer());
        assertEquals(ACTOR.organization(), verified.receiptKey().organization());
        assertEquals(ACTOR.principal(), verified.receiptKey().principal());
        assertTrue(verified.receiptKey().tokenId().matches("[a-f0-9]{32}"));
        assertNotEquals(verified.receiptKey().tokenId(), keys.verify(keys.issue(ACTOR).key(), ACTOR, current)
                .receiptKey().tokenId());
        assertEquals(GENERATION, verified.generation());
        assertEquals(NOW, verified.issuedAt());
        assertEquals(issued.expiresAt(), verified.expiresAt());
        assertEquals(ACTIVE_CAN_EXECUTE, verified.eligibility());

        var timeReads = new AtomicInteger();
        TrustedTime clock = () -> {
            timeReads.incrementAndGet();
            return NOW;
        };
        var denied = new OperationKeyIssuer(access(ACTOR, false, GENERATION, ignored -> {}), clock, secrets(), MARGIN);
        assertThrows(AccessDeniedException.class, () -> denied.issue(ACTOR));
        var unproved = new OperationKeyIssuer(access(ACTOR, true, GENERATION, ignored -> {
            throw new AuthorityUnavailableException();
        }), clock, secrets(), MARGIN);
        assertThrows(AuthorityUnavailableException.class, () -> unproved.issue(ACTOR));
        assertEquals(0, timeReads.get());
    }

    @Test
    void keyScopeSurvivesChangesInGrantsAndActorAttributes() {
        var issued = keys.issue(ACTOR);
        var changedActor = new AuthenticatedActor(ACTOR.issuer(), ORG, ACTOR.principal(),
                AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("other-person")));
        var deniedIssue = new OperationKeyIssuer(access(changedActor, false, GENERATION, ignored -> {}),
                now::get, secrets(), MARGIN);
        assertThrows(AccessDeniedException.class, () -> deniedIssue.issue(changedActor));
        assertEquals(ACTIVE_CAN_EXECUTE, deniedIssue.verify(issued.key(), changedActor,
                submittedAccess(changedActor, GENERATION)).eligibility());
    }

    @Test
    void wrongIssuerOrganizationPrincipalAndAdmissionScopeAreInvalid() {
        var issued = keys.issue(ACTOR);
        for (var wrongActor : List.of(actor("issuer-b", ORG, ACTOR.principal()),
                actor(ACTOR.issuer(), new OrganizationId("org-beta"), ACTOR.principal()),
                actor(ACTOR.issuer(), ORG, "other-principal"))) {
            assertInvalid(() -> keys.verify(issued.key(), wrongActor, submittedAccess(wrongActor, GENERATION)));
        }
        var unrelated = actor(ACTOR.issuer(), new OrganizationId("org-beta"), ACTOR.principal());
        assertInvalid(() -> keys.verify(issued.key(), ACTOR, submittedAccess(unrelated, GENERATION)));
        var verified = keys.verify(issued.key(), ACTOR, current);
        assertInvalid(() -> keys.recheck(verified, unrelated, submittedAccess(unrelated, GENERATION)));
    }

    @Test
    void signedEarlierGenerationCanOnlyBeLookedUpAndFutureGenerationIsInvalid() {
        var older = issuer(GENERATION - 1, now::get).issue(ACTOR);
        var past = keys.verify(older.key(), ACTOR, current);
        assertEquals(PAST_GENERATION_LOOKUP_ONLY, past.eligibility());
        assertEquals(PAST_GENERATION_LOOKUP_ONLY, keys.recheck(past, ACTOR, current));
        assertTrue(keys.isFresh(past));
        var verified = keys.verify(keys.issue(ACTOR).key(), ACTOR, current);
        assertEquals(PAST_GENERATION_LOOKUP_ONLY,
                keys.recheck(verified, ACTOR, submittedAccess(ACTOR, GENERATION + 1)));
        assertInvalid(() -> keys.recheck(verified, ACTOR, submittedAccess(ACTOR, GENERATION - 1)));
        var future = issuer(GENERATION + 1, now::get).issue(ACTOR);
        assertInvalid(() -> keys.verify(future.key(), ACTOR, current));
    }

    @Test
    void exactExpiryOverridesBothGenerationsAndAdmissionMarginNeverShortensLookup() {
        var issued = keys.issue(ACTOR);
        var older = issuer(GENERATION - 1, now::get).issue(ACTOR);
        var verified = keys.verify(issued.key(), ACTOR, current);
        now.set(issued.expiresAt().minus(MARGIN));
        assertEquals(ACTIVE_CAN_EXECUTE, keys.recheck(verified, ACTOR, current));
        now.set(now.get().plusNanos(1));
        assertEquals(ACTIVE_LOOKUP_ONLY, keys.recheck(verified, ACTOR, current));
        now.set(issued.expiresAt().minusNanos(1));
        assertEquals(ACTIVE_LOOKUP_ONLY, keys.verify(issued.key(), ACTOR, current).eligibility());
        assertEquals(PAST_GENERATION_LOOKUP_ONLY, keys.verify(older.key(), ACTOR, current).eligibility());
        assertTrue(keys.isFresh(verified));
        now.set(issued.expiresAt());
        assertFalse(keys.isFresh(verified));
        var active = assertThrows(OperationKeyException.class, () -> keys.verify(issued.key(), ACTOR, current));
        var past = assertThrows(OperationKeyException.class, () -> keys.verify(older.key(), ACTOR, current));
        assertEquals(EXPIRED, active.reason());
        assertEquals(active.reason(), past.reason());
        assertEquals(active.getMessage(), past.getMessage());
        now.set(issued.expiresAt().plusNanos(1));
        assertEquals(EXPIRED, assertThrows(OperationKeyException.class,
                () -> keys.recheck(verified, ACTOR, current)).reason());
    }

    @Test
    void clockOutageClosesIssueVerificationAndGuardRechecksWithSafeDiagnostics() {
        var clock = new AtomicReference<TrustedTime>(() -> NOW);
        var issuer = issuer(GENERATION, () -> clock.get().now());
        var issued = issuer.issue(ACTOR);
        var verified = issuer.verify(issued.key(), ACTOR, current);
        clock.set(() -> { throw new IllegalStateException("protected clock configuration"); });
        assertUnavailable(() -> issuer.issue(ACTOR));
        assertUnavailable(() -> issuer.verify(issued.key(), ACTOR, current));
        assertUnavailable(() -> issuer.recheck(verified, ACTOR, current));
        assertUnavailable(() -> issuer.isFresh(verified));
        clock.set(() -> null);
        assertUnavailable(() -> issuer.verify(issued.key(), ACTOR, current));
        clock.set(() -> NOW.minusNanos(1));
        assertUnavailable(() -> issuer.verify(issued.key(), ACTOR, current));
        clock.set(() -> Instant.MAX);
        assertUnavailable(() -> issuer.issue(ACTOR));
    }

    @Test
    void invalidSignatureWrongPurposeAndNoncanonicalTokensNeverReachTheClock() throws Exception {
        String token = keys.issue(ACTOR).key();
        String[] parts = token.split("\\.");
        String signature = parts[2];
        String alternate = (signature.charAt(0) == 'A' ? "B" : "A") + signature.substring(1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int canonicalLastIndex = alphabet.indexOf(signature.charAt(signature.length() - 1));
        String noncanonical = signature.substring(0, signature.length() - 1) + alphabet.charAt(canonicalLastIndex + 1);
        var noClock = issuer(GENERATION, () -> { throw new AssertionError("clock queried for invalid key"); });
        for (String invalid : List.of("obs1." + parts[1] + "." + signature, token + "=", token + ".",
                " " + token, "opk1." + parts[1] + "." + alternate, "opk1." + parts[1] + "." + noncanonical,
                sign(payload(token), DIGEST_PURPOSE, secret(41)), sign(payload(token), SIGNING_PURPOSE, secret(42)),
                "x".repeat(1025))) {
            assertInvalid(() -> noClock.verify(invalid, ACTOR, current));
        }
    }

    @Test
    void evenAuthenticPayloadMustHaveCanonicalClaimsAndExactValidity() throws Exception {
        byte[] original = payload(keys.issue(ACTOR).key());
        byte[] alteredLifetime = original.clone();
        ByteBuffer.wrap(alteredLifetime).putInt(48, NOW.getNano() + 1);
        byte[] invalidNano = original.clone();
        ByteBuffer.wrap(invalidNano).putInt(36, 1_000_000_000);
        byte[] nonPositiveGeneration = original.clone();
        ByteBuffer.wrap(nonPositiveGeneration).putLong(20, 0);
        byte[] invalidUtf8 = original.clone();
        invalidUtf8[54] = (byte) 0xff;
        byte[] unexpectedBytes = Arrays.copyOf(original, original.length + 1);
        byte[] versionZero = original.clone();
        ByteBuffer.wrap(versionZero).putInt(0, 0);
        for (byte[] invalid : List.of(alteredLifetime, invalidNano, nonPositiveGeneration, invalidUtf8,
                unexpectedBytes, versionZero)) {
            String token = sign(invalid, SIGNING_PURPOSE, secret(41));
            assertInvalid(() -> keys.verify(token, ACTOR, current));
        }
    }

    @Test
    void claimByteLimitProducesAKeyWithinTheRestHeaderBound() {
        String limit = "é".repeat(112);
        var bounded = actor(limit, new OrganizationId(limit), limit);
        var issuer = new OperationKeyIssuer(access(bounded, true, GENERATION, ignored -> {}),
                now::get, secrets(), MARGIN);
        var issued = issuer.issue(bounded);
        assertEquals(1023, issued.key().length());
        assertEquals(bounded.principal(), issuer.verify(issued.key(), bounded, submittedAccess(bounded, GENERATION))
                .receiptKey().principal());

        var over = actor(limit + "x", ORG, "stable");
        var unencodable = new OperationKeyIssuer(access(over, true, GENERATION, ignored -> {}),
                now::get, secrets(), MARGIN);
        assertUnavailable(() -> unencodable.issue(over));
    }

    @Test
    void signingVersionCanRotateWhileOldKeysRemainVerifiable() {
        String older = keys.issue(ACTOR).key();
        var rotating = new OperationKeyIssuer(access(ACTOR, true, GENERATION, ignored -> {}), now::get,
                new OperationSecrets(2, Map.of(1, secret(41), 2, secret(44)), 2, Map.of(2, secret(43))), MARGIN);
        assertEquals(ACTIVE_CAN_EXECUTE, rotating.verify(older, ACTOR, current).eligibility());
        String newest = rotating.issue(ACTOR).key();
        assertEquals(ACTIVE_CAN_EXECUTE, rotating.verify(newest, ACTOR, current).eligibility());
        assertInvalid(() -> keys.verify(newest, ACTOR, current));
        assertInvalid(() -> keys.isFresh(rotating.verify(newest, ACTOR, current)));
    }

    @Test
    void keyValuesAndExceptionsRedactProtectedMaterial() {
        var issued = keys.issue(ACTOR);
        var verified = keys.verify(issued.key(), ACTOR, current);
        assertEquals("IssuedOperationKey[protected]", issued.toString());
        assertEquals("VerifiedOperationKey[protected]", verified.toString());
        assertEquals("ReceiptKey[protected]", verified.receiptKey().toString());
        assertEquals("OperationSecrets[protected]", secrets().toString());
        assertInvalid(() -> keys.verify("protected malformed token", ACTOR, current));
    }

    static OperationSecrets secrets() {
        return new OperationSecrets(1, Map.of(1, secret(41)), 2, Map.of(1, secret(42), 2, secret(43)));
    }

    static byte[] secret(int value) {
        var material = new byte[32];
        Arrays.fill(material, (byte) value);
        return material;
    }

    private static byte[] payload(String token) {
        return Base64.getUrlDecoder().decode(token.split("\\.")[1]);
    }

    private static String sign(byte[] payload, String purpose, byte[] secret) throws GeneralSecurityException {
        var hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
        hmac.update(purpose.getBytes(StandardCharsets.US_ASCII));
        return "opk1." + BASE64.encodeToString(payload) + "." + BASE64.encodeToString(hmac.doFinal(payload));
    }

    private static void assertInvalid(Runnable task) {
        var problem = assertThrows(OperationKeyException.class, task::run);
        assertEquals(INVALID, problem.reason());
        assertEquals("Invalid operation key.", problem.getMessage());
        assertNull(problem.getCause());
    }

    private static void assertUnavailable(Runnable task) {
        var problem = assertThrows(AuthorityUnavailableException.class, task::run);
        assertEquals("Current authority is unavailable.", problem.getMessage());
        assertNull(problem.getCause());
    }

    private static AuthenticatedActor actor(String issuer, OrganizationId organization, String principal) {
        return new AuthenticatedActor(issuer, organization, principal, AuthenticatedActor.Kind.HUMAN,
                Optional.of(new PersonId("stable-person")));
    }

    private static OperationKeyIssuer issuer(int generation, TrustedTime clock) {
        return new OperationKeyIssuer(access(ACTOR, true, generation, ignored -> {}), clock, secrets(), MARGIN);
    }

    private static ScopeAuthority.Admission submittedAccess(AuthenticatedActor actor, int generation) {
        return access(actor, false, generation, ignored -> {}).requireCreate(actor, RequestType.HR_TICKET);
    }

    private static CurrentAccess access(AuthenticatedActor actor, boolean allowed, int generation,
                                        ScopeAuthority authority) {
        var types = allowed ? Set.<RequestType>of() : Set.of(RequestType.HR_TICKET);
        var grant = new SyntheticScopePolicy.Grant(actor, allowed, types, Set.of());
        var writer = new WriterEpoch(new UUID(0, generation), generation);
        return new CurrentAccess(authority, writer,
                List.of(new SyntheticScopePolicy(actor.organization(), 1, List.of(grant))));
    }
}
