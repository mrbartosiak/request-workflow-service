package com.company.requests.application;

/** A protected boundary could not establish usable authority. */
public final class AuthorityUnavailableException extends RuntimeException {
    public AuthorityUnavailableException() {
        super("Current authority is unavailable.");
    }
}
