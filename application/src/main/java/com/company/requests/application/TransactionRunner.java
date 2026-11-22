package com.company.requests.application;

import java.util.function.Supplier;

/** Runs synchronous work in one owned database transaction. */
public interface TransactionRunner {
    /**
     * Returns the result after commit. A failed commit phase requires authoritative
     * resolution before the caller admits another effect.
     *
     * @throws CommitOutcomeUnknownException if work returned but commit could not be confirmed
     * @throws IllegalStateException if invoked within an existing transaction
     */
    <T> T run(Supplier<T> work);

    /** Owns the guarded release transaction and permits a specific resource-failure classification. */
    default <T> T runGuardedRelease(Supplier<T> work) {
        return run(work);
    }
}
