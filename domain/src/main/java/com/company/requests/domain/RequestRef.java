package com.company.requests.domain;

/** Opaque reference supplied by the independent identity authority. */
public record RequestRef(String value) {
    public RequestRef {
        RequiredText.check(value, "request reference");
    }
}
