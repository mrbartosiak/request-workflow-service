package com.company.requests.adapter.http;

import java.net.URI;
import java.util.List;
import java.util.UUID;

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
        INVALID_INPUT(HttpStatus.BAD_REQUEST, "invalid_input", "Invalid input"),
        REQUEST_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "request_too_large", "Request too large"),
        UNSUPPORTED_MEDIA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media", "Unsupported media"),
        AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "authentication_required", "Authentication required"),
        NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "Not found"),
        ACTION_DENIED(HttpStatus.FORBIDDEN, "action_denied", "Action denied"),
        THROTTLED(HttpStatus.TOO_MANY_REQUESTS, "throttled", "Too many requests"),
        AUTHORITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "authority_unavailable", "Authority unavailable"),
        OUTCOME_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "outcome_unknown", "Outcome unknown"),
        ILLEGAL_STATE(HttpStatus.CONFLICT, "illegal_state", "Illegal state"),
        STALE_OBSERVATION(HttpStatus.CONFLICT, "stale_observation", "Stale observation"),
        KEY_CONFLICT(HttpStatus.CONFLICT, "key_conflict", "Key conflict"),
        KEY_EXPIRED(HttpStatus.CONFLICT, "key_expired", "Key expired"),
        RETRY_FENCED(HttpStatus.CONFLICT, "retry_fenced", "Retry fenced"),
        METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed"),
        NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "not_acceptable", "Not acceptable"),
        INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error");

        private final HttpStatus status;
        private final URI type;
        private final String title;

        Code(HttpStatus status, String code, String title) {
            this.status = status;
            this.type = URI.create("urn:requests:problem:" + code);
            this.title = title;
        }
    }

    /** Creates the shared error body, including for callers outside MVC exception handling. */
    public static ProblemDetail create(Code code) {
        var correlationId = UUID.randomUUID().toString();
        var problem = ProblemDetail.forStatus(code.status);
        problem.setType(code.type);
        problem.setTitle(code.title);
        problem.setInstance(URI.create("urn:uuid:" + correlationId));
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(Code code) {
        return response(code, null, HttpHeaders.EMPTY);
    }

    static ResponseEntity<ProblemDetail> response(Code code, RestInputException.Field field, HttpHeaders protocol) {
        var problem = create(code);
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
