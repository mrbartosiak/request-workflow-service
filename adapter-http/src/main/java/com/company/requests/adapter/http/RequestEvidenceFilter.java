package com.company.requests.adapter.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.company.requests.application.evidence.RequestEvidenceEvent.Direction;
import com.company.requests.application.evidence.RequestEvidenceEvent.Destination;
import com.company.requests.application.evidence.RequestEvidenceEvent.Operation;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.application.evidence.RequestEvidenceStorage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** The synchronous HTTP boundary starts evidence before MVC can authenticate, validate or dispatch. */
public final class RequestEvidenceFilter extends OncePerRequestFilter {
    private static final String STATE = RequestEvidenceFilter.class.getName() + ".state";

    private final RequestEvidenceRecorder recorder;

    public RequestEvidenceFilter(RequestEvidenceRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder);
    }

    public static boolean handled(HttpServletRequest request) {
        return request.getAttribute(STATE) != null;
    }

    static UUID problem(HttpServletRequest request, Outcome outcome) {
        if (request != null && request.getAttribute(STATE) instanceof State state) {
            state.outcome = outcome;
            return state.attempt.correlationId();
        }
        return UUID.randomUUID();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Operation operation = operation(request);
        var admitted = admit(operation);
        if (admitted.isEmpty()) {
            request.setAttribute(STATE, Boolean.TRUE);
            RestProblems.write(response, RestProblems.Code.EVIDENCE_UNAVAILABLE, recorder.rejectIngress(operation),
                    request.getMethod().equals("HEAD"));
            return;
        }
        var state = new State(admitted.orElseThrow());
        request.setAttribute(STATE, state);
        boolean escaped = true;
        try {
            chain.doFilter(request, response);
            escaped = false;
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            state.outcome = failureOutcome(failure);
            throw failure;
        } finally {
            int status = response.getStatus();
            if (escaped && status < 400 && !response.isCommitted()) {
                status = 0;
            }
            try {
                state.attempt.finish(state.outcome != null ? state.outcome : statusOutcome(status), status);
            } catch (RequestEvidenceStorage.Unavailable unavailable) {
                if (!escaped && !response.isCommitted()) {
                    response.reset();
                    RestProblems.write(response, terminalGap(operation), state.attempt.correlationId(),
                            request.getMethod().equals("HEAD"));
                }
            }
        }
    }

    private Optional<RequestEvidenceRecorder.Attempt> admit(Operation operation) {
        try {
            return recorder.tryStart(Direction.INGRESS, operation, Destination.REQUESTS_API);
        } catch (RequestEvidenceStorage.Unavailable unavailable) {
            return Optional.empty();
        }
    }

    private static RestProblems.Code terminalGap(Operation operation) {
        return switch (operation) {
            case CREATE_REQUEST, APPLY_REQUEST_ACTION, EDIT_REQUEST_BODY -> RestProblems.Code.OUTCOME_UNKNOWN;
            default -> RestProblems.Code.EVIDENCE_UNAVAILABLE;
        };
    }

    private static Operation operation(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI();
        }
        return switch (request.getMethod()) {
            case "GET", "HEAD" -> readOperation(path);
            case "POST" -> postOperation(path);
            case "PATCH" -> path.matches("/requests/[^/]+/body") ? Operation.EDIT_REQUEST_BODY
                    : Operation.UNMATCHED_HTTP;
            default -> Operation.UNMATCHED_HTTP;
        };
    }

    private static Operation readOperation(String path) {
        if (path.equals("/requests")) {
            return Operation.BROWSE_REQUESTS;
        }
        if (path.matches("/requests/[^/]+/history")) {
            return Operation.READ_HISTORY;
        }
        if (path.matches("/requests/[^/]+/action-context")) {
            return Operation.READ_ACTION_CONTEXT;
        }
        return path.matches("/requests/[^/]+") ? Operation.READ_REQUEST : Operation.UNMATCHED_HTTP;
    }

    private static Operation postOperation(String path) {
        return switch (path) {
            case "/requests" -> Operation.CREATE_REQUEST;
            case "/operation-keys" -> Operation.ISSUE_OPERATION_KEY;
            default -> path.matches("/requests/[^/]+/actions/(verify|accept|delete|reject|publish)")
                    ? Operation.APPLY_REQUEST_ACTION : Operation.UNMATCHED_HTTP;
        };
    }

    private static Outcome failureOutcome(Throwable failure) {
        if (failure instanceof InterruptedIOException || failure.getCause() instanceof InterruptedIOException) {
            return Outcome.TRANSPORT_TIMEOUT;
        }
        if (failure instanceof IOException || failure.getCause() instanceof IOException) {
            return Outcome.TRANSPORT_FAILURE;
        }
        return Outcome.INTERNAL_ERROR;
    }

    private static Outcome statusOutcome(int status) {
        if (status >= 500) {
            return Outcome.INTERNAL_ERROR;
        }
        return status >= 400 ? Outcome.REJECTED : Outcome.COMPLETED;
    }

    private static final class State {
        private final RequestEvidenceRecorder.Attempt attempt;
        private Outcome outcome;

        private State(RequestEvidenceRecorder.Attempt attempt) {
            this.attempt = attempt;
        }
    }
}
