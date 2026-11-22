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
        return run(admission, guard -> new Plan<>(delta, ignored -> effect.apply(guard)));
    }

    /** Plans the exact update against current state while holding the exclusive organization guard. */
    public <T> LocalCommit<T> run(Admission admission, Function<PolicyGuard, Plan<T>> planChange) {
        return transactions.run(() -> {
            var guard = guards.lockExclusive(admission.genesis().organization())
                    .orElseThrow(AuthorityUnavailableException::new);
            return apply(admission, guard, planChange.apply(guard));
        });
    }

    /** Apply a planned scope change on the caller's owned, exclusively guarded command transaction. */
    public <T> LocalCommit<T> apply(Admission admission, PolicyGuard guard, Plan<T> plan) {
        var local = witnesses.begin(admission, guard, plan.delta());
        var registered = journal.register(local);
        if (!registered.attempt().equals(local.attempt()) || registered.state() != State.REGISTERED) {
            throw new AuthorityUnavailableException();
        }
        // An uncertain prepare terminates this callback before any local effect.
        var prepared = journal.prepare(local.attempt(), plan.delta());
        if (!prepared.attempt().equals(local.attempt()) || prepared.state() != State.PENDING) {
            throw new AuthorityUnavailableException();
        }
        T result = plan.apply().apply(prepared);
        witnesses.append(prepared);
        return new LocalCommit<>(prepared, result);
    }

    public record Plan<T>(Delta delta, Function<Entry, T> apply) { }

    public record LocalCommit<T>(Entry prepared, T result) { }
}
