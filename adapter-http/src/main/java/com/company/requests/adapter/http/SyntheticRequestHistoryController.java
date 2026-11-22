package com.company.requests.adapter.http;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.RequestHistoryQuery;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.adapter.http.RestProblems.Code;

/** Protected history wire boundary for the effective-scope profile. */
@RestController
public final class SyntheticRequestHistoryController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final byte CONCEALED = 0;

    private final OrganizationId organization;
    private final SyntheticActorResolver actors;
    private final RestInputValidator inputs;
    private final RequestHistoryQuery history;
    private final JsonMapper json = new JsonMapper();

    public SyntheticRequestHistoryController(OrganizationId organization, SyntheticActorResolver actors,
                                             RestInputValidator inputs, RequestHistoryQuery history) {
        this.organization = organization;
        this.actors = actors;
        this.inputs = inputs;
        this.history = history;
    }

    @GetMapping(path = "/requests/{ref}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> history(HttpServletRequest request, @PathVariable("ref") String ref) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return RestProblems.response(request, Code.AUTHENTICATION_REQUIRED, null, headers);
        }
        var reference = new RequestRef(inputs.reference(request, ref));
        try {
            byte[] envelope = history.release(actor.orElseThrow(), reference, this::encode);
            if (envelope[0] == CONCEALED) {
                return RestProblems.response(request, Code.NOT_FOUND);
            }
            return ResponseEntity.ok().contentType(JSON_UTF8)
                    .header(HttpHeaders.CACHE_CONTROL, RestProblems.NO_STORE).body(envelope);
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        }
    }

    private byte[] encode(Optional<RequestHistoryQuery.View> result) {
        if (result.isEmpty()) {
            return new byte[] {CONCEALED};
        }
        var view = result.orElseThrow();
        var body = json.createObjectNode().put("ref", view.ref().value());
        var changes = body.putArray("changes");
        for (var change : view.changes()) {
            var entry = changes.addObject().put("sequence", change.sequence())
                    .put("action", change.action().name().toLowerCase(Locale.ROOT));
            if (change.from() == null) {
                entry.putNull("from");
            } else {
                entry.put("from", change.from().name());
            }
            entry.put("to", change.to().name());
        }
        return json.writeValueAsBytes(body);
    }
}
