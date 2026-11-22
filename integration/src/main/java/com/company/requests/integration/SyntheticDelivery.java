package com.company.requests.integration;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.WriterEpoch;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.Invocation;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Evidence exchanged by the opted-in synthetic remote endpoint and the local worker. */
public final class SyntheticDelivery {
    private SyntheticDelivery() { }

    public enum Contract { SINGLE_EFFECT, FENCEABLE, UNSAFE }

    public enum Action { RECONCILE, RETRY, FENCE_AND_RETRY, SUPPRESS, FENCE_AND_SUPPRESS }

    public enum Observation { CONFIRMED, ACCEPTED, ABSENT, TRANSIENT, PERMANENT }

    public enum Exclusion { NONE, SINGLE_EFFECT, CLOSED, FENCED }

    public enum Coverage { AUTHORITATIVE, STALE, PARTIAL }

    public enum Disposition { CONFIRMED, ACCEPTED_PENDING, UNKNOWN, RETRYABLE, FAILED_PERMANENT, QUARANTINED, SUPPRESSED }

    public interface Connector {
        Optional<Evidence> send(Invocation invocation);

        default SendResult sendWithThrottle(Invocation invocation) {
            return new SendResult(send(invocation), Optional.empty());
        }

        /** The endpoint obtains an authoritative snapshot after any requested fence. */
        Optional<Evidence> lookup(Invocation invocation, boolean fence);
    }

    public interface Store extends DispatchStore {
        Optional<Disposition> finishDelivery(Claim claim, Optional<Evidence> evidence);

        default Optional<Disposition> finishDelivery(Claim claim, SendResult result) {
            return finishDelivery(claim, result.evidence());
        }

        Optional<Recovery> startRecovery(WriterEpoch writer, AuthenticatedActor actor, DispatchStore.AttemptId attempt,
                                         Action action);

        boolean finishRecovery(Recovery recovery, Optional<Evidence> evidence);
    }

    /** An optional Retry-After hint from the same configured connector and observation. */
    public record SendResult(Optional<Evidence> evidence, Optional<Duration> retryAfter) {
        public SendResult {
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(retryAfter);
            retryAfter.ifPresent(delay -> {
                if (delay.compareTo(Duration.ofMillis(1)) < 0) {
                    throw new IllegalArgumentException("A throttle hint must establish a positive delay.");
                }
                delay.toMillis();
            });
        }

        public static SendResult unknown() {
            return new SendResult(Optional.empty(), Optional.empty());
        }
    }

    public record Recovery(UUID id, WriterEpoch writer, AuthenticatedActor actor, Invocation invocation, Action action) {
        public Recovery {
            Objects.requireNonNull(id);
            Objects.requireNonNull(writer);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(action);
        }

        @Override
        public String toString() {
            return "DispatchRecovery[protected]";
        }
    }

    public record Effect(UUID reference, long appliedAttempt) {
        public Effect {
            Objects.requireNonNull(reference);
            if (appliedAttempt < 1) {
                throw new IllegalArgumentException("Remote application requires a positive attempt number.");
            }
        }

        @Override
        public String toString() {
            return "SyntheticRemoteEffect[protected]";
        }
    }

    /** Remote terminal non-application facts read under the same key lock as the observation. */
    public record NoApplication(long fencedThrough, Set<Long> terminalAttempts) {
        public NoApplication {
            terminalAttempts = Set.copyOf(terminalAttempts);
            if (fencedThrough < 0 || terminalAttempts.stream().anyMatch(attempt -> attempt <= fencedThrough)) {
                throw new IllegalArgumentException("Invalid remote non-application facts.");
            }
        }

        public boolean closesArmedLineage(long currentAttempt, Collection<Long> armedAttempts) {
            return terminalAttempts.contains(currentAttempt) && armedAttempts.contains(currentAttempt)
                    && armedAttempts.stream().allMatch(attempt -> attempt <= fencedThrough
                            || terminalAttempts.contains(attempt));
        }
    }

    /** A persisted observation. Only an authoritative read can contribute disposition proof. */
    public record Evidence(Invocation invocation, UUID endpoint, UUID reference, Observation observation, Exclusion exclusion,
                           Optional<Effect> effect, Coverage coverage, Optional<NoApplication> noApplication) {
        public Evidence(Invocation invocation, UUID endpoint, UUID reference, Observation observation, Exclusion exclusion,
                        Optional<Effect> effect) {
            this(invocation, endpoint, reference, observation, exclusion, effect, Coverage.AUTHORITATIVE, Optional.empty());
        }

        public Evidence {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(endpoint);
            Objects.requireNonNull(reference);
            Objects.requireNonNull(observation);
            Objects.requireNonNull(exclusion);
            Objects.requireNonNull(effect);
            Objects.requireNonNull(coverage);
            Objects.requireNonNull(noApplication);
            var terminal = observation == Observation.TRANSIENT || observation == Observation.PERMANENT;
            if ((observation == Observation.CONFIRMED) != effect.isPresent()
                    || ((observation == Observation.CONFIRMED || observation == Observation.ACCEPTED)
                        && exclusion != Exclusion.NONE)
                    || exclusion == Exclusion.CLOSED || (noApplication.isPresent() && !terminal)
                    || noApplication.stream().flatMap(facts -> facts.terminalAttempts().stream())
                        .anyMatch(attempt -> attempt > invocation.id().number())) {
                throw new IllegalArgumentException("Inconsistent synthetic delivery evidence.");
            }
        }

        @Override
        public String toString() {
            return "SyntheticDeliveryEvidence[protected]";
        }
    }

    public static Optional<Evidence> exact(Invocation invocation, Optional<Evidence> evidence) {
        return evidence.filter(value -> value.coverage() == Coverage.AUTHORITATIVE && invocation.equals(value.invocation())
                && value.effect().stream().allMatch(effect -> effect.appliedAttempt() <= invocation.id().number()));
    }

    public static Disposition sent(Optional<Evidence> evidence, Exclusion exclusion) {
        return evidence.map(value -> switch (value.observation()) {
            case CONFIRMED -> Disposition.CONFIRMED;
            case ACCEPTED -> Disposition.ACCEPTED_PENDING;
            case TRANSIENT -> exclusion == Exclusion.NONE ? Disposition.UNKNOWN : Disposition.RETRYABLE;
            case PERMANENT -> excludesArrivals(exclusion) ? Disposition.FAILED_PERMANENT : Disposition.QUARANTINED;
            case ABSENT -> Disposition.UNKNOWN;
        }).orElse(Disposition.UNKNOWN);
    }

    public static Disposition reconciled(Action action, Optional<Evidence> evidence, Exclusion exclusion) {
        if (evidence.isEmpty()) {
            return Disposition.QUARANTINED;
        }
        var proof = evidence.orElseThrow();
        return switch (proof.observation()) {
            case CONFIRMED -> Disposition.CONFIRMED;
            case ACCEPTED -> Disposition.ACCEPTED_PENDING;
            case ABSENT, TRANSIENT, PERMANENT -> switch (action) {
                case SUPPRESS, FENCE_AND_SUPPRESS -> excludesArrivals(exclusion)
                        ? Disposition.SUPPRESSED : Disposition.QUARANTINED;
                case RETRY, FENCE_AND_RETRY -> proof.observation() != Observation.PERMANENT
                        && exclusion != Exclusion.NONE
                        ? Disposition.RETRYABLE : Disposition.QUARANTINED;
                case RECONCILE -> proof.observation() == Observation.PERMANENT && excludesArrivals(exclusion)
                        ? Disposition.FAILED_PERMANENT : Disposition.QUARANTINED;
            };
        };
    }

    private static boolean excludesArrivals(Exclusion exclusion) {
        return exclusion == Exclusion.CLOSED || exclusion == Exclusion.FENCED;
    }
}
