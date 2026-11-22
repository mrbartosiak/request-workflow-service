package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Optional;

/** Local organization guard records. Guard lock operations are owned by the access flow. */
public interface PolicyGuardStore {
    void insert(PolicyGuard guard);

    Optional<PolicyGuard> find(OrganizationId organization);
}
