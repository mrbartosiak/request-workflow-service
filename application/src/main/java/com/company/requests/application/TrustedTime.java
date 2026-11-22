package com.company.requests.application;

import java.time.Instant;

/** Supplies a current, trusted UTC instant. A source without usable time proof must throw. */
@FunctionalInterface
public interface TrustedTime {
    Instant now();
}
