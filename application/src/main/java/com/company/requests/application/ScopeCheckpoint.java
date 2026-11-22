package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import java.util.Objects;
import java.util.regex.Pattern;

/** The verified ordered authority prefix and policy version on the admitted writer. */
public record ScopeCheckpoint(Admission admission, long sequence, long policyVersion, String prefixDigest) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ScopeCheckpoint {
        Objects.requireNonNull(admission);
        if (sequence < 0 || policyVersion < admission.genesis().policyVersion()
                || !SHA256.matcher(prefixDigest).matches()) {
            throw new IllegalArgumentException("Invalid scope checkpoint.");
        }
    }

    public void requireGuard(PolicyGuard guard) {
        if (!admission.genesis().organization().equals(guard.organization()) || policyVersion != guard.policyVersion()) {
            throw new AuthorityUnavailableException();
        }
    }
}
