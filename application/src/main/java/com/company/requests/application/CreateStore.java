package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import java.time.Instant;

/** Writes on the same owned command transaction as the request records. */
public interface CreateStore {
    boolean claim(ReceiptKey key, ReceiptIntentDigest digest, long generation, Instant expiry);

    void insertInitialAssignment(OrganizationId organization, RequestRef ref, PersonId assignee);

    void finalizeReceipt(OperationReceipt receipt);
}
