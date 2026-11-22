package com.company.requests.application;

import java.util.Optional;

/** Successful local results. Callers prove access and key eligibility before lookup. */
public interface ReceiptStore {
    void insert(OperationReceipt receipt);

    Optional<OperationReceipt> find(ReceiptKey key);
}
