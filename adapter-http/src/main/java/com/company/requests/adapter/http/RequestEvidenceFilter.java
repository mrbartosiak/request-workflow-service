package com.company.requests.adapter.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.UUID;

import com.company.requests.application.evidence.RequestEvidenceEvent.Direction;
import com.company.requests.application.evidence.RequestEvidenceEvent.Destination;
import com.company.requests.application.evidence.RequestEvidenceEvent.Operation;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
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
        return request.getAttribute(STATE) instanceof State;
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
        var state = new State(recorder.start(Direction.INGRESS, operation(request), Destination.REQUESTS_API));
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
            state.attempt.finish(state.outcome != null ? state.outcome : statusOutcome(status), status);
        }
    }

    private static Operation operation(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI();
        }
        return switch (path) {
            case "/requests" -> Operation.CREATE_REQUEST;
            case "/operation-keys" -> Operation.ISSUE_OPERATION_KEY;
            default -> Operation.UNMATCHED_HTTP;
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
