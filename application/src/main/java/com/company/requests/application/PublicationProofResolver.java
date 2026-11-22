package com.company.requests.application;

import com.company.requests.application.PublicationJournal.Attempt;
import com.company.requests.application.PublicationJournal.State;

/** Private proof operations on the named original attempt, independent of public scope readiness. */
public final class PublicationProofResolver {
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final PublicationJournal journal;
    private final PublicationConfirmationStore confirmations;
    private final WriterLineage lineage;

    public PublicationProofResolver(TransactionRunner transactions, PolicyGuardStore guards, PublicationJournal journal,
                                    PublicationConfirmationStore confirmations, WriterLineage lineage) {
        this.transactions = transactions;
        this.guards = guards;
        this.journal = journal;
        this.confirmations = confirmations;
        this.lineage = lineage;
    }

    public void confirm(ScopeAuthority.Admission scope, Attempt attempt) {
        requireScope(scope, attempt);
        transactions.run(() -> {
            var guard = guards.lockExclusive(attempt.organization()).orElseThrow(AuthorityUnavailableException::new);
            lineage.requireCurrent(scope);
            var committed = journal.confirm(attempt);
            if (!committed.attempt().equals(attempt) || committed.state() != State.COMMITTED) {
                throw new AuthorityUnavailableException();
            }
            confirmations.record(guard, committed);
            return null;
        });
    }

    public void abort(ScopeAuthority.Admission scope, Attempt attempt) {
        requireScope(scope, attempt);
        transactions.run(() -> {
            guards.lockExclusive(attempt.organization()).orElseThrow(AuthorityUnavailableException::new);
            lineage.requireCurrent(scope);
            var aborted = journal.abort(attempt);
            if (!aborted.attempt().equals(attempt) || aborted.state() != State.ABORTED) {
                throw new AuthorityUnavailableException();
            }
            return null;
        });
    }

    private static void requireScope(ScopeAuthority.Admission scope, Attempt attempt) {
        if (!scope.writer().equals(attempt.writer()) || !scope.genesis().organization().equals(attempt.organization())) {
            throw new AuthorityUnavailableException();
        }
    }
}
