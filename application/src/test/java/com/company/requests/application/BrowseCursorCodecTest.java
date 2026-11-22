package com.company.requests.application;

import com.company.requests.application.BrowseCursorCodec.InvalidCursor;
import com.company.requests.application.BrowseCursorCodec.Position;
import com.company.requests.application.BrowseCursorCodec.Window;
import com.company.requests.application.RequestBrowseQuery.Selection;
import com.company.requests.application.RequestBrowseStore.Candidate;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowseCursorCodecTest {
    private static final Instant ISSUED = Instant.parse("2026-11-22T20:00:00.123456789Z");
    private static final AuthenticatedActor ACTOR = actor("issuer", "organization", "principal");
    private static final Selection FILTER = new Selection(Optional.of(" Café 東京 🚦 "),
            Optional.of(RequestState.CREATED), 50);
    private static final Position POSITION = new Position(Long.MAX_VALUE, new Candidate(
            new CreationOrder(9_007_199_254_740_993L, Long.MAX_VALUE), new RequestRef("ref-𐀀")),
            Window.starting(ISSUED));

    @Test
    void preservesExactCoordinatesAndUsesRandomOpaqueAscii() {
        var codec = codec(1, Map.of(1, material(1)));
        var first = codec.seal(ACTOR, FILTER, POSITION);
        var second = codec.seal(ACTOR, FILTER, POSITION);
        assertNotEquals(first, second);
        assertTrue(first.matches("browse1\\.1\\.[A-Za-z0-9_-]{1,2038}"));
        assertFalse(first.contains("ref-"));
        assertEquals(POSITION, codec.open(first, ACTOR, FILTER));
        assertEquals(POSITION, codec.open(second, ACTOR, FILTER));
        assertTrue(POSITION.window().contains(ISSUED));
        assertTrue(POSITION.window().contains(ISSUED.plusSeconds(900).minusNanos(1)));
        assertFalse(POSITION.window().contains(ISSUED.minusNanos(1)));
        assertFalse(POSITION.window().contains(ISSUED.plusSeconds(900)));
    }

    @Test
    void bindsIssuerOrganizationPrincipalAndEachEffectiveParameter() {
        var codec = codec(1, Map.of(1, material(1)));
        var cursor = codec.seal(ACTOR, FILTER, POSITION);
        for (var different : java.util.List.of(actor("another", "organization", "principal"),
                actor("issuer", "another", "principal"), actor("issuer", "organization", "another"))) {
            assertThrows(InvalidCursor.class, () -> codec.open(cursor, different, FILTER));
        }
        for (var different : java.util.List.of(
                new Selection(Optional.of(" Cafe\u0301 東京 🚦 "), FILTER.state(), 50),
                new Selection(Optional.of(" café 東京 🚦 "), FILTER.state(), 50),
                new Selection(Optional.empty(), FILTER.state(), 50),
                new Selection(FILTER.name(), Optional.of(RequestState.VERIFIED), 50),
                new Selection(FILTER.name(), Optional.empty(), 50),
                new Selection(FILTER.name(), FILTER.state(), 49))) {
            assertThrows(InvalidCursor.class, () -> codec.open(cursor, ACTOR, different));
        }
    }

    @Test
    void verifiesRetainedKeysAndRejectsTamperingAndNoncanonicalEncoding() {
        var first = codec(1, Map.of(1, material(1)));
        var rotated = codec(2, Map.of(1, material(1), 2, material(2)));
        var retired = codec(2, Map.of(2, material(2)));
        var cursor = first.seal(ACTOR, FILTER, POSITION);
        assertEquals(POSITION, rotated.open(cursor, ACTOR, FILTER));
        assertEquals(POSITION, retired.open(rotated.seal(ACTOR, FILTER, POSITION), ACTOR, FILTER));
        assertThrows(InvalidCursor.class, () -> retired.open(cursor, ACTOR, FILTER));
        int index = 55;
        String changed = cursor.substring(0, index) + (cursor.charAt(index) == 'A' ? 'B' : 'A')
                + cursor.substring(index + 1);
        for (var invalid : java.util.List.of(cursor.replace("browse1.1.", "browse1.2."),
                cursor.replace("browse1.1.", "browse1.01."), cursor.replace("browse1.1.", "obs1.1."),
                cursor.replace("browse1.1.", "browse1.2147483648."), changed, cursor + "=", "x".repeat(2049))) {
            assertThrows(InvalidCursor.class, () -> rotated.open(invalid, ACTOR, FILTER));
        }
    }

    @Test
    void rejectsOperationAndObservationKeyReuseAndCopiesConfiguration() {
        for (int reused : new int[] {3, 4, 5}) {
            assertThrows(IllegalArgumentException.class, () -> codec(1, Map.of(1, material(reused))));
        }
        assertThrows(IllegalArgumentException.class, () -> codec(1, Map.of(1, material(1), 2, material(1))));
        byte[] supplied = material(1);
        var retained = codec(1, Map.of(1, supplied));
        supplied[0] = 6;
        var cursor = retained.seal(ACTOR, FILTER, POSITION);
        assertEquals(POSITION, codec(1, Map.of(1, material(1))).open(cursor, ACTOR, FILTER));
    }

    private static AuthenticatedActor actor(String issuer, String organization, String principal) {
        return new AuthenticatedActor(issuer, new OrganizationId(organization), principal,
                AuthenticatedActor.Kind.HUMAN, Optional.of(new PersonId("stable-person")));
    }

    private static BrowseCursorCodec codec(int active, Map<Integer, byte[]> keys) {
        return new BrowseCursorCodec(new BrowseCursorCodec.Keys(active, keys),
                new OperationSecrets(3, Map.of(3, material(3)), 4, Map.of(4, material(4))),
                Optional.of(new ObservationCodec.Keys(5, Map.of(5, material(5)))));
    }

    private static byte[] material(int discriminator) {
        byte[] key = new byte[32];
        key[0] = (byte) discriminator;
        return key;
    }
}
