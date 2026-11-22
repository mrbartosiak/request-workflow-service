package com.company.requests.application.evidence;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** A request evidence envelope with no caller-controlled text fields. Status zero means none was observed. */
public record RequestEvidenceEvent(Instant at, UUID correlationId, Direction direction, Operation operation,
                                   Destination destination, Phase phase, Outcome outcome, long elapsedNanos,
                                   int status) {
    public enum Direction { INGRESS, EGRESS }

    public enum Operation { CREATE_REQUEST, ISSUE_OPERATION_KEY, UNMATCHED_HTTP, VENDOR_SEND }

    public enum Destination { REQUESTS_API, FAKE_ALPHA, FAKE_BETA }

    public enum Phase { START, ERROR, OUTCOME }

    public enum Stage { BOUNDARY, AUTHENTICATION, VALIDATION, ROUTING, APPLICATION, TRANSPORT, DELIVERY }

    public enum Outcome {
        STARTED(Stage.BOUNDARY, "Request started.", false),
        COMPLETED(Stage.BOUNDARY, "Request completed.", false),
        AUTHENTICATION_REQUIRED(Stage.AUTHENTICATION, "Authentication was required."),
        INVALID_INPUT(Stage.VALIDATION, "Request input failed validation."),
        REQUEST_TOO_LARGE(Stage.VALIDATION, "Request exceeded the size limit."),
        UNSUPPORTED_MEDIA(Stage.VALIDATION, "Request media or encoding was unsupported."),
        ROUTE_NOT_FOUND(Stage.ROUTING, "No request route matched."),
        METHOD_NOT_ALLOWED(Stage.ROUTING, "The route did not allow this method."),
        NOT_ACCEPTABLE(Stage.ROUTING, "The requested response format was unavailable."),
        RESOURCE_UNAVAILABLE(Stage.APPLICATION, "The requested resource was unavailable."),
        ACTION_DENIED(Stage.APPLICATION, "The action was denied."),
        THROTTLED(Stage.APPLICATION, "The request was throttled."),
        AUTHORITY_UNAVAILABLE(Stage.APPLICATION, "Authorization evidence was unavailable."),
        OUTCOME_UNKNOWN(Stage.APPLICATION, "A durable request outcome could not be established."),
        ILLEGAL_STATE(Stage.APPLICATION, "The current state did not allow the action."),
        STALE_OBSERVATION(Stage.APPLICATION, "The supplied observation was stale."),
        KEY_CONFLICT(Stage.APPLICATION, "The operation key belonged to a different intent."),
        KEY_EXPIRED(Stage.APPLICATION, "The operation key had expired."),
        RETRY_FENCED(Stage.APPLICATION, "Retry was blocked by the recovery fence."),
        INTERNAL_ERROR(Stage.APPLICATION, "The request failed during application processing."),
        REJECTED(Stage.BOUNDARY, "The request was rejected."),
        TRANSPORT_REJECTED(Stage.TRANSPORT, "HTTP transport rejected the request before dispatch."),
        TRANSPORT_FAILURE(Stage.TRANSPORT, "The HTTP transport failed during request processing."),
        TRANSPORT_TIMEOUT(Stage.TRANSPORT, "The HTTP transport timed out."),
        DELIVERY_CONFIRMED(Stage.DELIVERY, "Outbound delivery was confirmed.", false),
        DELIVERY_REJECTED(Stage.DELIVERY, "The destination rejected the outbound attempt."),
        DELIVERY_UNKNOWN(Stage.DELIVERY, "The outbound outcome could not be established."),
        DELIVERY_TIMEOUT(Stage.DELIVERY, "The outbound attempt timed out."),
        DELIVERY_FAILURE(Stage.DELIVERY, "The outbound attempt failed.");

        private final Stage stage;
        private final String description;
        private final boolean error;

        Outcome(Stage stage, String description) {
            this(stage, description, true);
        }

        Outcome(Stage stage, String description, boolean error) {
            this.stage = stage;
            this.description = description;
            this.error = error;
        }

        public Stage stage() {
            return stage;
        }

        public String description() {
            return description;
        }

        boolean error() {
            return error;
        }
    }

    /**
     * The structured line uses only generated UUIDs, UTC instants, finite catalogues and numbers.
     * The fixed catalogue descriptions contain no JSON escape characters.
     */
    public String json() {
        return String.format(Locale.ROOT, """
                {"at":"%s","correlation_id":"%s","direction":"%s","operation":"%s","destination":"%s","phase":"%s","outcome":"%s","stage":"%s","description":"%s","elapsed_ns":%d,"status":%d}""",
                at, correlationId, direction, operation, destination, phase, outcome, outcome.stage(),
                outcome.description(), elapsedNanos, status);
    }
}
