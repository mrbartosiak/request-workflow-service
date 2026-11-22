package com.company.requests.integration;

import com.company.requests.application.PolicyGuard;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.InboundNotice.Identity;
import com.company.requests.integration.InboundNotice.Type;
import java.util.Objects;

/** Immutable acceptance and quarantine ledger on the guarded workflow transaction. */
public interface InboundInbox {
    record Scope(String sourceSystem, String tenant, String identityDomain) {
        public Scope {
            InboundNotice.requireCoordinate(sourceSystem);
            InboundNotice.requireCoordinate(tenant);
            InboundNotice.requireCoordinate(identityDomain);
        }
    }

    record Key(Scope scope, Identity identity) {
        public Key {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(identity);
        }
    }

    record Receipt(Key key, Type type, Target target, long sourceVersion, long targetRevision, String semanticDigest) {
        public Receipt {
            Objects.requireNonNull(key);
            Objects.requireNonNull(type);
            Objects.requireNonNull(target);
            if (sourceVersion < 1 || targetRevision < 1 || semanticDigest == null
                    || !semanticDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid inbound receipt binding.");
            }
        }

        @Override public String toString() { return "InboundReceipt[protected]"; }
    }

    /** Lookup instructions for authoritative reconciliation. The notice itself cannot apply a workflow action. */
    record Processing(Receipt receipt, String identifierType, Identity externalId) {
        public Processing {
            Objects.requireNonNull(receipt);
            InboundNotice.requireCoordinate(identifierType);
            Objects.requireNonNull(externalId);
        }
    }

    enum Prior { ABSENT, EXACT, CONFLICT }

    /**
     * Serialize this full source key until transaction completion and compare the submitted binding
     * with the durable pair. Return the disposition without releasing the original binding.
     */
    Prior classifyAndLock(PolicyGuard guard, Receipt submitted);

    /** Maximum accepted provider version for this scoped, resolved target, or zero for a new stream. */
    long lastSourceVersion(PolicyGuard guard, Scope scope, Target target);

    void accept(PolicyGuard guard, Processing processing);

    void quarantine(PolicyGuard guard, Receipt discrepancy);
}
