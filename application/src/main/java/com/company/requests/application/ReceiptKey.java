package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Objects;

/** Verified issuer, stable principal and token identifier within an organization. */
public record ReceiptKey(String issuer, OrganizationId organization, String principal, String tokenId) {
    public ReceiptKey {
        requireText(issuer);
        Objects.requireNonNull(organization, "organization");
        requireText(principal);
        requireText(tokenId);
    }

    private static void requireText(String value) {
        Objects.requireNonNull(value, "receipt key component");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Receipt key components must be non-blank.");
        }
    }

    @Override
    public String toString() {
        return "ReceiptKey[protected]";
    }
}
