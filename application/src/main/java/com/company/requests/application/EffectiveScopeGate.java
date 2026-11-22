package com.company.requests.application;

import com.company.requests.application.ScopeAuthority.Admission;
import java.util.Objects;

/** Current B11 checkpoint and certified writer checked against the owned workflow connection. */
public final class EffectiveScopeGate {
    private final ConfirmedScopeAuthority authority;
    private final WriterLineage writer;
    private final EffectiveScopeStore stores;

    public EffectiveScopeGate(ConfirmedScopeAuthority authority, WriterLineage writer, EffectiveScopeStore stores) {
        this.authority = Objects.requireNonNull(authority);
        this.writer = Objects.requireNonNull(writer);
        this.stores = Objects.requireNonNull(stores);
    }

    public void requireCurrent(Admission admission, PolicyGuard guard) {
        var checkpoint = authority.current(admission);
        if (!admission.equals(checkpoint.admission())) {
            throw new AuthorityUnavailableException();
        }
        checkpoint.requireGuard(guard);
        stores.requireProjection(guard, checkpoint);
        writer.requireCurrent(admission);
    }
}
