package com.company.requests.application;

import com.company.requests.application.RequestStore.HistoryFact;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Releases a request's ordered state changes with current scoped history permission. */
public final class RequestHistoryQuery {
    private final CurrentAccess access;
    private final RequestStore requests;
    private final GuardedRelease release;

    public RequestHistoryQuery(CurrentAccess access, RequestStore requests, GuardedRelease release) {
        this.access = Objects.requireNonNull(access);
        this.requests = Objects.requireNonNull(requests);
        this.release = Objects.requireNonNull(release);
    }

    public record Change(long sequence, Action action, RequestState from, RequestState to) { }

    public record View(RequestRef ref, List<Change> changes) {
        public View {
            changes = List.copyOf(changes);
        }

        @Override
        public String toString() {
            return "RequestHistory[protected]";
        }
    }

    public byte[] release(AuthenticatedActor actor, RequestRef ref, Function<Optional<View>, byte[]> encode) {
        return release.handoffRead(actor, context -> access.readHistoryTarget(actor, context, ref)
                .map(request -> project(request, requests.findHistory(context.guard(), ref))),
                encode, new ReleaseBuffer(ReleaseBuffer.MAX_BYTES));
    }

    private static View project(Request request, List<HistoryFact> facts) {
        if (facts.size() != request.lastSequence()) {
            throw new AuthorityUnavailableException();
        }
        var changes = new ArrayList<Change>(facts.size());
        RequestState previous = null;
        long revision = 0;
        for (var fact : facts) {
            if (fact.sequence() != changes.size() + 1L || fact.from() != previous
                    || fact.revision() <= revision || fact.revision() > request.revision()
                    || (previous == RequestState.ACCEPTED && fact.revision() - revision != 1)
                    || fact.to() != next(previous, fact.action()) || fact.to() == previous
                    || (previous == null && fact.revision() != 1)) {
                throw new AuthorityUnavailableException();
            }
            changes.add(new Change(fact.sequence(), fact.action(), fact.from(), fact.to()));
            previous = fact.to();
            revision = fact.revision();
        }
        if (previous != request.state()) {
            throw new AuthorityUnavailableException();
        }
        // Only CREATED and VERIFIED can have trailing, state-preserving edits.
        if (previous != RequestState.CREATED && previous != RequestState.VERIFIED && revision != request.revision()) {
            throw new AuthorityUnavailableException();
        }
        return new View(request.ref(), changes);
    }

    private static RequestState next(RequestState from, Action action) {
        try {
            return from == null ? Lifecycle.nextFromStart(action) : Lifecycle.next(from, action);
        } catch (Lifecycle.IllegalState invalid) {
            throw new AuthorityUnavailableException();
        }
    }
}
