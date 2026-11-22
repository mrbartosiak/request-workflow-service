package com.company.requests.application;

/** Independently records that a proved, committed result was prepared for possible handoff. */
@FunctionalInterface
public interface ReleasePreparation {
    void append(ScopeAuthority.Admission admission, OperationReceipt receipt);
}
