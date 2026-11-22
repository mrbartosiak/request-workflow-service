package com.company.requests.integration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.application.evidence.RequestEvidenceEvent.Direction;
import com.company.requests.application.evidence.RequestEvidenceEvent.Operation;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;

/** Records the actual lifetime of one outbound attempt when invoked by the worker. */
public final class OutboundEvidenceRecorder {
    public enum Destination {
        FAKE_ALPHA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_ALPHA),
        FAKE_BETA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_BETA),
        FAKE_GAMMA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_GAMMA),
        FAKE_DELTA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_DELTA),
        FAKE_EPSILON(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_EPSILON),
        FAKE_ZETA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_ZETA),
        FAKE_ETA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_ETA);

        private final com.company.requests.application.evidence.RequestEvidenceEvent.Destination alias;

        Destination(com.company.requests.application.evidence.RequestEvidenceEvent.Destination alias) {
            this.alias = alias;
        }
    }

    public enum Result {
        CONFIRMED(Outcome.DELIVERY_CONFIRMED),
        ACCEPTED(Outcome.DELIVERY_ACCEPTED),
        LOOKUP_COMPLETED(Outcome.DELIVERY_LOOKUP_COMPLETED),
        REJECTED(Outcome.DELIVERY_REJECTED),
        UNKNOWN(Outcome.DELIVERY_UNKNOWN),
        TIMEOUT(Outcome.DELIVERY_TIMEOUT),
        FAILED(Outcome.DELIVERY_FAILURE);

        private final Outcome outcome;

        Result(Outcome outcome) {
            this.outcome = outcome;
        }
    }

    private final RequestEvidenceRecorder recorder;

    public OutboundEvidenceRecorder(RequestEvidenceRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder);
    }

    public Attempt start(Destination destination) {
        return new Attempt(recorder.start(Direction.EGRESS, Operation.VENDOR_SEND, destination.alias));
    }

    public boolean available() {
        return recorder.available(Direction.EGRESS);
    }

    public Optional<Reservation> reserve(Destination destination) {
        return reserve(Operation.VENDOR_SEND, destination);
    }

    public Optional<Reservation> reserveLookup(Destination destination) {
        return reserve(Operation.VENDOR_LOOKUP, destination);
    }

    private Optional<Reservation> reserve(Operation operation, Destination destination) {
        return recorder.reserve(Direction.EGRESS, operation, destination.alias).map(Reservation::new);
    }

    public static final class Reservation implements AutoCloseable {
        private final RequestEvidenceRecorder.ReservedAttempt reserved;

        private Reservation(RequestEvidenceRecorder.ReservedAttempt reserved) {
            this.reserved = reserved;
        }

        public Attempt start() {
            return new Attempt(reserved.start());
        }

        @Override
        public void close() {
            reserved.close();
        }
    }

    public static final class Attempt {
        private final RequestEvidenceRecorder.Attempt evidence;

        private Attempt(RequestEvidenceRecorder.Attempt evidence) {
            this.evidence = evidence;
        }

        public UUID correlationId() {
            return evidence.correlationId();
        }

        public void finish(Result result) {
            evidence.finish(result.outcome, 0);
        }
    }
}
