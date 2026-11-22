package com.company.requests.adapter.http;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.RequestDetailQuery;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.adapter.http.RestProblems.Code;

/** Current detail wire boundary for the explicitly provisioned effective-scope profile. */
@RestController
public final class SyntheticRequestDetailController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final byte CONCEALED = 0;
    private static final byte FOUND = 1;

    private final OrganizationId organization;
    private final SyntheticActorResolver actors;
    private final RestInputValidator inputs;
    private final RequestDetailQuery details;
    private final JsonMapper json = new JsonMapper();

    public SyntheticRequestDetailController(OrganizationId organization, SyntheticActorResolver actors,
                                            RestInputValidator inputs, RequestDetailQuery details) {
        this.organization = organization;
        this.actors = actors;
        this.inputs = inputs;
        this.details = details;
    }

    @GetMapping(path = "/requests/{ref}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> detail(HttpServletRequest request, @PathVariable("ref") String ref) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return RestProblems.response(request, Code.AUTHENTICATION_REQUIRED, null, headers);
        }
        var reference = new RequestRef(inputs.reference(request, ref));
        try {
            byte[] envelope = details.release(actor.orElseThrow(), reference, this::encode);
            return switch (envelope[0]) {
                case CONCEALED -> RestProblems.response(request, Code.NOT_FOUND);
                case FOUND -> ResponseEntity.ok().contentType(JSON_UTF8)
                        .header(HttpHeaders.CACHE_CONTROL, RestProblems.NO_STORE)
                        .body(Arrays.copyOfRange(envelope, 1, envelope.length));
                default -> throw new IllegalStateException("Invalid request detail response.");
            };
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        }
    }

    private byte[] encode(Optional<RequestDetailQuery.View> result) {
        if (result.isEmpty()) {
            return new byte[] {CONCEALED};
        }
        var view = result.orElseThrow();
        var fields = new LinkedHashMap<String, String>();
        fields.put("ref", view.ref().value());
        view.type().ifPresent(type -> fields.put("type", type.name()));
        view.name().ifPresent(name -> fields.put("name", name));
        view.body().ifPresent(body -> fields.put("body", body));
        view.state().ifPresent(state -> fields.put("state", state.name()));
        view.revision().ifPresent(revision -> fields.put("revision", revision));
        view.publicationId().ifPresent(id -> fields.put("publicationId", Long.toString(id.value())));
        byte[] body = json.writeValueAsBytes(fields);
        var envelope = new byte[body.length + 1];
        envelope[0] = FOUND;
        System.arraycopy(body, 0, envelope, 1, body.length);
        return envelope;
    }
}
