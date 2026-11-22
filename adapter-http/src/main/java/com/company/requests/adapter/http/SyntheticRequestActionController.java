package com.company.requests.adapter.http;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.RequestActionCommand;
import com.company.requests.domain.Action;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.adapter.http.RestProblems.Code;

/** Existing-request command routes for the explicitly provisioned effective-scope profile. */
@RestController
public final class SyntheticRequestActionController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

    private final OrganizationId organization;
    private final SyntheticActorResolver actors;
    private final RestInputValidator inputs;
    private final Optional<RequestActionCommand> commands;
    private final JsonMapper json = new JsonMapper();

    public SyntheticRequestActionController(OrganizationId organization, SyntheticActorResolver actors,
                                             RestInputValidator inputs, Optional<RequestActionCommand> commands) {
        this.organization = organization;
        this.actors = actors;
        this.inputs = inputs;
        this.commands = commands;
    }

    @PatchMapping(path = "/requests/{ref}/body", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> edit(HttpServletRequest request, @PathVariable("ref") String ref) {
        return authenticated(request, actor -> {
            var input = inputs.edit(request, ref);
            return command().edit(actor, input.key(),
                    new IntentDigest.Edit(new RequestRef(input.ref()), input.body(), input.expectedRevision()),
                    this::encode);
        });
    }

    @PostMapping(path = "/requests/{ref}/actions/{action:verify|accept}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> transition(HttpServletRequest request, @PathVariable("ref") String ref,
                                        @PathVariable("action") String action) {
        return authenticated(request, actor -> {
            var input = inputs.action(request, ref, action);
            var intent = new IntentDigest.Transition(Action.valueOf(action.toUpperCase(Locale.ROOT)),
                    new RequestRef(input.ref()), input.reason(), input.expectedRevision());
            return command().transition(actor, input.key(), intent, this::encode);
        });
    }

    private RequestActionCommand command() {
        return commands.orElseThrow(AuthorityUnavailableException::new);
    }

    private ResponseEntity<?> authenticated(HttpServletRequest request,
                                            Function<AuthenticatedActor, RequestActionCommand.Result> run) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return RestProblems.response(request, Code.AUTHENTICATION_REQUIRED, null, headers);
        }
        try {
            return switch (run.apply(actor.orElseThrow())) {
                case RequestActionCommand.Released released ->
                        ResponseEntity.ok().contentType(JSON_UTF8).body(released.envelope());
                case RequestActionCommand.Rejection rejected -> problem(request, rejected);
            };
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        } catch (OperationKeyException key) {
            if (key.reason() == OperationKeyException.Reason.EXPIRED) {
                return RestProblems.response(request, Code.KEY_EXPIRED);
            }
            return RestProblems.response(request, Code.INVALID_INPUT, RestInputException.Field.KEY, HttpHeaders.EMPTY);
        }
    }

    private static ResponseEntity<?> problem(HttpServletRequest request, RequestActionCommand.Rejection rejection) {
        if (rejection == RequestActionCommand.Rejection.MALFORMED) {
            return RestProblems.response(request, Code.INVALID_INPUT, RestInputException.Field.EXPECTED_REVISION,
                    HttpHeaders.EMPTY);
        }
        return RestProblems.response(request, switch (rejection) {
            case CONCEALED -> Code.NOT_FOUND;
            case DENIED -> Code.ACTION_DENIED;
            case STALE -> Code.STALE_OBSERVATION;
            case ILLEGAL_STATE -> Code.ILLEGAL_STATE;
            case CONFLICT -> Code.KEY_CONFLICT;
            case EXPIRED -> Code.KEY_EXPIRED;
            case FENCED -> Code.RETRY_FENCED;
            case OUTCOME_UNKNOWN -> Code.OUTCOME_UNKNOWN;
            case MALFORMED -> throw new IllegalStateException("Malformed observations require a field.");
        });
    }

    private byte[] encode(OperationReceipt receipt) {
        return json.writeValueAsBytes(new MutationResult(receipt.resultRef().value(),
                receipt.action().name().toLowerCase(Locale.ROOT), Long.toString(receipt.resultRevision()),
                receipt.resultState().name()));
    }

    private record MutationResult(String ref, String action, String resultRevision, String state) { }
}
