package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboundObligationTest {
    @Test
    void retainedVersionOneReferenceDoesNotAcceptArbitraryProjectionContent() {
        var retained = fromReference(1, "publish-request");
        assertEquals("publish-request", retained.projectionRef());
        assertThrows(IllegalArgumentException.class, () -> fromReference(1, "protected request body"));
        assertThrows(IllegalArgumentException.class, () -> fromReference(99, "publish-request"));
        assertThrows(IllegalArgumentException.class, () -> fromReference(2, "publish-request"));
    }

    @Test
    void publishUsesOnlyReceiptCoordinatesAndTheCurrentReferenceSchema() {
        var scheduledAt = Instant.ofEpochSecond(18, 345678901);
        var receipt = receipt("tenant-a", "same-key", 9_007_199_254_740_993L);
        var obligation = OutboundObligation.publishReference("fixture-publish", receipt, scheduledAt);
        assertEquals(new OutboundObligation("fixture-publish", receipt.key().organization(), obligation.effectId(),
                "publish.reference", receipt.resultRef(), 4, "PUBLISH", 2, "published-request-reference", scheduledAt),
                obligation);
        assertEquals("OutboundObligation[protected]", obligation.toString());
        assertThrows(IllegalArgumentException.class, () -> new OutboundObligation(obligation.destination(),
                obligation.tenant(), obligation.effectId(), "publish.other", obligation.ref(), obligation.revision(),
                obligation.operation(), obligation.schemaVersion(), obligation.projectionRef(), scheduledAt));
    }

    @Test
    void stableEffectSurvivesBurnAndIsSeparatedByScopedKeyAndDestination() {
        var original = OutboundObligation.publishReference("fixture-one", receipt("tenant-a", "same-key", 10), Instant.EPOCH);
        var retry = OutboundObligation.publishReference("fixture-one", receipt("tenant-a", "same-key", 11), Instant.EPOCH);
        var alternateDestination = OutboundObligation.publishReference("fixture-two",
                receipt("tenant-a", "same-key", 11), Instant.EPOCH);
        var alternateTenant = OutboundObligation.publishReference("fixture-one",
                receipt("tenant-b", "same-key", 11), Instant.EPOCH);
        var changedOperation = OutboundObligation.publishReference("fixture-one",
                receipt("tenant-a", "different-key", 11), Instant.EPOCH);

        assertEquals("35e5644bf9a33d46dbff0a159d72bfbf801b90273d8a83aa911431c72f711aff", original.effectId());
        assertEquals(original, retry);
        assertEquals(original.effectId(), alternateDestination.effectId());
        assertNotEquals(original.destination(), alternateDestination.destination());
        assertNotEquals(original.effectId(), alternateTenant.effectId());
        assertNotEquals(original.effectId(), changedOperation.effectId());
    }

    private static OperationReceipt receipt(String organization, String tokenId, long publicationId) {
        return new OperationReceipt(new ReceiptKey("fixture-issuer", new OrganizationId(organization),
                "fixture-principal", tokenId), new ReceiptIntentDigest(1, "ab"), 1, Instant.EPOCH.plusSeconds(10),
                Action.PUBLISH, new RequestRef("fixture-ref"), 4, RequestState.PUBLISHED,
                Optional.of(new PublicationId(publicationId)), 200);
    }

    private static OutboundObligation fromReference(int version, String projection) {
        return new OutboundObligation("fixture-publish", new OrganizationId("fixture-org"), "c".repeat(64),
                new RequestRef("fixture-ref"), 4, "PUBLISH", version, projection, Instant.EPOCH);
    }
}
