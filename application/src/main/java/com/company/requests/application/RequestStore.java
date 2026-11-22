package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.TransitionFact;
import java.util.List;
import java.util.Optional;

/** Local records used within an authorized workflow transaction. */
public interface RequestStore {
    void insert(Request request);

    void insertBody(OrganizationId organization, RequestRef ref, RequestBody body);

    void appendFact(OrganizationId organization, TransitionFact fact);

    Optional<Request> find(OrganizationId organization, RequestRef ref);

    Optional<RequestBody> findRetainedBody(OrganizationId organization, RequestRef ref);

    List<TransitionFact> findFacts(OrganizationId organization, RequestRef ref);
}
