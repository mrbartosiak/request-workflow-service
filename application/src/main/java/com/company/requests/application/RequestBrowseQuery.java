package com.company.requests.application;

import com.company.requests.application.BrowseCursorCodec.InvalidCursor;
import com.company.requests.application.BrowseCursorCodec.Position;
import com.company.requests.application.BrowseCursorCodec.Window;
import com.company.requests.application.CurrentAccess.ReadField;
import com.company.requests.application.CurrentAccess.ReadTarget;
import com.company.requests.application.RequestBrowseStore.Candidate;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Release a current page and derive its continuation from an independently authorized lookahead. */
public final class RequestBrowseQuery {
    private static final int CANDIDATE_BATCH = 64;

    private final CurrentAccess access;
    private final RequestBrowseStore candidates;
    private final GuardedRelease release;
    private final BrowseCursorCodec cursors;
    private final TrustedTime clock;

    public RequestBrowseQuery(CurrentAccess access, RequestBrowseStore candidates, GuardedRelease release,
                              BrowseCursorCodec cursors, TrustedTime clock) {
        this.access = Objects.requireNonNull(access);
        this.candidates = Objects.requireNonNull(candidates);
        this.release = Objects.requireNonNull(release);
        this.cursors = Objects.requireNonNull(cursors);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Selection(Optional<String> name, Optional<RequestState> state, int size) {
        public Selection {
            Objects.requireNonNull(name).ifPresent(value -> ExactOperationText.encode(value, 256));
            Objects.requireNonNull(state);
            if (size < 1 || size > 50) {
                throw new IllegalArgumentException("Browse size must be between 1 and 50.");
            }
        }

        @Override
        public String toString() {
            return "BrowseSelection[protected]";
        }
    }

    public record Item(RequestRef ref, Optional<RequestType> type, Optional<String> name,
                       Optional<RequestState> state, Optional<PublicationId> publicationId) {
        @Override
        public String toString() {
            return "BrowseItem[protected]";
        }
    }

    public record View(List<Item> items, Optional<String> nextCursor) {
        public View {
            items = List.copyOf(items);
        }

        @Override
        public String toString() {
            return "BrowseView[protected]";
        }
    }

    public byte[] release(AuthenticatedActor actor, Selection selection, Optional<String> cursor,
                          Function<View, byte[]> encode) {
        var continuation = cursor.map(token -> cursors.open(token, actor, selection));
        var window = continuation.map(Position::window).orElseGet(() -> Window.starting(clock.now()));
        if (!fresh(window, continuation.isPresent())) {
            throw new AuthorityUnavailableException();
        }
        return release.handoffRead(actor, context -> page(actor, context, selection, continuation),
                page -> encode.apply(view(actor, selection, window, page)),
                ReleaseBuffer.requestRead(), () -> fresh(window, continuation.isPresent()));
    }

    private boolean fresh(Window window, boolean continuing) {
        boolean fresh = window.contains(clock.now());
        if (!fresh && continuing) {
            throw new InvalidCursor();
        }
        return fresh;
    }

    private Page page(AuthenticatedActor actor, GuardedRelease.Context context, Selection selection,
                      Optional<Position> continuation) {
        long generation = context.admission().writer().generation();
        if (continuation.filter(position -> position.generation() != generation).isPresent()) {
            throw new InvalidCursor();
        }
        var items = new ArrayList<Item>(selection.size());
        var below = continuation.map(Position::below);
        Candidate lastReturned = null;
        while (true) {
            context.requireWithinBudget();
            var batch = candidates.findCandidates(context.guard(), below, CANDIDATE_BATCH);
            for (var candidate : batch) {
                context.requireWithinBudget();
                var target = access.readBrowseTarget(actor, context, candidate.ref(),
                        selection.name().isPresent(), selection.state().isPresent());
                if (target.isEmpty() || !matches(target.orElseThrow(), selection)) {
                    continue;
                }
                if (items.size() == selection.size()) {
                    return new Page(items, generation, Optional.of(new More(lastReturned, candidate)));
                }
                items.add(item(target.orElseThrow()));
                lastReturned = candidate;
            }
            if (batch.size() < CANDIDATE_BATCH) {
                return new Page(items, generation, Optional.empty());
            }
            below = Optional.of(batch.getLast());
        }
    }

    private static boolean matches(ReadTarget target, Selection selection) {
        var request = target.request();
        // The Create and Browse boundaries preserve valid UTF-8 exactly, so equality preserves its bytes.
        return selection.name().filter(name -> !name.equals(request.name())).isEmpty()
                && selection.state().filter(state -> state != request.state()).isEmpty();
    }

    private static Item item(ReadTarget target) {
        var request = target.request();
        var fields = target.fields();
        return new Item(request.ref(), Optional.of(request.type()).filter(ignored -> fields.contains(ReadField.TYPE)),
                Optional.of(request.name()).filter(ignored -> fields.contains(ReadField.NAME)),
                Optional.of(request.state()).filter(ignored -> fields.contains(ReadField.STATE)),
                target.publicationId());
    }

    private View view(AuthenticatedActor actor, Selection selection, Window window, Page page) {
        return new View(page.items(), page.more().map(more ->
                cursors.seal(actor, selection, new Position(page.generation(), more.lastReturned(), window))));
    }

    private record Page(List<Item> items, long generation, Optional<More> more) { }

    /** Include lookahead identity in the guarded recheck without including it in the cursor or response. */
    private record More(Candidate lastReturned, Candidate lookahead) { }
}
