package com.company.requests.application;

import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.LocalAttempt;

/** Independent command-side proof. Registration must be durable before a prepare can be dispatched. */
public interface ScopeAuthorityJournal {
    Entry register(LocalAttempt attempt);

    Entry prepare(Attempt attempt, Delta delta);
}
