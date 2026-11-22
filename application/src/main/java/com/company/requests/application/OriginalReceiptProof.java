package com.company.requests.application;

import com.company.requests.application.OperationKeyIssuer.VerifiedKey;
import com.company.requests.application.ScopeAuthority.Admission;
import java.util.Optional;

/** Reads an earlier-generation receipt only from authoritative evidence of its original commit. */
@FunctionalInterface
public interface OriginalReceiptProof {
    /**
     * Return a retained, exact receipt whose original committed effect is proved for the current scope.
     * A restored row, a reservation, or absence from a restored database alone is insufficient proof.
     * The caller holds the current organization guard and separately checks present-day disclosure.
     */
    Optional<OperationReceipt> find(Admission current, VerifiedKey key);

    static OriginalReceiptProof unavailable() {
        return (current, key) -> Optional.empty();
    }
}
