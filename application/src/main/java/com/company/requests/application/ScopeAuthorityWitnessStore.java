package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.LocalAttempt;

/** Local witness operations on the owned workflow transaction under its exclusive organization guard. */
public interface ScopeAuthorityWitnessStore {
    LocalAttempt begin(Admission admission, PolicyGuard guard, Delta delta);

    void append(Entry prepared);
}
