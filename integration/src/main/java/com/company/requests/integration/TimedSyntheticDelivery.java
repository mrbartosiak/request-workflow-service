package com.company.requests.integration;

import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.SendResult;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Bounds the worker's wait and retains its destination slot until the call has actually stopped. */
public final class TimedSyntheticDelivery implements Connector, AutoCloseable {
    private final Connector connector;
    private Thread invocationThread;

    public TimedSyntheticDelivery(Connector connector) {
        this.connector = Objects.requireNonNull(connector);
    }

    @Override
    public SendResult sendWithThrottle(Invocation invocation) {
        return invoke(() -> connector.sendWithThrottle(invocation), SendResult.unknown());
    }

    private <T> T invoke(Supplier<T> operation, T unknown) {
        if (invocationThread != null) {
            throw new IllegalStateException("A timed delivery is scoped to a single attempt.");
        }
        var request = new FutureTask<>(operation::get);
        invocationThread = Thread.ofVirtual().name("outbox-connector").unstarted(request);
        long started = System.nanoTime();
        invocationThread.start();
        try {
            long remaining = DispatchScheduler.IO_TIMEOUT.toNanos() - (System.nanoTime() - started);
            return request.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            return unknown;
        } catch (TimeoutException timeout) {
            request.cancel(true);
            return unknown;
        } catch (ExecutionException ambiguous) {
            return unknown;
        }
    }

    @Override
    public Optional<Evidence> send(Invocation invocation) {
        return sendWithThrottle(invocation).evidence();
    }

    @Override
    public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
        return invoke(() -> connector.lookup(invocation, fence), Optional.empty());
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
}
