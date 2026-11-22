package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Optional;

/** Local organization guards. Locks belong to the owning command transaction. */
public interface PolicyGuardStore {
    void insert(PolicyGuard guard);

    Optional<PolicyGuard> find(OrganizationId organization);

    Optional<PolicyGuard> lockShared(OrganizationId organization);

    Optional<PolicyGuard> lockExclusive(OrganizationId organization);
}
