package com.company.requests.adapter.http;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.BrowseCursorCodec.InvalidCursor;
import com.company.requests.application.RequestBrowseQuery;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestState;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.adapter.http.RestProblems.Code;

/** Browse wire boundary for the effective-scope profile. */
@RestController
public final class SyntheticRequestBrowseController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private final OrganizationId organization;
    private final SyntheticActorResolver actors;
    private final RestInputValidator inputs;
    private final Optional<RequestBrowseQuery> browse;
    private final JsonMapper json = new JsonMapper();

    public SyntheticRequestBrowseController(OrganizationId organization, SyntheticActorResolver actors,
                                            RestInputValidator inputs, Optional<RequestBrowseQuery> browse) {
        this.organization = organization;
        this.actors = actors;
        this.inputs = inputs;
        this.browse = browse;
    }

    @GetMapping(path = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> browse(HttpServletRequest request) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return RestProblems.response(request, Code.AUTHENTICATION_REQUIRED, null, headers);
        }
        var input = inputs.browse(request);
        var selection = new RequestBrowseQuery.Selection(input.name(), input.state().map(RequestState::valueOf),
                input.size());
        try {
            byte[] page = browse.orElseThrow(AuthorityUnavailableException::new)
                    .release(actor.orElseThrow(), selection, input.cursor(), this::encode);
            return ResponseEntity.ok().contentType(JSON_UTF8).header(HttpHeaders.CACHE_CONTROL, RestProblems.NO_STORE)
                    .body(page);
        } catch (InvalidCursor invalid) {
            throw new RestInputException(Code.INVALID_INPUT, RestInputException.Field.CURSOR, false);
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        }
    }

    private byte[] encode(RequestBrowseQuery.View view) {
        var body = json.createObjectNode();
        var items = body.putArray("items");
        for (var entry : view.items()) {
            var item = items.addObject().put("ref", entry.ref().value());
            entry.type().ifPresent(type -> item.put("type", type.name()));
            entry.name().ifPresent(name -> item.put("name", name));
            entry.state().ifPresent(state -> item.put("state", state.name()));
            entry.publicationId().ifPresent(id -> item.put("publicationId", Long.toString(id.value())));
        }
        view.nextCursor().ifPresent(cursor -> body.put("nextCursor", cursor));
        return json.writeValueAsBytes(body);
    }
}
