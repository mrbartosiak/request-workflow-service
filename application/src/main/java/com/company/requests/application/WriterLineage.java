package com.company.requests.application;

import java.time.Instant;
import java.util.Objects;

/** Checks that the owned command connection belongs to the independently certified live writer. */
@FunctionalInterface
public interface WriterLineage {
    void requireCurrent(ScopeAuthority.Admission admission);

    /** An owner-certified database and running server instance. A stopped writer cannot be reused. */
    record Identity(String address, int port, String database, Instant startedAt) {
        public Identity {
            Objects.requireNonNull(address);
            Objects.requireNonNull(database);
            Objects.requireNonNull(startedAt);
            if (address.isBlank() || database.isBlank() || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Invalid live workflow identity.");
            }
        }
    }
}
