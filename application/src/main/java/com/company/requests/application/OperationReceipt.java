package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The original successful effect, stored as protected control data for replay. */
public record OperationReceipt(ReceiptKey key, ReceiptIntentDigest intentDigest, long keyGeneration,
                               Instant expiresAt, Action action, RequestRef resultRef, long resultRevision,
                               RequestState resultState, Optional<PublicationId> publicationId, int originalStatus) {
    public OperationReceipt {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(intentDigest, "intent digest");
        Objects.requireNonNull(expiresAt, "expiry");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resultRef, "result reference");
        Objects.requireNonNull(resultState, "result state");
        Objects.requireNonNull(publicationId, "publication ID");
        if (keyGeneration < 1 || resultRevision < 1) {
            throw new IllegalArgumentException("Receipt counters must be positive.");
        }
        boolean validResult = switch (action) {
            case CREATE -> resultState == RequestState.CREATED && resultRevision == 1;
            case EDIT -> resultState == RequestState.CREATED || resultState == RequestState.VERIFIED;
            case DELETE -> resultState == RequestState.DELETED;
            case VERIFY -> resultState == RequestState.VERIFIED;
            case ACCEPT -> resultState == RequestState.ACCEPTED;
            case REJECT -> resultState == RequestState.REJECTED;
            case PUBLISH -> resultState == RequestState.PUBLISHED;
        };
        if (!validResult || originalStatus != (action == Action.CREATE ? 201 : 200)) {
            throw new IllegalArgumentException("The receipt must represent a successful result of its action.");
        }
        if ((action == Action.PUBLISH) != publicationId.isPresent()) {
            throw new IllegalArgumentException("A publication ID is required exactly for a Publish result.");
        }
    }

    @Override
    public String toString() {
        return "OperationReceipt[action=" + action + ", status=" + originalStatus + "]";
    }
}
