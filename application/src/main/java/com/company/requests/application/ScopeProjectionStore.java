package com.company.requests.application;

import com.company.requests.application.WriterLineage.Identity;

/** Projection-only writes on the transaction owning the exact exclusive organization guard. */
public interface ScopeProjectionStore {
    Identity writer(PolicyGuard guard);

    void replace(PolicyGuard guard, ScopeCheckpoint checkpoint);
}
