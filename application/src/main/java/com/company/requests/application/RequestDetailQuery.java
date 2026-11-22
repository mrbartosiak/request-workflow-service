package com.company.requests.application;

import com.company.requests.application.CurrentAccess.ReadField;
import com.company.requests.application.CurrentAccess.ReadTarget;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Plans and releases a masked current request view inside the shared organization guard. */
public final class RequestDetailQuery {
    private final CurrentAccess access;
    private final RequestStore requests;
    private final GuardedRelease release;

    public RequestDetailQuery(CurrentAccess access, RequestStore requests, GuardedRelease release) {
        this.access = Objects.requireNonNull(access);
        this.requests = Objects.requireNonNull(requests);
        this.release = Objects.requireNonNull(release);
    }

    public record View(RequestRef ref, Optional<RequestType> type, Optional<String> name, Optional<String> body,
                       Optional<RequestState> state, Optional<String> revision, Optional<PublicationId> publicationId) {
        @Override
        public String toString() {
            return "RequestView[protected]";
        }
    }

    public byte[] release(AuthenticatedActor actor, RequestRef ref, Function<Optional<View>, byte[]> encode) {
        return release.handoffRead(actor, context -> project(actor, context, ref), encode, ReleaseBuffer.requestRead());
    }

    private Optional<View> project(AuthenticatedActor actor, GuardedRelease.Context context, RequestRef ref) {
        return access.readTarget(actor, context, ref).map(this::project);
    }

    private View project(ReadTarget target) {
        if (target.publicationPending()) {
            throw new AuthorityUnavailableException();
        }
        var request = target.request();
        var fields = target.fields();
        var body = fields.contains(ReadField.BODY)
                ? requests.findRetainedBody(request.organization(), request.ref()).map(RequestBody::value)
                : Optional.<String>empty();
        return new View(request.ref(), Optional.of(request.type()).filter(ignored -> fields.contains(ReadField.TYPE)),
                Optional.of(request.name()).filter(ignored -> fields.contains(ReadField.NAME)), body,
                Optional.of(request.state()).filter(ignored -> fields.contains(ReadField.STATE)),
                Optional.of(Long.toString(request.revision())).filter(ignored -> fields.contains(ReadField.REVISION)),
                target.publicationId());
    }
}
