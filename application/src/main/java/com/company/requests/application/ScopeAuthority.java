package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Objects;
import java.util.regex.Pattern;

/** Independently attests the expected immutable scope policy and writer epoch. */
@FunctionalInterface
public interface ScopeAuthority {
    void requireCurrent(Admission admission);

    record Genesis(OrganizationId organization, long policyVersion, String policyDigest) {
        private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

        public Genesis {
            Objects.requireNonNull(organization, "organization");
            if (policyVersion < 1 || !SHA256.matcher(policyDigest).matches()) {
                throw new IllegalArgumentException("Invalid scope genesis.");
            }
        }
    }

    /** Expected proof coordinates. Each protected boundary checks them against the proof store. */
    record Admission(Genesis genesis, WriterEpoch writer) {
        public Admission {
            Objects.requireNonNull(genesis, "scope genesis");
            Objects.requireNonNull(writer, "writer epoch");
        }
    }
}
