package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.util.List;
import java.util.Optional;

/** Local records used within an authorized workflow transaction. */
public interface RequestStore {
    void insert(Request request);

    void insertBody(OrganizationId organization, RequestRef ref, RequestBody body);

    void appendFact(OrganizationId organization, TransitionFact fact);

    Optional<Request> find(OrganizationId organization, RequestRef ref);
    /** Read only association coordinates for a published request on the guarded connection. */
    default Optional<PublishedTarget> findPublishedTarget(PolicyGuard guard, RequestRef ref) {
        return find(guard.organization(), ref).flatMap(PublishedTarget::from);
    }

    Optional<RequestBody> findRetainedBody(OrganizationId organization, RequestRef ref);

    List<TransitionFact> findFacts(OrganizationId organization, RequestRef ref);

    /** Read only lifecycle counters and states in ascending sequence on the guarded connection. */
    List<HistoryFact> findHistory(PolicyGuard guard, RequestRef ref);

    record HistoryFact(long sequence, long revision, Action action, RequestState from, RequestState to) { }

    record PublishedTarget(OrganizationId organization, RequestRef ref, RequestType type, long revision,
                           PublicationId publicationId) {
        static Optional<PublishedTarget> from(Request request) {
            return request.state() == RequestState.PUBLISHED
                    ? request.publicationId().map(id -> new PublishedTarget(request.organization(), request.ref(),
                            request.type(), request.revision(), id))
                    : Optional.empty();
        }

        @Override
        public String toString() {
            return "PublishedTarget[protected]";
        }
    }
}
