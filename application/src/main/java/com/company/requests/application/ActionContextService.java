package com.company.requests.application;

import com.company.requests.application.CurrentAccess.ActionTarget;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Protected action context and the new-intent precondition shared by existing-request commands. */
public final class ActionContextService {
    private final CurrentAccess access;
    private final PolicyGuardStore guards;
    private final RequestStore requests;
    private final GuardedRelease release;
    private final ObservationCodec observations;
    private final TrustedTime clock;

    public ActionContextService(CurrentAccess access, PolicyGuardStore guards, RequestStore requests,
                                GuardedRelease release, ObservationCodec observations, TrustedTime clock) {
        this.access = Objects.requireNonNull(access);
        this.guards = Objects.requireNonNull(guards);
        this.requests = Objects.requireNonNull(requests);
        this.release = Objects.requireNonNull(release);
        this.observations = Objects.requireNonNull(observations);
        this.clock = Objects.requireNonNull(clock);
    }

    public sealed interface Reply permits View, Rejection { }

    public record View(RequestRef ref, Action action, RequestState state, Field field, String value,
                       String expectedRevision) implements Reply {
        @Override
        public String toString() {
            return "ActionContextView[protected]";
        }
    }

    public sealed interface Verification permits Verified, Rejection { }

    public record Verified(Request request, PolicyGuard guard, Admission admission) implements Verification { }

    public enum Rejection implements Reply, Verification, Inspection {
        CONCEALED, DENIED, ILLEGAL_STATE, MALFORMED, STALE
    }

    /** Stage guarded response bytes before returning control to the transport. */
    public byte[] release(AuthenticatedActor actor, RequestRef ref, Action action, Function<Reply, byte[]> encode) {
        requireExistingAction(action);
        var slot = ReleaseBuffer.actionContext();
        var issuedAt = new AtomicReference<Instant>();
        GuardedRelease.Outcome outcome;
        try {
            outcome = release.handoff(actor.organization(), guard -> access.requireActionScope(actor, guard),
                    () -> fresh(issuedAt), context -> {
                        var current = inspect(actor, context.guard(), ref, action);
                        var reply = reply(actor, ref, action, context, current, issuedAt.get());
                        return Optional.of(new GuardedRelease.Prepared(
                                new GuardedRelease.Payload(encode.apply(reply), Optional.empty()),
                                checked -> current.equals(inspect(actor, checked.guard(), ref, action))));
                    }, slot);
        } catch (TransactionBusyException | TransactionResourceUnavailableException | CommitOutcomeUnknownException failure) {
            throw new AuthorityUnavailableException();
        }
        if (outcome != GuardedRelease.Outcome.HANDED_OFF) {
            throw new AuthorityUnavailableException();
        }
        return slot.take().orElseThrow(AuthorityUnavailableException::new);
    }

    /**
     * Call on the owned workflow transaction, after receipt arbitration for a new intent.
     * The exclusive guard acquired here belongs to the caller's transaction through effect commit.
     * Trusted exact replay follows the original receipt and its fresh disclosure gate.
     */
    public Verification verifyNewIntent(AuthenticatedActor actor, RequestRef ref, Action action, String token) {
        requireExistingAction(action);
        var guard = guards.lockExclusive(actor.organization()).orElseThrow(AuthorityUnavailableException::new);
        var admission = access.requireActionScope(actor, guard);
        var current = inspect(actor, guard, ref, action);
        if (current instanceof Rejection rejection) {
            return rejection;
        }
        var allowed = (Allowed) current;
        var exact = ObservationBinding.digest(actor, admission, guard, action, allowed.target(), allowed.value());
        return switch (observations.verify(token, exact, now())) {
            case MALFORMED -> Rejection.MALFORMED;
            case STALE -> Rejection.STALE;
            case MATCH -> eligible(allowed.target().request(), action)
                    ? new Verified(allowed.target().request(), guard, admission) : Rejection.ILLEGAL_STATE;
        };
    }

    private Reply reply(AuthenticatedActor actor, RequestRef ref, Action action, GuardedRelease.Context context,
                        Inspection current, Instant issuedAt) {
        if (current instanceof Rejection rejection) {
            return rejection;
        }
        var allowed = (Allowed) current;
        var request = allowed.target().request();
        if (!eligible(request, action)) {
            return Rejection.ILLEGAL_STATE;
        }
        var binding = ObservationBinding.digest(actor, context.admission(), context.guard(), action,
                allowed.target(), allowed.value());
        return new View(ref, action, request.state(), allowed.target().materialField(), allowed.value(),
                observations.seal(binding, issuedAt));
    }

    private Inspection inspect(AuthenticatedActor actor, PolicyGuard guard, RequestRef ref, Action action) {
        Optional<ActionTarget> found;
        try {
            found = access.actionTarget(actor, guard, ref, action);
        } catch (AccessDeniedException denied) {
            return Rejection.DENIED;
        }
        if (found.isEmpty()) {
            return Rejection.CONCEALED;
        }
        var target = found.orElseThrow();
        String material = switch (target.materialField()) {
            case BODY -> requests.findRetainedBody(guard.organization(), ref)
                    .map(RequestBody::value).orElseThrow(AuthorityUnavailableException::new);
            case NAME -> target.request().name();
            default -> throw new IllegalStateException("Unknown action context field.");
        };
        return new Allowed(target, material);
    }

    private boolean fresh(AtomicReference<Instant> issuedAt) {
        var now = now();
        issuedAt.compareAndSet(null, now);
        var start = issuedAt.get();
        return !now.isBefore(start) && now.isBefore(start.plus(ObservationCodec.WINDOW));
    }

    private Instant now() {
        return Optional.ofNullable(clock.now()).orElseThrow(AuthorityUnavailableException::new);
    }

    private static boolean eligible(Request request, Action action) {
        try {
            Lifecycle.next(request.state(), action);
            return true;
        } catch (Lifecycle.IllegalState ineligible) {
            return false;
        }
    }

    private static void requireExistingAction(Action action) {
        if (Objects.requireNonNull(action) == Action.CREATE) {
            throw new IllegalArgumentException("Create has no action context.");
        }
    }

    private sealed interface Inspection permits Allowed, Rejection { }

    private record Allowed(ActionTarget target, String value) implements Inspection { }
}
