package com.company.requests.domain;

/** Immutable ordering tuple supplied with the reference reservation. */
public record CreationOrder(long epoch, long ordinal) {
    public CreationOrder {
        if (epoch < 1 || ordinal < 1) {
            throw new IllegalArgumentException("Creation epoch and ordinal must be positive.");
        }
    }
}
