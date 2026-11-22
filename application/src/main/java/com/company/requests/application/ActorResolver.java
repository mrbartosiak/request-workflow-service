package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import java.util.Optional;

/** Resolves edge credentials into a scope-bound stable directory actor. */
public interface ActorResolver {
    Optional<AuthenticatedActor> resolve(Credential credential, OrganizationId organization);

    record Credential(String issuer, String audience, String subject, String secret) {
        @Override
        public String toString() {
            return "Credential[protected]";
        }
    }
}
