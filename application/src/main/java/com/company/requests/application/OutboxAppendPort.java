package com.company.requests.application;

/** B22 append boundary. Implementations join the transaction holding the command's exclusive guard. */
@FunctionalInterface
public interface OutboxAppendPort {
    void append(OutboundObligation obligation);
}
