package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static com.company.requests.application.OperationKeyIssuerTest.secret;
import static com.company.requests.application.OperationKeyIssuerTest.secrets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentDigestTest {
    private static final RequestRef REF = new RequestRef("ref-a");
    private static final String OBSERVATION = "opaque.original.a";
    private static final IntentDigest.Create CREATE = new IntentDigest.Create(RequestType.HR_TICKET, "café", " body\r\n");
    private final IntentDigest digests = new IntentDigest(secrets());

    @Test
    void fixedDigestVectorBindsPurposeVersionTagsAndExactUtf8() {
        var digest = digests.calculate(CREATE);
        assertEquals(2, digest.version());
        assertEquals("18a554c04618815b8160343ae1aed0aa86976d7e4f001e23a9d00604542d82a0", digest.hex());
        assertTrue(digests.matches(digest, CREATE));
    }

    @Test
    void createBindsTypeNameBodyWhitespaceAndUnicodeWithoutFieldAmbiguity() {
        for (var changed : new IntentDigest.Create[] {
                new IntentDigest.Create(RequestType.BUG_REPORT, CREATE.name(), CREATE.body()),
                new IntentDigest.Create(CREATE.type(), "cafe\u0301", CREATE.body()),
                new IntentDigest.Create(CREATE.type(), CREATE.name(), " body\n"),
                new IntentDigest.Create(CREATE.type(), CREATE.name(), CREATE.body().strip())
        }) {
            assertFalse(digests.matches(digests.calculate(CREATE), changed));
        }
        assertNotEquals(
                digests.calculate(new IntentDigest.Create(CREATE.type(), "ab", "c")),
                digests.calculate(new IntentDigest.Create(CREATE.type(), "a", "bc")));
    }

    @Test
    void bothEditAndTransitionBindTargetActionAndOriginalObservation() {
        var edit = new IntentDigest.Edit(REF, "new text", OBSERVATION);
        var editedDigest = digests.calculate(edit);
        assertFalse(digests.matches(editedDigest, new IntentDigest.Edit(new RequestRef("ref-b"), edit.body(), OBSERVATION)));
        assertFalse(digests.matches(editedDigest, new IntentDigest.Edit(REF, "new text ", OBSERVATION)));
        assertFalse(digests.matches(editedDigest, new IntentDigest.Edit(REF, edit.body(), OBSERVATION + " ")));
        assertFalse(digests.matches(editedDigest, CREATE));

        var rejection = new IntentDigest.Transition(Action.REJECT, REF, Optional.of("reason"), OBSERVATION);
        var rejectedDigest = digests.calculate(rejection);
        assertFalse(digests.matches(rejectedDigest,
                new IntentDigest.Transition(Action.DELETE, REF, rejection.reason(), OBSERVATION)));
        assertFalse(digests.matches(rejectedDigest,
                new IntentDigest.Transition(Action.REJECT, new RequestRef("ref-b"), rejection.reason(), OBSERVATION)));
        assertFalse(digests.matches(rejectedDigest,
                new IntentDigest.Transition(Action.REJECT, REF, Optional.of("reason "), OBSERVATION)));
        assertFalse(digests.matches(rejectedDigest,
                new IntentDigest.Transition(Action.REJECT, REF, rejection.reason(), "opaque.original.b")));
        assertNotEquals(
                digests.calculate(new IntentDigest.Transition(Action.ACCEPT, REF, Optional.empty(), OBSERVATION)),
                digests.calculate(new IntentDigest.Transition(Action.VERIFY, REF, Optional.empty(), OBSERVATION)));
    }

    @Test
    void earlierDigestVersionStillComparesAndUnavailableVersionClosesComparison() {
        var older = new IntentDigest(new OperationSecrets(1, Map.of(1, secret(41)), 1, Map.of(1, secret(42))));
        ReceiptIntentDigest retained = older.calculate(CREATE);
        assertEquals(1, retained.version());
        assertNotEquals(retained.hex(), digests.calculate(CREATE).hex());
        assertTrue(digests.matches(retained, CREATE));
        assertFalse(digests.matches(retained, new IntentDigest.Create(CREATE.type(), "other", CREATE.body())));
        assertThrows(AuthorityUnavailableException.class, () -> older.matches(digests.calculate(CREATE), CREATE));
        assertThrows(AuthorityUnavailableException.class,
                () -> digests.matches(new ReceiptIntentDigest(2, "ab"), CREATE));
    }

    @Test
    void independentSecretMaterialIsRequiredAndDefensivelyCopied() {
        byte[] signing = secret(41);
        byte[] digest = secret(42);
        assertThrows(IllegalArgumentException.class,
                () -> new OperationSecrets(1, Map.of(1, signing), 1, Map.of(1, signing.clone())));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationSecrets(1, Map.of(1, signing), 1, Map.of(1, new byte[31])));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationSecrets(2, Map.of(1, signing), 1, Map.of(1, digest)));
        var copied = new IntentDigest(new OperationSecrets(1, Map.of(1, signing), 1, Map.of(1, digest)));
        var before = copied.calculate(CREATE);
        Arrays.fill(digest, (byte) 0);
        assertEquals(before, copied.calculate(CREATE));
    }

    @Test
    void typedShapesAndExactUtf8BoundsCloseWithSafeDiagnostics() {
        var bodyAtLimit = "🙂".repeat(4096);
        var validIntent = new IntentDigest.Create(CREATE.type(), CREATE.name(), bodyAtLimit);
        digests.calculate(validIntent);
        var error = assertThrows(IllegalArgumentException.class, () -> digests.calculate(
                new IntentDigest.Create(CREATE.type(), CREATE.name(), bodyAtLimit + "x")));
        assertEquals("Invalid exact operation text.", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> digests.calculate(
                new IntentDigest.Edit(REF, "\uD800", OBSERVATION)));
        assertThrows(IllegalArgumentException.class, () -> digests.calculate(
                new IntentDigest.Edit(REF, "body", "\0old")));
        assertThrows(IllegalArgumentException.class, () -> digests.calculate(
                new IntentDigest.Create(CREATE.type(), "\u00a0", "body")));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentDigest.Transition(Action.REJECT, REF, Optional.empty(), OBSERVATION));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentDigest.Transition(Action.VERIFY, REF, Optional.of("reason"), OBSERVATION));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentDigest.Transition(Action.EDIT, REF, Optional.empty(), OBSERVATION));
        assertEquals("CreateIntent[protected]", validIntent.toString());
        assertEquals("EditIntent[protected]", new IntentDigest.Edit(REF, "body", OBSERVATION).toString());
        assertEquals("TransitionIntent[protected]",
                new IntentDigest.Transition(Action.REJECT, REF, Optional.of("reason"), OBSERVATION).toString());
        assertEquals("ReceiptIntentDigest[protected]", digests.calculate(CREATE).toString());
    }
}
