package com.company.requests.application;

/** The command or guarded release could not settle within its local SQL wait limit. */
public final class TransactionBusyException extends RuntimeException {
    public TransactionBusyException(Throwable cause) {
        super("The transaction exceeded its wait limit.", cause);
    }
}
