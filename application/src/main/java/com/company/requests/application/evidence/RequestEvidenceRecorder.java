package com.company.requests.application.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.company.requests.application.evidence.RequestEvidenceEvent.*;

/** Emits one start and one outcome, plus an error for each failed request. */
public final class RequestEvidenceRecorder {
    private final Clock clock;
    private final Consumer<RequestEvidenceEvent> sink;

    public RequestEvidenceRecorder(Clock clock, Consumer<RequestEvidenceEvent> sink) {
        this.clock = Objects.requireNonNull(clock);
        this.sink = Objects.requireNonNull(sink);
    }

    public Attempt start(Direction direction, Operation operation, Destination destination) {
        var attempt = new Attempt(direction, operation, destination);
        attempt.emit(clock.instant(), Phase.START, Outcome.STARTED, 0, 0);
        return attempt;
    }

    /** The connector may report a request that failed before the application could start a record. */
    public void recordUndispatchedIngress(long elapsedNanos, int status) {
        var attempt = new Attempt(Direction.INGRESS, Operation.UNMATCHED_HTTP, Destination.REQUESTS_API);
        long elapsed = Math.max(elapsedNanos, 0);
        Instant finished = clock.instant();
        Outcome outcome = status >= 400 ? Outcome.TRANSPORT_REJECTED : Outcome.COMPLETED;
        if (status == 408) {
            outcome = Outcome.TRANSPORT_TIMEOUT;
        }
        attempt.emit(finished.minusNanos(elapsed), Phase.START, Outcome.STARTED, 0, 0);
        attempt.complete(finished, outcome, elapsed, status);
    }

    public final class Attempt {
        private final UUID id = UUID.randomUUID();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long startedAt = System.nanoTime();
        private final Direction direction;
        private final Operation operation;
        private final Destination destination;

        private Attempt(Direction direction, Operation operation, Destination destination) {
            this.direction = Objects.requireNonNull(direction);
            this.operation = Objects.requireNonNull(operation);
            this.destination = Objects.requireNonNull(destination);
        }

        public UUID correlationId() {
            return id;
        }

        public void finish(Outcome outcome, int status) {
            complete(clock.instant(), outcome, Math.max(System.nanoTime() - startedAt, 0), status);
        }

        private void complete(Instant at, Outcome outcome, long elapsed, int status) {
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.STARTED || status != 0 && (status < 100 || status > 599)) {
                throw new IllegalArgumentException("Invalid request evidence outcome or status.");
            }
            if (!finished.compareAndSet(false, true)) {
                throw new IllegalStateException("Request evidence attempt already finished.");
            }
            if (outcome.error()) {
                emit(at, Phase.ERROR, outcome, elapsed, status);
            }
            emit(at, Phase.OUTCOME, outcome, elapsed, status);
        }

        private void emit(Instant at, Phase phase, Outcome outcome, long elapsed, int status) {
            sink.accept(new RequestEvidenceEvent(at, id, direction, operation, destination, phase, outcome, elapsed, status));
        }
    }
}
