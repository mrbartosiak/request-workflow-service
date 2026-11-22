package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** A versioned reference to an approved projection for a single scoped outbound effect. */
public record OutboundObligation(String destination, OrganizationId tenant, String effectId, String triggerSlot,
                                 RequestRef ref, long revision, String operation, int schemaVersion,
                                 String projectionRef, Instant scheduledAt) {
    public static final String PUBLISH_REFERENCE_SLOT = "publish.reference";
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final String CURRENT_PROJECTION_REF = "published-request-reference";

    /** The original flat Publish envelope used the same implicit trigger slot. */
    public OutboundObligation(String destination, OrganizationId tenant, String effectId, RequestRef ref,
                              long revision, String operation, int schemaVersion, String projectionRef,
                              Instant scheduledAt) {
        this(destination, tenant, effectId, PUBLISH_REFERENCE_SLOT, ref, revision, operation, schemaVersion,
                projectionRef, scheduledAt);
    }

    public OutboundObligation {
        requireDestination(destination);
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(ref);
        Objects.requireNonNull(scheduledAt);
        requireDigest(effectId);
        requirePublishSlot(triggerSlot);
        boolean approvedProjection = switch (schemaVersion) {
            case 1 -> "publish-request".equals(projectionRef);
            case CURRENT_SCHEMA_VERSION -> CURRENT_PROJECTION_REF.equals(projectionRef);
            default -> false;
        };
        if (!Action.PUBLISH.name().equals(operation) || revision < 2 || !approvedProjection) {
            throw new IllegalArgumentException("Unsupported outbound reference envelope.");
        }
    }

    static OutboundObligation publishReference(String destination, OperationReceipt receipt, Instant scheduledAt) {
        if (receipt.action() != Action.PUBLISH) {
            throw new IllegalArgumentException("A Publish obligation requires a Publish receipt.");
        }
        String effectId = effectIdentity(PublicationJournal.receiptIdentity(receipt.key()), PUBLISH_REFERENCE_SLOT);
        return new OutboundObligation(destination, receipt.key().organization(), effectId, PUBLISH_REFERENCE_SLOT,
                receipt.resultRef(), receipt.resultRevision(), receipt.action().name(), CURRENT_SCHEMA_VERSION,
                CURRENT_PROJECTION_REF, scheduledAt);
    }

    /** The receipt identity is stable across abort, reservation burn, configuration replay and schema version. */
    public static String effectIdentity(String receiptIdentity, String triggerSlot) {
        requireDigest(receiptIdentity);
        requirePublishSlot(triggerSlot);
        String binding = "requests.outbound.effect.v1\n" + receiptIdentity + "\n" + triggerSlot;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(binding.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    static void requireDestination(String destination) {
        if (destination == null || !destination.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid outbound destination identifier.");
        }
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid outbound identity digest.");
        }
    }

    private static void requirePublishSlot(String triggerSlot) {
        if (!PUBLISH_REFERENCE_SLOT.equals(triggerSlot)) {
            throw new IllegalArgumentException("Unsupported outbound trigger slot.");
        }
    }

    @Override
    public String toString() {
        return "OutboundObligation[protected]";
    }
}
