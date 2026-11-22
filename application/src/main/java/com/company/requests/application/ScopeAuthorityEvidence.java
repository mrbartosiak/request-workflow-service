package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import java.util.Optional;
import java.util.UUID;

/** Named internal proof operations. The resolver must authorize and audit access to this port. */
public interface ScopeAuthorityEvidence {
    Optional<Entry> find(Admission admission, UUID attemptId);

    /** Discover the scope's durable unresolved attempt after a lost local response. */
    Optional<Entry> findUnresolved(Admission admission);

    /** Close an exact named attempt only with fresh, original-lineage rollback evidence. */
    Entry abort(Admission admission, UUID attemptId);
}
