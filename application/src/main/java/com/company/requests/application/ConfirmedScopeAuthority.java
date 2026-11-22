package com.company.requests.application;

/** Gives guarded authority readers the version of the independently verified current projection. */
@FunctionalInterface
public interface ConfirmedScopeAuthority extends ScopeAuthority {
    ScopeCheckpoint current(Admission admission);

    @Override
    default void requireCurrent(Admission admission) {
        current(admission);
    }
}
