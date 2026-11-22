package com.company.requests.application;

import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.RequestRef;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Independent durable allocator of globally unique Create references and creation order. */
@FunctionalInterface
public interface IdentityAuthority {
    /**
     * Retries of an uncertain proof call use the same execution attempt. A new workflow
     * execution after proven rollback uses a fresh attempt and leaves the prior value burned.
     */
    Reservation reserve(ScopeAuthority.Admission admission, UUID executionAttempt);

    /** Protected control data for the Create transaction. Safe external use begins after commit and release. */
    record Reservation(RequestRef ref, CreationOrder creationOrder) {
        private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{32}");

        public Reservation {
            Objects.requireNonNull(ref, "request reference");
            Objects.requireNonNull(creationOrder, "creation order");
            if (!FORMAT.matcher(ref.value()).matches()) {
                throw new IllegalArgumentException("Reserved references require 192-bit base64url encoding.");
            }
        }

        @Override
        public String toString() {
            return "Reservation[protected]";
        }
    }
}
