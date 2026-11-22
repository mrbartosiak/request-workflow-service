package com.company.requests.application;

import java.util.Optional;

/** Independently records that a proved, committed result was prepared for possible handoff. */
@FunctionalInterface
public interface ReleasePreparation {
    void append(ScopeAuthority.Admission admission, OperationReceipt receipt);

    /** Legacy preparation can be retained without an enqueue or client evidence input. */
    default Optional<ReleaseEvidence.Correlation> prepare(ScopeAuthority.Admission admission, OperationReceipt receipt) {
        append(admission, receipt);
        return Optional.empty();
    }
}
