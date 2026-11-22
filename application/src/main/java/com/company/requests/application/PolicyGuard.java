package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Objects;

public record PolicyGuard(OrganizationId organization, long policyVersion) {
    public PolicyGuard {
        Objects.requireNonNull(organization, "organization");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("The policy version must be positive.");
        }
    }
}
