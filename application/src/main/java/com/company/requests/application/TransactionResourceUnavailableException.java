package com.company.requests.application;

/** A known database resource or connection failure interrupted a guarded release. */
public final class TransactionResourceUnavailableException extends RuntimeException {
    public TransactionResourceUnavailableException(Throwable cause) {
        super("Guarded release lost access to its transaction resource.", cause);
    }
}
