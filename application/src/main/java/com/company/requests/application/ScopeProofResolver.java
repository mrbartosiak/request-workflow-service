package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityResolution.Operation;
import com.company.requests.application.ScopeAuthorityResolution.OperatorCredential;
import com.company.requests.application.ScopeAuthorityResolution.Outcome;
import java.util.Objects;
import java.util.UUID;

/** Internal recovery entry point. It has no dependencies on lifecycle, receipt, or dispatch stores. */
public final class ScopeProofResolver {
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final ScopeAuthorityResolution journal;
    private final ScopeProjectionStore projection;

    public ScopeProofResolver(TransactionRunner transactions, PolicyGuardStore guards,
                              ScopeAuthorityResolution journal, ScopeProjectionStore projection) {
        this.transactions = transactions;
        this.guards = guards;
        this.journal = journal;
        this.projection = projection;
    }

    public State resolve(OperatorCredential credential, Admission admission, UUID attemptId, Operation operation) {
        var work = journal.authorize(credential, Objects.requireNonNull(admission),
                Objects.requireNonNull(attemptId), Objects.requireNonNull(operation))
                .orElseThrow(AccessDeniedException::new);
        State state;
        try {
            state = transactions.run(() -> {
                var guard = guards.lockExclusive(admission.genesis().organization())
                        .orElseThrow(AuthorityUnavailableException::new);
                var terminal = journal.resolve(work, projection.writer(guard), guard);
                var checkpoint = terminal.checkpoint();
                if (!admission.equals(checkpoint.admission())) {
                    throw new AuthorityUnavailableException();
                }
                checkpoint.requireGuard(guard);
                projection.replace(guard, checkpoint);
                return terminal.state();
            });
        } catch (RuntimeException failure) {
            journal.finish(work, Outcome.DEFERRED);
            // Persistence exceptions can carry protected operands. The operation returns a safe deferral.
            throw new AuthorityUnavailableException();
        }
        journal.finish(work, Outcome.PROJECTED);
        return state;
    }
}
