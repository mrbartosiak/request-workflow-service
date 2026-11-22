package com.company.requests.application;

import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.domain.Action;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Local authority state accessed on the transaction holding the organization guard. */
public interface EffectiveScopeStore {
    enum Grant {
        KEY_ISSUE, CREATE, CREATE_RESULT, ROW_READ, FIELD_READ_BODY, FIELD_READ_NAME,
        CONTEXT_EDIT, CONTEXT_VERIFY, CONTEXT_ACCEPT, CONTEXT_REJECT, CONTEXT_DELETE, CONTEXT_PUBLISH,
        EXECUTE_EDIT, EXECUTE_VERIFY, EXECUTE_ACCEPT, EXECUTE_REJECT, EXECUTE_DELETE, EXECUTE_PUBLISH,
        RESULT_READ_EDIT, RESULT_READ_VERIFY, RESULT_READ_ACCEPT, RESULT_READ_DELETE, RESULT_READ_REJECT,
        DELETED_METADATA, RESULT_READ_PUBLISH, FIELD_READ_PUBLICATION_ID,
        FIELD_READ_TYPE, FIELD_READ_STATE, FIELD_READ_REVISION;

        public static Grant context(Action action) {
            return valueOf("CONTEXT_" + action.name());
        }

        public static Grant execute(Action action) {
            return valueOf("EXECUTE_" + action.name());
        }
    }

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
