package com.company.requests.adapter.http;

import com.company.requests.application.ActionContextService;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.adapter.http.RestProblems.Code;

/** Action-context wire boundary for the explicitly provisioned effective-scope profile. */
@RestController
public final class SyntheticActionContextController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private final OrganizationId organization;
    private final SyntheticActorResolver actors;
    private final RestInputValidator inputs;
    private final Optional<ActionContextService> contexts;
    private final JsonMapper json = new JsonMapper();

    public SyntheticActionContextController(OrganizationId organization, SyntheticActorResolver actors,
                                            RestInputValidator inputs, Optional<ActionContextService> contexts) {
        this.organization = organization;
        this.actors = actors;
        this.inputs = inputs;
        this.contexts = contexts;
    }

    @GetMapping(path = "/requests/{ref}/action-context", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> actionContext(HttpServletRequest request, @PathVariable("ref") String ref) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return RestProblems.response(request, Code.AUTHENTICATION_REQUIRED, null, headers);
        }
        var input = inputs.actionContext(request, ref);
        var action = Action.valueOf(input.action().toUpperCase(Locale.ROOT));
        try {
            byte[] envelope = contexts.orElseThrow(AuthorityUnavailableException::new)
                    .release(actor.orElseThrow(), new RequestRef(input.ref()), action, this::encode);
            return response(request, envelope);
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        }
    }

    private byte[] encode(ActionContextService.Reply reply) {
        return switch (reply) {
            case ActionContextService.View view -> {
                var body = json.writeValueAsBytes(new ContextBody(view.ref().value(), lower(view.action()),
                        view.state().name(), Map.of(lower(view.field()), view.value()), view.expectedRevision()));
                yield ByteBuffer.allocate(Integer.BYTES + body.length).putInt(200).put(body).array();
            }
            case ActionContextService.Rejection rejection -> {
                int status = switch (rejection) {
                    case CONCEALED -> 404;
                    case DENIED -> 403;
                    case ILLEGAL_STATE -> 409;
                    default -> throw new IllegalArgumentException("Invalid action context outcome.");
                };
                yield ByteBuffer.allocate(Integer.BYTES).putInt(status).array();
            }
        };
    }

    private static ResponseEntity<?> response(HttpServletRequest request, byte[] envelope) {
        var bytes = ByteBuffer.wrap(envelope);
        return switch (bytes.getInt()) {
            case 200 -> {
                byte[] body = new byte[bytes.remaining()];
                bytes.get(body);
                yield ResponseEntity.ok().contentType(JSON_UTF8).body(body);
            }
            case 403 -> RestProblems.response(request, Code.ACTION_DENIED);
            case 404 -> RestProblems.response(request, Code.NOT_FOUND);
            case 409 -> RestProblems.response(request, Code.ILLEGAL_STATE);
            default -> throw new IllegalStateException("Invalid action context response.");
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private record ContextBody(String ref, String action, String state, Map<String, String> context,
                               String expectedRevision) { }
}
