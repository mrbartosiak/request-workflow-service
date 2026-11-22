package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import java.time.Instant;
import java.util.Objects;

/** A versioned reference to an approved projection for a single scoped outbound effect. */
public record OutboundObligation(String destination, OrganizationId tenant, String effectId, RequestRef ref,
                                 long revision, String operation, int schemaVersion, String projectionRef,
                                 Instant scheduledAt) {
    public OutboundObligation {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(ref);
        Objects.requireNonNull(scheduledAt);
        if (destination == null || destination.isBlank() || effectId == null || effectId.isBlank()
                || operation == null || operation.isBlank() || projectionRef == null || projectionRef.isBlank()
                || revision < 1 || schemaVersion < 1) {
            throw new IllegalArgumentException("Incomplete outbound obligation coordinates.");
        }
    }

    @Override
    public String toString() {
        return "OutboundObligation[protected]";
    }
}
