package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;

/** Writes on the same owned command transaction as the request records. */
public interface CreateStore extends ReceiptClaims {
    void insertInitialAssignment(OrganizationId organization, RequestRef ref, PersonId assignee);
}
