package com.company.requests.application;

import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Local authority state accessed on the transaction holding the organization guard. */
public interface EffectiveScopeStore {
    enum Grant { KEY_ISSUE, CREATE, CREATE_RESULT }

    enum Field { REF, ACTION, RESULT_REVISION, STATE, NAME, BODY }

    record GrantKey(AuthenticatedActor actor, Grant grant, Optional<RequestType> type) {
        public GrantKey {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(grant);
            Objects.requireNonNull(type);
            if ((grant == Grant.KEY_ISSUE) == type.isPresent()) {
                throw new IllegalArgumentException("Only request grants require a request type.");
            }
        }
    }

    record GrantOverride(boolean allowed, long version) {
        public GrantOverride {
            if (version < 1) {
                throw new IllegalArgumentException("A grant override requires a positive version.");
            }
        }
    }

    record Assignment(PersonId person, long version) {
        public Assignment {
            Objects.requireNonNull(person);
            if (version < 1) {
                throw new IllegalArgumentException("An assignment requires a positive version.");
            }
        }
    }

    record Visibility(long version, boolean assigneeOnly, Set<Field> fields) {
        public static final Visibility INITIAL = new Visibility(0, false, Set.of(Field.values()));

        public Visibility {
            fields = Set.copyOf(fields);
            if (version < 0) {
                throw new IllegalArgumentException("A visibility version cannot be negative.");
            }
        }
    }

    void requireProjection(PolicyGuard guard, ScopeCheckpoint checkpoint);

    Optional<GrantOverride> grantOverride(PolicyGuard guard, GrantKey key);

    Optional<Assignment> assignment(PolicyGuard guard, RequestRef ref);

    Visibility visibility(PolicyGuard guard, RequestRef ref);

    void apply(PolicyGuard guard, Entry prepared, EffectiveScopeChange change);
}
