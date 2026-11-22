package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.State;
import java.util.function.Function;

/** Local effective point for an internal scope mutation. Independent confirmation still gates release. */
public final class ScopeAuthorityMutation {
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final ScopeAuthorityJournal journal;
    private final ScopeAuthorityWitnessStore witnesses;

    public ScopeAuthorityMutation(TransactionRunner transactions, PolicyGuardStore guards,
                                  ScopeAuthorityJournal journal, ScopeAuthorityWitnessStore witnesses) {
        this.transactions = transactions;
        this.guards = guards;
        this.journal = journal;
        this.witnesses = witnesses;
    }

    public <T> LocalCommit<T> run(Admission admission, Delta delta, Function<PolicyGuard, T> effect) {
        return transactions.run(() -> {
            var guard = guards.lockExclusive(admission.genesis().organization())
                    .orElseThrow(AuthorityUnavailableException::new);
            var local = witnesses.begin(admission, guard, delta);
            var registered = journal.register(local);
            if (!registered.attempt().equals(local.attempt()) || registered.state() != State.REGISTERED) {
                throw new AuthorityUnavailableException();
            }
            // An uncertain prepare terminates this callback before any local effect.
            var prepared = journal.prepare(local.attempt(), delta);
            if (!prepared.attempt().equals(local.attempt()) || prepared.state() != State.PENDING) {
                throw new AuthorityUnavailableException();
            }
            T result = effect.apply(guard);
            witnesses.append(prepared);
            return new LocalCommit<>(prepared, result);
        });
    }

    public record LocalCommit<T>(Entry prepared, T result) { }
}
