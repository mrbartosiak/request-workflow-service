package com.company.requests.application;

import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.RequestRef;
import java.util.List;
import java.util.Optional;

/** Ordered scope-local candidates. Current access decides which candidates can enter a page. */
public interface RequestBrowseStore {
    record Candidate(CreationOrder order, RequestRef ref) { }

    /** Seek below the complete tuple, in descending numeric and unsigned UTF-8 reference order. */
    List<Candidate> findCandidates(PolicyGuard guard, Optional<Candidate> below, int limit);
}
