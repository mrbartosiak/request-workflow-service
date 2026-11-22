package com.company.requests.integration;

import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Protected identities common to the independent registry and workflow projection. */
public final class ExternalMapping {
    private ExternalMapping() { }

    public record Scope(String sourceSystem, String tenant, String identifierType) {
        public Scope {
            requireIdentity(sourceSystem);
            requireIdentity(tenant);
            requireIdentity(identifierType);
        }

        @Override public String toString() { return "ExternalMapping.Scope[protected]"; }
    }

    public record Key(Scope scope, byte[] externalId) {
        public Key {
            Objects.requireNonNull(scope);
            externalId = Objects.requireNonNull(externalId).clone();
        }

        @Override public byte[] externalId() { return externalId.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && scope.equals(key.scope) && Arrays.equals(externalId, key.externalId);
        }

        @Override public int hashCode() { return 31 * scope.hashCode() + Arrays.hashCode(externalId); }

        @Override public String toString() { return "ExternalMapping.Key[protected]"; }
    }

    public record Target(OrganizationId organization, RequestType type, RequestRef ref) {
        private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{32}");

        public Target {
            Objects.requireNonNull(organization);
            Objects.requireNonNull(type);
            if (!REFERENCE.matcher(Objects.requireNonNull(ref).value()).matches()) {
                throw new IllegalArgumentException("Invalid mapping target reference.");
            }
        }

        @Override public String toString() { return "ExternalMapping.Target[protected]"; }
    }

    public record Publication(String namespace, PublicationId id) {
        public Publication {
            requireIdentity(namespace);
            Objects.requireNonNull(id);
        }

        @Override public String toString() { return "ExternalMapping.Publication[protected]"; }
    }

    public record Association(Target target, CreationOrder creation, Optional<Publication> publication) {
        public Association {
            Objects.requireNonNull(target);
            Objects.requireNonNull(creation);
            Objects.requireNonNull(publication);
        }

        @Override public String toString() { return "ExternalMapping.Association[protected]"; }
    }

    /** Stable independent identity and the writer first admitted to project this binding. */
    public record Binding(UUID id, Key key, Association association, WriterEpoch writer) {
        public Binding {
            Objects.requireNonNull(id);
            Objects.requireNonNull(key);
            Objects.requireNonNull(association);
            Objects.requireNonNull(writer);
        }

        @Override public String toString() { return "ExternalMapping.Binding[protected]"; }
    }

    private static void requireIdentity(String value) {
        if (Objects.requireNonNull(value).isBlank() || value.indexOf(0) >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Invalid mapping scope or namespace identifier.");
        }
    }
}
