package com.company.requests.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure decision for one request-type policy. The coordinator supplies the permitted facts
 * and combines the result with common eligibility, grants and observation checks.
 *
 * @param <F> immutable decision facts approved for the action
 * @param <R> approved routing intent value, interpreted by the coordinator
 */
@FunctionalInterface
public interface WorkflowPolicy<F, R> {
    Decision<R> decide(Action action, F permittedFacts);

    sealed interface Decision<R> permits Allow, Deny {}

    record Allow<R>(Optional<R> routingIntent) implements Decision<R> {
        public Allow {
            Objects.requireNonNull(routingIntent, "routing intent");
        }
    }

    record Deny<R>() implements Decision<R> {}
}
