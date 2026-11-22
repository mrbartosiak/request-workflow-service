package com.company.requests.application;

/** Safe application outcomes for invalid or expired operation keys. */
public final class OperationKeyException extends RuntimeException {
    public enum Reason { INVALID, EXPIRED }

    private final Reason reason;

    OperationKeyException(Reason reason) {
        super(switch (reason) {
            case INVALID -> "Invalid operation key.";
            case EXPIRED -> "Operation key expired.";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
