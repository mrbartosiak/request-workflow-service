package com.company.requests.application;

/** Ordinary denial for the submitted operation. */
public final class AccessDeniedException extends RuntimeException {
    public AccessDeniedException() {
        super("The action is denied.");
    }
}
