package com.company.requests.application.evidence;

import java.util.List;
import java.util.Optional;

import static com.company.requests.application.evidence.RequestEvidenceEvent.Direction;

/** Capacity for two durable frames is held before work can enter a protected boundary. */
public interface RequestEvidenceStorage {
    boolean available(Direction direction);

    Optional<Reservation> reserve(Direction direction);

    /** Stores a complete ingress rejection in separately budgeted capacity. */
    boolean edge(List<RequestEvidenceEvent> events);

    interface Reservation extends AutoCloseable {
        void start(RequestEvidenceEvent event);

        void finish(List<RequestEvidenceEvent> events);

        /** Releases unused capacity. A started attempt abandoned without a finish is an evidence gap. */
        @Override
        void close();
    }

    final class Unavailable extends IllegalStateException {
        public Unavailable() {
            super("Protected request evidence is unavailable.");
        }
    }
}
