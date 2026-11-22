package com.company.requests.application.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.company.requests.application.evidence.RequestEvidenceEvent.*;

/** Emits one start and one outcome, plus an error for each failed request. */
public final class RequestEvidenceRecorder {
    private final Clock clock;
    private final RequestEvidenceStorage storage;

    public RequestEvidenceRecorder(Clock clock, Consumer<RequestEvidenceEvent> sink) {
        this(clock, immediate(sink));
    }

    public RequestEvidenceRecorder(Clock clock, RequestEvidenceStorage storage) {
        this.clock = Objects.requireNonNull(clock);
        this.storage = Objects.requireNonNull(storage);
    }

    public boolean available(Direction direction) {
        return storage.available(direction);
    }

    public Optional<ReservedAttempt> reserve(Direction direction, Operation operation, Destination destination) {
        Objects.requireNonNull(direction);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(destination);
        return storage.reserve(direction).map(slot -> new ReservedAttempt(slot, direction, operation, destination));
    }

    public Optional<Attempt> tryStart(Direction direction, Operation operation, Destination destination) {
        return reserve(direction, operation, destination).map(reserved -> {
            try {
                return reserved.start();
            } catch (RuntimeException | Error failure) {
                reserved.close();
                throw failure;
            }
        });
    }

    public Attempt start(Direction direction, Operation operation, Destination destination) {
        return tryStart(direction, operation, destination).orElseThrow(RequestEvidenceStorage.Unavailable::new);
    }

    /** The connector may report a request that failed before the application could start a record. */
    public void recordUndispatchedIngress(long elapsedNanos, int status) {
        Outcome outcome = status >= 400 ? Outcome.TRANSPORT_REJECTED : Outcome.COMPLETED;
        if (status == 408) {
            outcome = Outcome.TRANSPORT_TIMEOUT;
        }
        edge(Operation.UNMATCHED_HTTP, Math.max(elapsedNanos, 0), status, outcome);
    }

    /** Records the rejected ingress even when service capacity is restricted. */
    public UUID rejectIngress(Operation operation) {
        return edge(operation, 0, 503, Outcome.EVIDENCE_UNAVAILABLE);
    }

    private UUID edge(Operation operation, long elapsed, int status, Outcome outcome) {
        var context = new Context(UUID.randomUUID(), Direction.INGRESS, operation, Destination.REQUESTS_API);
        Instant finished = clock.instant();
        var start = context.event(finished.minusNanos(elapsed), Phase.START, Outcome.STARTED, 0, 0);
        var end = context.end(finished, outcome, elapsed, status);
        storage.edge(outcome.error() ? List.of(start, end.get(0), end.get(1)) : List.of(start, end.getFirst()));
        return context.id();
    }

    private record Context(UUID id, Direction direction, Operation operation, Destination destination) {
        RequestEvidenceEvent event(Instant at, Phase phase, Outcome outcome, long elapsed, int status) {
            return new RequestEvidenceEvent(at, id, direction, operation, destination, phase, outcome, elapsed, status);
        }

        List<RequestEvidenceEvent> end(Instant at, Outcome outcome, long elapsed, int status) {
            var finalEvent = event(at, Phase.OUTCOME, outcome, elapsed, status);
            return outcome.error()
                    ? List.of(event(at, Phase.ERROR, outcome, elapsed, status), finalEvent)
                    : List.of(finalEvent);
        }
    }

    public final class ReservedAttempt implements AutoCloseable {
        private final RequestEvidenceStorage.Reservation slot;
        private final Context context;
        private boolean started;
        private boolean closed;

        private ReservedAttempt(RequestEvidenceStorage.Reservation slot, Direction direction, Operation operation,
                                Destination destination) {
            this.slot = slot;
            this.context = new Context(UUID.randomUUID(), direction, operation, destination);
        }

        public synchronized Attempt start() {
            if (started || closed) {
                throw new IllegalStateException("Request evidence reservation is no longer available.");
            }
            started = true;
            var attempt = new Attempt(this);
            slot.start(context.event(clock.instant(), Phase.START, Outcome.STARTED, 0, 0));
            return attempt;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                slot.close();
            }
        }
    }

    public final class Attempt {
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long startedAt = System.nanoTime();
        private final ReservedAttempt reservation;

        private Attempt(ReservedAttempt reservation) {
            this.reservation = reservation;
        }

        public UUID correlationId() {
            return reservation.context.id();
        }

        public void finish(Outcome outcome, int status) {
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.STARTED || status != 0 && (status < 100 || status > 599)) {
                throw new IllegalArgumentException("Invalid request evidence outcome or status.");
            }
            if (!finished.compareAndSet(false, true)) {
                throw new IllegalStateException("Request evidence attempt already finished.");
            }
            long elapsed = Math.max(System.nanoTime() - startedAt, 0);
            try (reservation) {
                reservation.slot.finish(reservation.context.end(clock.instant(), outcome, elapsed, status));
            }
        }
    }

    private static RequestEvidenceStorage immediate(Consumer<RequestEvidenceEvent> sink) {
        Objects.requireNonNull(sink);
        return new RequestEvidenceStorage() {
            @Override
            public boolean available(Direction direction) {
                return true;
            }

            @Override
            public Optional<Reservation> reserve(Direction direction) {
                return Optional.of(new Reservation() {
                    @Override
                    public void start(RequestEvidenceEvent event) {
                        sink.accept(event);
                    }

                    @Override
                    public void finish(List<RequestEvidenceEvent> events) {
                        events.forEach(sink);
                    }

                    @Override
                    public void close() { }
                });
            }

            @Override
            public boolean edge(List<RequestEvidenceEvent> events) {
                events.forEach(sink);
                return true;
            }
        };
    }
}
