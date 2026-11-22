package com.company.requests.application;

/** Appends one pending obligation on the owned command transaction under its exclusive guard. */
@FunctionalInterface
public interface OutboxAppendPort {
    void append(OutboundObligation obligation);
}
