package com.company.requests.adapter.http;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

public final class RestProblems {
    static final String NO_STORE = "no-store";

    private RestProblems() {
    }

    public enum Code {
        INVALID_INPUT(HttpStatus.BAD_REQUEST, "invalid_input", "Invalid input", Outcome.INVALID_INPUT),
        REQUEST_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "request_too_large", "Request too large",
                Outcome.REQUEST_TOO_LARGE),
        UNSUPPORTED_MEDIA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media", "Unsupported media",
                Outcome.UNSUPPORTED_MEDIA),
        AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication required",
                Outcome.AUTHENTICATION_REQUIRED),
        NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "Not found", Outcome.RESOURCE_UNAVAILABLE),
        ACTION_DENIED(HttpStatus.FORBIDDEN, "action_denied", "Action denied", Outcome.ACTION_DENIED),
        THROTTLED(HttpStatus.TOO_MANY_REQUESTS, "throttled", "Too many requests", Outcome.THROTTLED),
        AUTHORITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "authority_unavailable", "Authority unavailable",
                Outcome.AUTHORITY_UNAVAILABLE),
        OUTCOME_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "outcome_unknown", "Outcome unknown", Outcome.OUTCOME_UNKNOWN),
        ILLEGAL_STATE(HttpStatus.CONFLICT, "illegal_state", "Illegal state", Outcome.ILLEGAL_STATE),
        STALE_OBSERVATION(HttpStatus.CONFLICT, "stale_observation", "Stale observation", Outcome.STALE_OBSERVATION),
        KEY_CONFLICT(HttpStatus.CONFLICT, "key_conflict", "Key conflict", Outcome.KEY_CONFLICT),
        KEY_EXPIRED(HttpStatus.CONFLICT, "key_expired", "Key expired", Outcome.KEY_EXPIRED),
        RETRY_FENCED(HttpStatus.CONFLICT, "retry_fenced", "Retry fenced", Outcome.RETRY_FENCED),
        METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed",
                Outcome.METHOD_NOT_ALLOWED),
        NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "not_acceptable", "Not acceptable", Outcome.NOT_ACCEPTABLE),
        INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error",
                Outcome.INTERNAL_ERROR);

        private final HttpStatus status;
        private final URI type;
        private final String title;
        private final Outcome outcome;

        Code(HttpStatus status, String code, String title, Outcome outcome) {
            this.status = status;
            this.type = URI.create("urn:requests:problem:" + code);
            this.title = title;
            this.outcome = outcome;
        }

        Outcome outcome() {
            return outcome;
        }
    }

    /** Creates the shared error body, including for callers outside MVC exception handling. */
    public static ProblemDetail create(Code code) {
        return create(code, UUID.randomUUID().toString());
    }

    private static ProblemDetail create(Code code, String correlationId) {
        var problem = ProblemDetail.forStatus(code.status);
        problem.setType(code.type);
        problem.setTitle(code.title);
        problem.setInstance(URI.create("urn:uuid:" + correlationId));
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(Code code) {
        return response(null, code, null, HttpHeaders.EMPTY);
    }

    public static ResponseEntity<ProblemDetail> response(HttpServletRequest request, Code code) {
        return response(request, code, null, HttpHeaders.EMPTY);
    }

    static ResponseEntity<ProblemDetail> response(HttpServletRequest request, Code code, RestInputException.Field field,
                                                   HttpHeaders protocol) {
        return response(request, code, field, protocol, code.outcome);
    }

    static ResponseEntity<ProblemDetail> response(HttpServletRequest request, Code code, RestInputException.Field field,
                                                   HttpHeaders protocol, Outcome outcome) {
        return response(code, field, protocol, RequestEvidenceFilter.problem(request, outcome).toString());
    }

    private static ResponseEntity<ProblemDetail> response(Code code, RestInputException.Field field,
                                                           HttpHeaders protocol, String correlationId) {
        var problem = create(code, correlationId);
        if (field != null) {
            problem.setProperty("invalidFields", List.of(field.wireName()));
        }
        return ResponseEntity.status(code.status)
                .headers(protocol)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(problem);
    }
}
