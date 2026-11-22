package com.company.requests.domain;

public record OrganizationId(String value) {
    public OrganizationId {
        RequiredText.check(value, "organization");
    }
}
