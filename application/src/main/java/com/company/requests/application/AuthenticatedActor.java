package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import java.util.Objects;
import java.util.Optional;

/** A trusted directory binding. Organization, principal and person are independent identities. */
public record AuthenticatedActor(String issuer, OrganizationId organization, String principal,
                                 Kind kind, Optional<PersonId> person) {
    public enum Kind { HUMAN, SERVICE }

    public AuthenticatedActor {
        if (Objects.requireNonNull(issuer, "issuer").isBlank()
                || Objects.requireNonNull(principal, "principal").isBlank()) {
            throw new IllegalArgumentException("Actor identifiers must be nonblank.");
        }
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(kind, "actor kind");
        Objects.requireNonNull(person, "stable person");
        if ((kind == Kind.HUMAN) != person.isPresent()) {
            throw new IllegalArgumentException("Only a human actor has a stable person in this slice.");
        }
    }

    @Override
    public String toString() {
        return "AuthenticatedActor[kind=" + kind + "]";
    }
}
