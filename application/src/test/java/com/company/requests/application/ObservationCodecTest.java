package com.company.requests.application;

import com.company.requests.application.CurrentAccess.ActionTarget;
import com.company.requests.application.EffectiveScopeStore.Assignment;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.ObservationCodec.Verification;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthority.Genesis;
import com.company.requests.domain.Action;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationCodecTest {
    private static final Instant ISSUED = Instant.parse("2026-11-22T21:00:00.123456789Z");
    private static final Instant EXPIRES = ISSUED.plus(ObservationCodec.WINDOW);
    private static final byte[] BINDING = secret(5);
    private static final OrganizationId ORG = new OrganizationId("observation-synthetic");
    private static final PersonId PERSON = new PersonId("synthetic-person");
    private static final WriterEpoch WRITER = new WriterEpoch(new UUID(4, 7), 12);
    private static final Request TARGET = new Request(new RequestRef("reference"), RequestType.HR_TICKET, ORG, "name",
            RequestState.CREATED, 1, 1, new CreationOrder(11, 13), PERSON, Optional.empty());

    @Test
    void encryptsWithFreshNoncesAndUsesTheExactHalfOpenTimeWindow() {
        var codec = codec(1, Map.of(1, secret(1)));
        var one = codec.seal(BINDING, ISSUED);
        var two = codec.seal(BINDING, ISSUED);
        assertNotEquals(one, two);
        assertEquals(Verification.MATCH, codec.verify(one, BINDING, ISSUED));
        assertEquals(Verification.MATCH, codec.verify(two, BINDING, EXPIRES.minusNanos(1)));
        assertEquals(Verification.STALE, codec.verify(one, BINDING, ISSUED.minusNanos(1)));
        assertEquals(Verification.STALE, codec.verify(two, BINDING, EXPIRES));
        assertEquals(Verification.STALE, codec.verify(one, secret(6), ISSUED));
    }

    @Test
    void supportsRotationAndRejectsNoncanonicalFormatAndTampering() {
        var original = codec(1, Map.of(1, secret(1)));
        var rotated = codec(2, Map.of(1, secret(1), 2, secret(2)));
        var retired = codec(2, Map.of(2, secret(2)));
        String first = original.seal(BINDING, ISSUED);
        String second = rotated.seal(BINDING, ISSUED);
        assertEquals(Verification.MATCH, rotated.verify(first, BINDING, ISSUED));
        assertEquals(Verification.MATCH, retired.verify(second, BINDING, ISSUED));
        assertEquals(Verification.STALE, retired.verify(first, BINDING, ISSUED));
        assertEquals(Verification.STALE, rotated.verify(first.replace("obs1.1.", "obs1.2."), BINDING, ISSUED));
        char alternative = first.charAt(42) == 'A' ? 'B' : 'A';
        String tampered = first.substring(0, 42) + alternative + first.substring(43);
        assertEquals(Verification.STALE, rotated.verify(tampered, BINDING, ISSUED));
        assertEquals(Verification.MALFORMED, rotated.verify(first.replace("obs1.1.", "obs1.01."), BINDING, ISSUED));
        assertEquals(Verification.MALFORMED, rotated.verify(first.replace("obs1.1.", "obs1.2147483648."),
                BINDING, ISSUED));
        assertEquals(Verification.MALFORMED, rotated.verify(first + "=", BINDING, ISSUED));
        assertEquals(Verification.MALFORMED, rotated.verify("1", BINDING, ISSUED));
        assertEquals(Verification.MALFORMED, rotated.verify("x".repeat(2049), BINDING, ISSUED));
    }

    @Test
    void requiresIndependentKeyMaterialAndCopiesConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> codec(1, Map.of(1, secret(3))));
        assertThrows(IllegalArgumentException.class, () -> codec(1, Map.of(1, secret(4))));
        assertThrows(IllegalArgumentException.class, () -> codec(1, Map.of(1, secret(1), 2, secret(1))));
        assertThrows(IllegalArgumentException.class, () -> codec(1, Map.of(2, secret(1))));
        byte[] configured = secret(1);
        var copied = codec(1, Map.of(1, configured));
        var prior = copied.seal(BINDING, ISSUED);
        configured[0] = 42;
        assertEquals(Verification.MATCH, codec(1, Map.of(1, secret(1))).verify(prior, BINDING, ISSUED));
        assertEquals(Verification.MATCH, copied.verify(prior, BINDING, ISSUED));
    }

    @Test
    void bindsActorScopeActionWriterGuardTargetAndMaterialCoordinatesIndependently() {
        var codec = codec(1, Map.of(1, secret(1)));
        byte[] current = binding("issuer", "principal", WRITER, 12, "0", Action.EDIT, TARGET, 2, 0, " é ");
        String token = codec.seal(current, ISSUED);
        assertEquals(Verification.MATCH, codec.verify(token, current, ISSUED));
        assertStale(codec, token, binding("other issuer", "principal", WRITER, 12, "0", Action.EDIT,
                TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "another principal", WRITER, 12, "0", Action.EDIT,
                TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", new WriterEpoch(WRITER.writerId(), 13), 12, "0",
                Action.EDIT, TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", new WriterEpoch(new UUID(5, 7), 12), 12, "0",
                Action.EDIT, TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "1", Action.EDIT, TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 13, "0", Action.EDIT, TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.VERIFY, TARGET, 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.EDIT, TARGET, 3, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.EDIT, TARGET, 2, 1, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.EDIT, TARGET, 2, 0, " e\u0301 "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.EDIT,
                new Request(TARGET.ref(), TARGET.type(), TARGET.organization(), TARGET.name(), TARGET.state(), 2, 1,
                        TARGET.creationOrder(), TARGET.originatingPersonId(), Optional.empty()), 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.EDIT,
                new Request(TARGET.ref(), RequestType.BUG_REPORT, TARGET.organization(), TARGET.name(), TARGET.state(),
                        1, 1, TARGET.creationOrder(), TARGET.originatingPersonId(), Optional.empty()), 2, 0, " é "));
        assertStale(codec, token, binding("issuer", "principal", WRITER, 12, "0", Action.EDIT,
                new Request(TARGET.ref(), TARGET.type(), new OrganizationId("other-organization"), TARGET.name(),
                        TARGET.state(), 1, 1, TARGET.creationOrder(), TARGET.originatingPersonId(), Optional.empty()),
                2, 0, " é "));
    }

    private static void assertStale(ObservationCodec codec, String token, byte[] changed) {
        assertEquals(Verification.STALE, codec.verify(token, changed, ISSUED));
    }

    private static byte[] binding(String issuer, String principal, WriterEpoch writer, long policyVersion,
                                  String digestDigit, Action action, Request target, long assignment,
                                  long visibility, String body) {
        var org = target.organization();
        var actor = new AuthenticatedActor(issuer, org, principal, AuthenticatedActor.Kind.HUMAN, Optional.of(PERSON));
        var admission = new Admission(new Genesis(org, 1, digestDigit.repeat(64)), writer);
        var scope = new ActionTarget(target, new Assignment(PERSON, assignment),
                new Visibility(visibility, false, Visibility.INITIAL.fields()), Field.BODY);
        return ObservationBinding.digest(actor, admission, new PolicyGuard(org, policyVersion), action, scope, body);
    }

    private static ObservationCodec codec(int active, Map<Integer, byte[]> entries) {
        return new ObservationCodec(new ObservationCodec.Keys(active, entries),
                new OperationSecrets(1, Map.of(1, secret(3)), 1, Map.of(1, secret(4))));
    }

    private static byte[] secret(int discriminant) {
        byte[] result = new byte[32];
        result[0] = (byte) discriminant;
        return result;
    }
}
