package com.company.requests.application;

import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.TransitionFact;
import com.company.requests.domain.TransitionReason;

/** Existing-request effects on the command connection holding the exclusive organization guard. */
public interface ExistingRequestStore {
    void lockForChange(PolicyGuard guard, RequestRef ref, Field material);

    void advance(PolicyGuard guard, Request before, Request after);

    void replaceBody(PolicyGuard guard, RequestRef ref, RequestBody body);

    void appendReason(PolicyGuard guard, TransitionFact fact, TransitionReason reason);
}
