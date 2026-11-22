package com.company.requests.adapter.http;

import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestType;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.adapter.http.RestProblems.Code;

/** Wire boundary registered only by the explicitly configured synthetic bootstrap. */
@RestController
public final class SyntheticCreateController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
    private final OrganizationId organization;
    private final SyntheticActorResolver actors;
    private final RestInputValidator inputs;
    private final CreateCommand creates;
    private final JsonMapper json = new JsonMapper();

    public SyntheticCreateController(OrganizationId organization, SyntheticActorResolver actors,
                                     RestInputValidator inputs, CreateCommand creates) {
        this.organization = organization;
        this.actors = actors;
        this.inputs = inputs;
        this.creates = creates;
    }

    @PostMapping(path = "/operation-keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> issue(HttpServletRequest request) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            return unauthenticated(request);
        }
        inputs.keyIssuance(request);
        try {
            var issued = creates.issue(actor.orElseThrow());
            return ResponseEntity.status(201).contentType(JSON_UTF8)
                    .body(json.writeValueAsBytes(new Issued(issued.key(), issued.expiresAt().toString())));
        } catch (AccessDeniedException denied) {
            return RestProblems.response(request, Code.ACTION_DENIED);
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        }
    }

    @PostMapping(path = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(HttpServletRequest request) {
        var actor = actors.resolveBearer(request, organization);
        if (actor.isEmpty()) {
            return unauthenticated(request);
        }
        return create(request, actor.orElseThrow(), inputs.create(request));
    }

    private ResponseEntity<?> create(HttpServletRequest request, AuthenticatedActor actor, RestInputs.Create input) {
        var intent = new IntentDigest.Create(RequestType.valueOf(input.type()), input.name(), input.body());
        try {
            return switch (creates.execute(actor, input.key(), intent, this::encode)) {
                case CreateCommand.Released released -> response(released.envelope());
                case CreateCommand.Rejection rejection -> RestProblems.response(request, switch (rejection) {
                    case CONCEALED -> Code.NOT_FOUND;
                    case CONFLICT -> Code.KEY_CONFLICT;
                    case EXPIRED -> Code.KEY_EXPIRED;
                    case FENCED -> Code.RETRY_FENCED;
                    case OUTCOME_UNKNOWN -> Code.OUTCOME_UNKNOWN;
                });
            };
        } catch (AccessDeniedException denied) {
            return RestProblems.response(request, Code.NOT_FOUND);
        } catch (AuthorityUnavailableException unavailable) {
            return RestProblems.response(request, Code.AUTHORITY_UNAVAILABLE);
        } catch (OperationKeyException key) {
            return key.reason() == OperationKeyException.Reason.EXPIRED ? RestProblems.response(request, Code.KEY_EXPIRED)
                    : RestProblems.response(request, Code.INVALID_INPUT, RestInputException.Field.KEY, HttpHeaders.EMPTY);
        }
    }

    private byte[] encode(OperationReceipt receipt) {
        String ref = receipt.resultRef().value();
        byte[] location = ("/requests/" + UriUtils.encodePathSegment(ref, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII);
        byte[] body = json.writeValueAsBytes(new MutationResult(ref, receipt.action().name().toLowerCase(Locale.ROOT),
                Long.toString(receipt.resultRevision()), receipt.resultState().name()));
        return ByteBuffer.allocate(Integer.BYTES * 2 + location.length + body.length)
                .putInt(receipt.originalStatus()).putInt(location.length).put(location).put(body).array();
    }

    private static ResponseEntity<byte[]> response(byte[] envelope) {
        var bytes = ByteBuffer.wrap(envelope);
        int status = bytes.getInt();
        byte[] location = new byte[bytes.getInt()];
        bytes.get(location);
        byte[] body = new byte[bytes.remaining()];
        bytes.get(body);
        return ResponseEntity.status(status).location(URI.create(new String(location, StandardCharsets.US_ASCII)))
                .contentType(JSON_UTF8).body(body);
    }

    private static ResponseEntity<?> unauthenticated(HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return RestProblems.response(request, Code.AUTHENTICATION_REQUIRED, null, headers);
    }

    private record Issued(String key, String expiresAt) { }

    private record MutationResult(String ref, String action, String resultRevision, String state) { }
}
