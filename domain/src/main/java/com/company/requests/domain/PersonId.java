package com.company.requests.domain;

/** Stable originating person supplied by trusted identity resolution. */
public record PersonId(String value) {
    public PersonId {
        RequiredText.check(value, "person");
    }
}
