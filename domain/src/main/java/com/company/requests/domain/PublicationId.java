package com.company.requests.domain;

/** Positive signed-64-bit number supplied by the publication reservation. */
public record PublicationId(long value) {
    public static final String INITIAL_NAMESPACE = "initial-requests";

    public PublicationId {
        if (value < 1) {
            throw new IllegalArgumentException("Publication ID must be positive.");
        }
    }
}
