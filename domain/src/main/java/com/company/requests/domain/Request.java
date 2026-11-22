package com.company.requests.domain;

import java.util.Objects;
import java.util.Optional;

/** Immutable workflow snapshot. Body, ownership and assignment are separate records. */
public record Request(RequestRef ref, RequestType type, OrganizationId organization, String name,
                      RequestState state, long revision, long lastSequence, CreationOrder creationOrder,
                      PersonId originatingPersonId, Optional<PublicationId> publicationId) {
    public Request {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(organization, "organization");
        RequiredText.check(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(creationOrder, "creation order");
        Objects.requireNonNull(originatingPersonId, "originating person");
        Objects.requireNonNull(publicationId, "publication ID");
        if (lastSequence < 1 || revision < lastSequence) {
            throw new IllegalArgumentException("Counters must be positive and revision must cover the history index.");
        }
        if ((state == RequestState.PUBLISHED) != publicationId.isPresent()) {
            throw new IllegalArgumentException("A publication ID is required exactly in PUBLISHED.");
        }
    }

    Request advance(RequestState next, long sequence, Optional<PublicationId> publication) {
        return new Request(ref, type, organization, name, next, Math.incrementExact(revision), sequence,
                creationOrder, originatingPersonId, publication);
    }

    @Override
    public String toString() {
        return "Request[state=" + state + ", revision=" + revision + ", lastSequence=" + lastSequence + "]";
    }
}
