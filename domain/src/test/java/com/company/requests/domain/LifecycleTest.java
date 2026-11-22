package com.company.requests.domain;

import static com.company.requests.domain.Action.*;
import static com.company.requests.domain.RequestState.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class LifecycleTest {
    private static final RequestRef REF = new RequestRef("opaque-ref");
    private static final OrganizationId ORGANIZATION = new OrganizationId("org-a");
    private static final PersonId ORIGIN = new PersonId("person-a");
    private static final CreationOrder ORDER = new CreationOrder(4, 28);
    private static final String NAME = "  Łódź 名 e\u0301  ";
    private static final String ORIGINAL_TEXT = "  Zażółć 🌱\r\n  ";
    private static final String REPLACEMENT_TEXT = "  改訂 e\u0301\n  ";
    private static final String REASON_TEXT = "  説明 🧪  ";
    private static final RequestBody ORIGINAL = new RequestBody(ORIGINAL_TEXT);
    private static final RequestBody REPLACEMENT = new RequestBody(REPLACEMENT_TEXT);
    private static final TransitionReason REASON = new TransitionReason(REASON_TEXT);
    private static final PublicationId PUBLICATION = new PublicationId(Long.MAX_VALUE);
    private static final TransitionFact.Occurrence OCCURRENCE =
            new TransitionFact.Occurrence(Instant.parse("2026-11-22T14:00:00Z"), "protected-actor");

    private static final Map<RequestState, Map<Action, RequestState>> ALLOWED = Map.of(
            CREATED, Map.of(EDIT, CREATED, DELETE, DELETED, VERIFY, VERIFIED),
            VERIFIED, Map.of(EDIT, VERIFIED, ACCEPT, ACCEPTED, REJECT, REJECTED),
            ACCEPTED, Map.of(REJECT, REJECTED, PUBLISH, PUBLISHED));

    static Stream<Arguments> stateActionPairs() {
        return Stream.of(RequestType.values()).flatMap(type ->
                Stream.of(RequestState.values()).flatMap(state ->
                        Stream.of(Action.values()).map(action -> Arguments.of(type, state, action))));
    }

    static Stream<Arguments> startActionPairs() {
        return Stream.of(RequestType.values()).flatMap(type ->
                Stream.of(Action.values()).map(action -> Arguments.of(type, action)));
    }

    @ParameterizedTest
    @MethodSource("startActionPairs")
    void startOnlyAllowsCreate(RequestType type, Action action) {
        if (action != CREATE) {
            assertThrows(Lifecycle.IllegalState.class, () -> Lifecycle.nextFromStart(action));
            return;
        }

        assertEquals(CREATED, Lifecycle.nextFromStart(action));
        Lifecycle.Created effect = create(type);
        assertEquals(new Request(REF, type, ORGANIZATION, NAME, CREATED, 1, 1,
                ORDER, ORIGIN, Optional.empty()), effect.request());
        assertEquals(NAME, effect.request().name());
        assertEquals(ORIGINAL_TEXT, effect.body().value());
        assertEquals(new TransitionFact(REF, 1, 1, CREATE, null, CREATED, OCCURRENCE),
                effect.transition());
    }

    @ParameterizedTest
    @MethodSource("stateActionPairs")
    void exactMatrixForExistingRequests(RequestType type, RequestState state, Action action) {
        Request request = requestIn(type, state);
        RequestState expected = ALLOWED.getOrDefault(state, Map.of()).get(action);
        if (expected == null) {
            assertThrows(Lifecycle.IllegalState.class, () -> Lifecycle.next(request.state(), action));
            if (action == EDIT) {
                assertThrows(Lifecycle.IllegalState.class, () -> Lifecycle.edit(request, REPLACEMENT));
            } else if (action != CREATE) {
                assertThrows(Lifecycle.IllegalState.class,
                        () -> Lifecycle.transition(request, command(action), OCCURRENCE));
            }
            assertEquals(state, request.state());
            return;
        }

        assertEquals(expected, Lifecycle.next(request.state(), action));
        Request updated = action == EDIT
                ? Lifecycle.edit(request, REPLACEMENT).request()
                : Lifecycle.transition(request, command(action), OCCURRENCE).request();
        assertEquals(expected, updated.state());
        assertEquals(request.revision() + 1, updated.revision());
        assertEquals(request.lastSequence() + (action == EDIT ? 0 : 1), updated.lastSequence());
        assertIdentityUnchanged(request, updated);
    }

    @ParameterizedTest
    @EnumSource(RequestType.class)
    void allFourTerminalPathsHaveContiguousHistory(RequestType type) {
        assertPath(type, List.of(DELETE), List.of(CREATED, DELETED));
        assertPath(type, List.of(VERIFY, REJECT), List.of(CREATED, VERIFIED, REJECTED));
        assertPath(type, List.of(VERIFY, ACCEPT, REJECT), List.of(CREATED, VERIFIED, ACCEPTED, REJECTED));
        assertPath(type, List.of(VERIFY, ACCEPT, PUBLISH), List.of(CREATED, VERIFIED, ACCEPTED, PUBLISHED));
    }

    @ParameterizedTest
    @EnumSource(RequestType.class)
    void editsPreserveExactTextAndIdentityAndDoNotAppendHistory(RequestType type) {
        Lifecycle.Created created = create(type);
        Lifecycle.Edited changed = Lifecycle.edit(created.request(), REPLACEMENT);
        assertEquals(REPLACEMENT_TEXT, changed.body().value());
        assertEquals(2, changed.request().revision());
        assertEquals(1, changed.request().lastSequence());
        assertIdentityUnchanged(created.request(), changed.request());
        assertEquals(ORIGINAL_TEXT, created.body().value());
        assertEquals(1, created.request().revision());

        Lifecycle.Transitioned verified = Lifecycle.transition(changed.request(), new Lifecycle.Verify(),
                OCCURRENCE);
        assertEquals(3, verified.transition().revision());
        assertEquals(2, verified.transition().sequence());
        Lifecycle.Edited equal = Lifecycle.edit(verified.request(), changed.body());
        assertEquals(REPLACEMENT, equal.body());
        assertEquals(VERIFIED, equal.request().state());
        assertEquals(4, equal.request().revision());
        assertEquals(2, equal.request().lastSequence());
        assertIdentityUnchanged(verified.request(), equal.request());

        Lifecycle.Transitioned rejected = Lifecycle.transition(equal.request(), new Lifecycle.Reject(REASON),
                OCCURRENCE);
        assertEquals(5, rejected.transition().revision());
        assertEquals(3, rejected.transition().sequence());
        assertEquals(REASON_TEXT, rejected.reason().orElseThrow().value());
    }

    @Test
    void bodyAndReasonCannotBeMissingOrBlank() {
        for (String blank : List.of("", " \t\n", "\u00a0\u0085\u2003\u202f")) {
            assertThrows(IllegalArgumentException.class, () -> new TransitionReason(blank));
            assertThrows(IllegalArgumentException.class, () -> new RequestBody(blank));
        }
        assertThrows(NullPointerException.class, () -> new Lifecycle.Delete(null));
        assertThrows(NullPointerException.class, () -> new Lifecycle.Reject(null));
        assertThrows(NullPointerException.class, () -> new RequestBody(null));
        assertThrows(NullPointerException.class, () -> new Lifecycle.Creation(
                REF, RequestType.HR_TICKET, ORGANIZATION, NAME, null, ORDER, ORIGIN));
        assertThrows(NullPointerException.class,
                () -> Lifecycle.edit(create(RequestType.HR_TICKET).request(), null));
    }

    @Test
    void positiveCountersAndPublicationPairAreChecked() {
        assertThrows(IllegalArgumentException.class,
                () -> restored(CREATED, 0, 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> restored(CREATED, 1, 2, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> restored(CREATED, 1, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> restored(PUBLISHED, 4, 4, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> restored(ACCEPTED, 4, 3, Optional.of(PUBLICATION)));
        assertThrows(IllegalArgumentException.class, () -> new PublicationId(0));
        assertThrows(IllegalArgumentException.class, () -> new PublicationId(-1));
        assertThrows(IllegalArgumentException.class, () -> new CreationOrder(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CreationOrder(0, 1));

        Request exhausted = restored(CREATED, Long.MAX_VALUE, 1, Optional.empty());
        assertThrows(ArithmeticException.class, () -> Lifecycle.edit(exhausted, REPLACEMENT));
        assertThrows(ArithmeticException.class,
                () -> Lifecycle.transition(exhausted, new Lifecycle.Verify(), OCCURRENCE));
    }

    @Test
    void policyMakesOnlyADecisionFromPermittedFacts() {
        record Facts(boolean approved) {}
        record RoutingIntent(String route) {}
        RoutingIntent routingIntent = new RoutingIntent("reviewed-route");
        WorkflowPolicy<Facts, RoutingIntent> policy = (action, facts) -> {
            if (action == ACCEPT && !facts.approved()) {
                return new WorkflowPolicy.Deny<>();
            }
            return new WorkflowPolicy.Allow<>(action == ACCEPT
                    ? Optional.of(routingIntent) : Optional.empty());
        };
        Request verified = requestIn(RequestType.HR_TICKET, VERIFIED);
        assertEquals(new WorkflowPolicy.Deny<>(), policy.decide(ACCEPT, new Facts(false)));
        assertEquals(new WorkflowPolicy.Allow<>(Optional.of(routingIntent)),
                policy.decide(ACCEPT, new Facts(true)));
        assertEquals(new WorkflowPolicy.Allow<>(Optional.empty()), policy.decide(VERIFY, new Facts(true)));
        assertEquals(VERIFIED, verified.state());
        assertEquals(2, verified.revision());
        assertEquals(2, verified.lastSequence());
        assertThrows(Lifecycle.IllegalState.class, () -> Lifecycle.next(CREATED, ACCEPT));
        assertEquals(ACCEPTED,
                Lifecycle.transition(verified, new Lifecycle.Accept(), OCCURRENCE).request().state());
    }

    private static Lifecycle.Created create(RequestType type) {
        return Lifecycle.create(new Lifecycle.Creation(REF, type, ORGANIZATION, NAME, ORIGINAL, ORDER, ORIGIN),
                OCCURRENCE);
    }

    private static Request requestIn(RequestType type, RequestState state) {
        long sequence = switch (state) {
            case CREATED -> 1;
            case VERIFIED, DELETED -> 2;
            case ACCEPTED, REJECTED -> 3;
            case PUBLISHED -> 4;
        };
        Optional<PublicationId> publication = state == PUBLISHED ? Optional.of(PUBLICATION) : Optional.empty();
        return new Request(REF, type, ORGANIZATION, NAME, state, sequence, sequence, ORDER, ORIGIN, publication);
    }

    private static void assertPath(RequestType type, List<Action> actions, List<RequestState> states) {
        Lifecycle.Created creation = create(type);
        Request request = creation.request();
        List<TransitionFact> history = new ArrayList<>();
        history.add(creation.transition());
        for (Action action : actions) {
            Lifecycle.Transitioned result = Lifecycle.transition(request, command(action), OCCURRENCE);
            assertIdentityUnchanged(request, result.request());
            assertEquals(action == PUBLISH ? Optional.of(PUBLICATION) : Optional.empty(),
                    result.request().publicationId());
            if (action == PUBLISH) {
                assertEquals(Long.MAX_VALUE, result.request().publicationId().orElseThrow().value());
            }
            assertEquals(action == REJECT || action == DELETE ? Optional.of(REASON) : Optional.empty(),
                    result.reason());
            assertEquals(action == DELETE, result.concealBody());
            history.add(result.transition());
            request = result.request();
        }
        assertEquals(states.size(), history.size());
        assertEquals(states.getLast(), request.state());
        for (int index = 0; index < history.size(); index++) {
            assertEquals(new TransitionFact(REF, index + 1, index + 1,
                    index == 0 ? CREATE : actions.get(index - 1),
                    index == 0 ? null : states.get(index - 1), states.get(index), OCCURRENCE), history.get(index));
        }
        assertEquals(history.size(), request.lastSequence());
    }

    private static Lifecycle.TransitionCommand command(Action action) {
        return switch (action) {
            case VERIFY -> new Lifecycle.Verify();
            case ACCEPT -> new Lifecycle.Accept();
            case REJECT -> new Lifecycle.Reject(REASON);
            case DELETE -> new Lifecycle.Delete(REASON);
            case PUBLISH -> new Lifecycle.Publish(PUBLICATION);
            case CREATE, EDIT -> throw new IllegalArgumentException("Not an existing state-transition command.");
        };
    }

    private static void assertIdentityUnchanged(Request previous, Request updated) {
        assertEquals(previous.ref(), updated.ref());
        assertEquals(previous.type(), updated.type());
        assertEquals(previous.organization(), updated.organization());
        assertEquals(previous.name(), updated.name());
        assertEquals(previous.creationOrder(), updated.creationOrder());
        assertEquals(previous.originatingPersonId(), updated.originatingPersonId());
    }

    private static Request restored(RequestState state, long revision, long lastSequence,
                                    Optional<PublicationId> publicationId) {
        return new Request(REF, RequestType.HR_TICKET, ORGANIZATION, NAME, state, revision, lastSequence,
                ORDER, ORIGIN, publicationId);
    }
}
