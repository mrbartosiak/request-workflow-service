package com.company.requests.application.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** An internal lookup port. The composition root can give the matching capability to an authenticated operator adapter. */
public final class RestrictedEvidenceLookup {
    public static final class Capability { }

    @FunctionalInterface
    public interface Source {
        List<RequestEvidenceEvent> find(UUID correlationId);
    }

    public record Access(Instant at, boolean permitted) { }

    private final Clock clock;
    private final Capability operator;
    private final Source source;
    private final Consumer<Access> accessLog;

    public RestrictedEvidenceLookup(Clock clock, Capability operator, Source source, Consumer<Access> accessLog) {
        this.clock = Objects.requireNonNull(clock);
        this.operator = Objects.requireNonNull(operator);
        this.source = Objects.requireNonNull(source);
        this.accessLog = Objects.requireNonNull(accessLog);
    }

    public List<RequestEvidenceEvent> find(Capability presented, UUID correlationId) {
        boolean permitted = presented == operator;
        accessLog.accept(new Access(clock.instant(), permitted));
        if (!permitted) {
            throw new SecurityException("Request evidence lookup was denied.");
        }
        return List.copyOf(source.find(Objects.requireNonNull(correlationId)));
    }
}
