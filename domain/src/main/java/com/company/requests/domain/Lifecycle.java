package com.company.requests.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Shared lifecycle for an authorized, current new intent. Effects are values for the caller to commit. */
public final class Lifecycle {
    private static final Map<RequestState, Map<Action, RequestState>> NEXT = Map.of(
            RequestState.CREATED, Map.of(Action.EDIT, RequestState.CREATED,
                    Action.DELETE, RequestState.DELETED, Action.VERIFY, RequestState.VERIFIED),
            RequestState.VERIFIED, Map.of(Action.EDIT, RequestState.VERIFIED,
                    Action.ACCEPT, RequestState.ACCEPTED, Action.REJECT, RequestState.REJECTED),
            RequestState.ACCEPTED, Map.of(Action.REJECT, RequestState.REJECTED,
                    Action.PUBLISH, RequestState.PUBLISHED));

    private Lifecycle() {}

    public static RequestState nextFromStart(Action action) {
        Objects.requireNonNull(action, "action");
        if (action == Action.CREATE) {
            return RequestState.CREATED;
        }
        throw new IllegalState(action);
    }

    public static RequestState next(RequestState current, Action action) {
        Objects.requireNonNull(current, "current state");
        Objects.requireNonNull(action, "action");
        RequestState next = NEXT.getOrDefault(current, Map.of()).get(action);
        if (next == null) {
            throw new IllegalState(action);
        }
        return next;
    }

    public static Created create(Creation command, TransitionFact.Occurrence occurrence) {
        Request request = new Request(command.ref(), command.type(), command.organization(), command.name(),
                nextFromStart(Action.CREATE), 1, 1, command.creationOrder(), command.originatingPersonId(),
                Optional.empty());
        TransitionFact fact = new TransitionFact(request.ref(), 1, 1, Action.CREATE, null, request.state(),
                occurrence);
        return new Created(request, command.body(), fact);
    }

    public static Edited edit(Request request, RequestBody body) {
        Objects.requireNonNull(body, "replacement body");
        RequestState state = next(request.state(), Action.EDIT);
        return new Edited(request.advance(state, request.lastSequence(), request.publicationId()), body);
    }

    /**
     * The caller checks applicable scoped grants and the distinct stable-person rule for Accept.
     * It supplies the reserved number for Publish and occurrence metadata for the history row.
     */
    public static Transitioned transition(Request request, TransitionCommand command,
                                          TransitionFact.Occurrence occurrence) {
        Action action = command.action();
        RequestState state = next(request.state(), action);
        long sequence = Math.incrementExact(request.lastSequence());
        Optional<PublicationId> publication = command instanceof Publish publish
                ? Optional.of(publish.id()) : request.publicationId();
        Optional<TransitionReason> reason = switch (command) {
            case Delete delete -> Optional.of(delete.reason());
            case Reject reject -> Optional.of(reject.reason());
            default -> Optional.empty();
        };
        Request updated = request.advance(state, sequence, publication);
        TransitionFact fact = new TransitionFact(updated.ref(), sequence, updated.revision(), action,
                request.state(), state, occurrence);
        return new Transitioned(updated, fact, reason);
    }

    public record Creation(RequestRef ref, RequestType type, OrganizationId organization, String name,
                           RequestBody body, CreationOrder creationOrder, PersonId originatingPersonId) {
        public Creation {
            Objects.requireNonNull(body, "body");
        }

        @Override
        public String toString() {
            return "Creation[type=" + type + "]";
        }
    }

    public sealed interface TransitionCommand permits Verify, Accept, Reject, Delete, Publish {
        Action action();
    }

    public record Verify() implements TransitionCommand {
        @Override
        public Action action() {
            return Action.VERIFY;
        }
    }

    public record Accept() implements TransitionCommand {
        @Override
        public Action action() {
            return Action.ACCEPT;
        }
    }

    public record Reject(TransitionReason reason) implements TransitionCommand {
        public Reject {
            Objects.requireNonNull(reason, "reject reason");
        }

        @Override
        public Action action() {
            return Action.REJECT;
        }
    }

    public record Delete(TransitionReason reason) implements TransitionCommand {
        public Delete {
            Objects.requireNonNull(reason, "delete reason");
        }

        @Override
        public Action action() {
            return Action.DELETE;
        }
    }

    public record Publish(PublicationId id) implements TransitionCommand {
        public Publish {
            Objects.requireNonNull(id, "publication ID");
        }

        @Override
        public Action action() {
            return Action.PUBLISH;
        }
    }

    public record Created(Request request, RequestBody body, TransitionFact transition) {}

    public record Edited(Request request, RequestBody body) {}

    public record Transitioned(Request request, TransitionFact transition, Optional<TransitionReason> reason) {
        public boolean concealBody() {
            return transition.action() == Action.DELETE;
        }
    }

    public static final class IllegalState extends IllegalStateException {
        private IllegalState(Action action) {
            super("Action is ineligible: " + action);
        }
    }
}
