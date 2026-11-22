package com.company.requests.integration;

import java.util.Objects;
import java.util.UUID;

import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.application.evidence.RequestEvidenceEvent.Direction;
import com.company.requests.application.evidence.RequestEvidenceEvent.Operation;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;

/** Records the actual lifetime of one outbound attempt when invoked by the worker. */
public final class OutboundEvidenceRecorder {
    public enum Destination {
        FAKE_ALPHA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_ALPHA),
        FAKE_BETA(com.company.requests.application.evidence.RequestEvidenceEvent.Destination.FAKE_BETA);

        private final com.company.requests.application.evidence.RequestEvidenceEvent.Destination alias;

        Destination(com.company.requests.application.evidence.RequestEvidenceEvent.Destination alias) {
            this.alias = alias;
        }
    }

    public enum Result {
        CONFIRMED(Outcome.DELIVERY_CONFIRMED),
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
