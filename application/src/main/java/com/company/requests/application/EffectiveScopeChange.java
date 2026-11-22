package com.company.requests.application;

import com.company.requests.application.EffectiveScopeStore.Assignment;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.EffectiveScopeStore.GrantKey;
import com.company.requests.application.EffectiveScopeStore.GrantOverride;
import com.company.requests.application.EffectiveScopeStore.Visibility;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.domain.RequestRef;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** Canonical v1 delta including the operator and exact old and new authority values. */
public record EffectiveScopeChange(PolicyGuard before, AuthenticatedActor operator, Edit edit) {
    public EffectiveScopeChange {
        Objects.requireNonNull(before);
        Objects.requireNonNull(operator);
        Objects.requireNonNull(edit);
        if (!before.organization().equals(operator.organization()) || operator.person().isEmpty()) {
            throw new AccessDeniedException();
        }
        if (edit instanceof SetGrant update && !before.organization().equals(update.key().actor().organization())) {
            throw new AccessDeniedException();
        }
    }

    public sealed interface Edit permits SetGrant, Move, SetVisibility { }

    public record SetGrant(GrantKey key, Optional<GrantOverride> prior, boolean wasAllowed, boolean allowed)
            implements Edit {
        public SetGrant {
            Objects.requireNonNull(key);
            Objects.requireNonNull(prior);
            if (wasAllowed == allowed || prior.map(GrantOverride::allowed).orElse(wasAllowed) != wasAllowed) {
                throw new IllegalArgumentException("A grant update must change its current effective value.");
            }
        }
    }

    public record Move(RequestRef ref, Assignment old, Assignment next) implements Edit {
        public Move {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(old);
            Objects.requireNonNull(next);
            if (next.version() != Math.incrementExact(old.version()) || next.person().equals(old.person())) {
                throw new IllegalArgumentException("An assignment move must change person and advance version.");
            }
        }
    }

    public record SetVisibility(RequestRef ref, Visibility old, Visibility next) implements Edit {
        public SetVisibility {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(old);
            Objects.requireNonNull(next);
            if (next.version() != Math.incrementExact(old.version())
                    || (next.assigneeOnly() == old.assigneeOnly() && next.fields().equals(old.fields()))) {
                throw new IllegalArgumentException("A visibility change must advance version and change a fact.");
            }
        }
    }

    @Override
    public String toString() {
        return "EffectiveScopeChange[protected]";
    }

    public Delta delta() {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            text(data, "requests.effective-scope.v1");
            text(data, before.organization().value());
            data.writeLong(before.policyVersion());
            data.writeLong(Math.incrementExact(before.policyVersion()));
            actor(data, operator);
            switch (edit) {
                case SetGrant update -> {
                    text(data, "SET_GRANT");
                    actor(data, update.key().actor());
                    text(data, update.key().grant().name());
                    text(data, update.key().type().map(Enum::name).orElse(""));
                    data.writeLong(update.prior().map(GrantOverride::version).orElse(0L));
                    data.writeBoolean(update.wasAllowed());
                    data.writeBoolean(update.allowed());
                }
                case Move move -> {
                    text(data, "MOVE_ASSIGNMENT");
                    text(data, move.ref().value());
                    assignment(data, move.old());
                    assignment(data, move.next());
                }
                case SetVisibility change -> {
                    text(data, "SET_VISIBILITY");
                    text(data, change.ref().value());
                    visibility(data, change.old());
                    visibility(data, change.next());
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not encode scope change.", failure);
        }
        return new Delta(bytes.toByteArray());
    }

    private static void actor(DataOutputStream data, AuthenticatedActor actor) throws IOException {
        text(data, actor.issuer());
        text(data, actor.principal());
        text(data, actor.kind().name());
        text(data, actor.person().map(person -> person.value()).orElse(""));
    }

    private static void assignment(DataOutputStream data, Assignment assignment) throws IOException {
        text(data, assignment.person().value());
        data.writeLong(assignment.version());
    }

    private static void visibility(DataOutputStream data, Visibility visibility) throws IOException {
        data.writeLong(visibility.version());
        data.writeBoolean(visibility.assigneeOnly());
        for (Field field : Field.values()) {
            data.writeBoolean(visibility.fields().contains(field));
        }
    }

    private static void text(DataOutputStream data, String value) throws IOException {
        if (value.indexOf(0) >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Scope changes require exact UTF-8 text.");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }
}
