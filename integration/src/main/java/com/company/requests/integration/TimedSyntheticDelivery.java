package com.company.requests.integration;

import com.company.requests.application.evidence.RequestEvidenceStorage;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.OutboundEvidenceRecorder.Result;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.SendResult;
import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Bounds the worker's wait and retains its destination slot until the call has actually stopped. */
public final class TimedSyntheticDelivery implements Connector, AutoCloseable {
    private final Connector connector;
    private final OutboundEvidenceRecorder.Reservation lookupEvidence;
    private Thread invocationThread;

    public TimedSyntheticDelivery(Connector connector) {
        this(connector, null);
    }

    public TimedSyntheticDelivery(Connector connector, OutboundEvidenceRecorder.Reservation lookupEvidence) {
        this.connector = Objects.requireNonNull(connector);
        this.lookupEvidence = lookupEvidence;
    }

    @Override
    public SendResult sendWithThrottle(Invocation invocation) {
        return invoke(() -> connector.sendWithThrottle(invocation), SendResult.unknown(), false).value();
    }

    public SendResult sendWithThrottle(Invocation invocation, OutboundEvidenceRecorder.Reservation reservation) {
        if (Thread.currentThread().isInterrupted()) {
            return SendResult.unknown();
        }
        OutboundEvidenceRecorder.Attempt attempt;
        try {
            // Spool persistence precedes the I/O timer. Once recorded, the invocation is committed to the sender.
            attempt = reservation.start();
        } catch (RequestEvidenceStorage.Unavailable unavailable) {
            return SendResult.unknown();
        }
        var observed = invoke(() -> connector.sendWithThrottle(invocation), SendResult.unknown(), true);
        return finish(attempt, invocation, observed);
    }

    private <T> Observed<T> invoke(Supplier<T> operation, T unknown, boolean committedSend) {
        if (invocationThread != null) {
            throw new IllegalStateException("A timed delivery is scoped to a single attempt.");
        }
        var request = new CompletableFuture<T>();
        invocationThread = Thread.ofVirtual().name("outbox-connector").unstarted(() -> {
            if (!committedSend && Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                request.complete(operation.get());
            } catch (Throwable failure) {
                request.completeExceptionally(failure);
            }
        });
        long started = System.nanoTime();
        invocationThread.start();
        try {
            long remaining = DispatchScheduler.IO_TIMEOUT.toNanos() - (System.nanoTime() - started);
            return new Observed<>(request.get(remaining, TimeUnit.NANOSECONDS), null);
        } catch (InterruptedException interrupted) {
            invocationThread.interrupt();
            Thread.currentThread().interrupt();
            return new Observed<>(unknown, Result.UNKNOWN);
        } catch (TimeoutException timeout) {
            invocationThread.interrupt();
            return new Observed<>(unknown, Result.TIMEOUT);
        } catch (ExecutionException ambiguous) {
            return new Observed<>(unknown, timedOut(ambiguous.getCause()) ? Result.TIMEOUT : Result.FAILED);
        }
    }

    private static boolean timedOut(Throwable failure) {
        for (int depth = 0; depth < 8 && failure != null; depth++, failure = failure.getCause()) {
            if (failure instanceof TimeoutException || failure instanceof SocketTimeoutException
                    || failure instanceof SQLTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Evidence> send(Invocation invocation) {
        return sendWithThrottle(invocation).evidence();
    }

    @Override
    public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
        if (lookupEvidence == null) {
            return invoke(() -> connector.lookup(invocation, fence), Optional.<Evidence>empty(), false).value();
        }
        if (Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }
        try {
            var attempt = lookupEvidence.start();
            var observed = invoke(() -> connector.lookup(invocation, fence), Optional.<Evidence>empty(), true);
            var exact = observed.value() == null ? Optional.<Evidence>empty()
                    : SyntheticDelivery.exact(invocation, observed.value());
            var outcome = observed.failure();
            attempt.finish(outcome != null ? outcome : exact.isPresent() ? Result.LOOKUP_COMPLETED : Result.UNKNOWN);
            return exact;
        } catch (RequestEvidenceStorage.Unavailable unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (invocationThread == null) {
            return;
        }
        boolean interrupted = false;
        for (;;) {
            try {
                invocationThread.join();
                break;
            } catch (InterruptedException stop) {
                invocationThread.interrupt();
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Observed<T>(T value, Result failure) { }

    private static SendResult finish(OutboundEvidenceRecorder.Attempt attempt, Invocation invocation,
                                     Observed<SendResult> observed) {
        var report = observed.value();
        var exact = report == null ? Optional.<Evidence>empty()
                : SyntheticDelivery.exact(invocation, report.evidence());
        var result = observed.failure();
        if (result == null) {
            result = exact.map(value -> switch (value.observation()) {
                case CONFIRMED -> Result.CONFIRMED;
                case ACCEPTED -> Result.ACCEPTED;
                case TRANSIENT, PERMANENT -> Result.REJECTED;
                case ABSENT -> Result.UNKNOWN;
            }).orElse(Result.UNKNOWN);
        }
        try {
            attempt.finish(result);
        } catch (RequestEvidenceStorage.Unavailable gap) {
            return SendResult.unknown();
        }
        return report == null ? SendResult.unknown() : new SendResult(exact, report.retryAfter());
    }
}
