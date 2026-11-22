package com.company.requests.application;

/** The caller must resolve in the certified store before retrying an effect. */
public final class CommitOutcomeUnknownException extends RuntimeException {
    public CommitOutcomeUnknownException(Throwable cause) {
        super("Command commit could not be confirmed.", cause);
    }
}
