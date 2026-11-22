package com.company.requests.domain;

import java.time.Instant;
import java.util.Objects;

/** A proposed append-only history row, paired with the new request snapshot by Lifecycle. */
public record TransitionFact(RequestRef ref, long sequence, long revision, Action action,
                             RequestState from, RequestState to, Occurrence occurrence) {
    public TransitionFact {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurrence, "occurrence");
        if (sequence < 1 || revision < sequence) {
            throw new IllegalArgumentException("History counters must be positive and ordered.");
        }
        if (action == Action.CREATE) {
            if (from != null || to != RequestState.CREATED || sequence != 1 || revision != 1) {
                throw new IllegalArgumentException("Create must be the initial fact.");
            }
        } else if (from == null || from == to || sequence == 1 || Lifecycle.next(from, action) != to) {
            throw new IllegalArgumentException("History requires a permitted state change after creation.");
        }
    }

    public record Occurrence(Instant recordedAt, String actorRef) {
        public Occurrence {
            Objects.requireNonNull(recordedAt, "recorded instant");
            RequiredText.check(actorRef, "protected actor reference");
        }

        @Override
        public String toString() {
            return "Occurrence[recordedAt=" + recordedAt + ", actorRef=protected]";
        }
    }
}
