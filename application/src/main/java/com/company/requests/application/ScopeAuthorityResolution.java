package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.WriterLineage.Identity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Private proof operations. A work item binds one authorized audit record to one named decision. */
public interface ScopeAuthorityResolution {
    Optional<Work> authorize(OperatorCredential credential, Admission admission, UUID attemptId, Operation operation);

    Terminal resolve(Work work, Identity guardedWriter, PolicyGuard guard);

    void finish(Work work, Outcome outcome);

    enum Operation { CONFIRM, ABORT }
    enum Outcome { PROJECTED, DEFERRED }

    record OperatorCredential(String secret) {
        @Override
        public String toString() {
            return "OperatorCredential[protected]";
        }
    }

    record Work(UUID auditId, Admission admission, UUID attemptId, Operation operation) { }

    record Terminal(State state, ScopeCheckpoint checkpoint) {
        public Terminal {
            Objects.requireNonNull(checkpoint);
            if (state != State.COMMITTED && state != State.ABORTED) {
                throw new IllegalArgumentException("Resolution must be terminal.");
            }
        }
    }
}
