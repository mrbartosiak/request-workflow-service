package com.company.requests.adapter.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.company.requests.adapter.http.RestInputException.Field;
import com.company.requests.adapter.http.RestProblems.Code;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;

@RestControllerAdvice
final class RestProblemAdvice {
    @ExceptionHandler
    ResponseEntity<ProblemDetail> invalid(RestInputException ex, HttpServletRequest request,
                                          HttpServletResponse response) {
        var headers = HttpHeaders.EMPTY;
        if (ex.code() == Code.UNSUPPORTED_MEDIA) {
            headers = ex.unsupportedEncoding() ? encodingHeaders() : mediaHeaders(request);
        }
        return problem(request, response, ex.code(), ex.field(), headers, ex.outcome());
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
                                         HttpServletResponse response) {
        var headers = new HttpHeaders();
        var allowed = ex.getSupportedHttpMethods();
        if (allowed != null) {
            // Match the implicit methods advertised by Spring MVC's OPTIONS handler.
            var methods = new LinkedHashSet<>(allowed);
            if (methods.contains(HttpMethod.GET)) {
                methods.add(HttpMethod.HEAD);
            }
            methods.add(HttpMethod.OPTIONS);
            headers.setAllow(methods);
        }
        return problem(request, response, Code.METHOD_NOT_ALLOWED, headers);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media(HttpServletRequest request, HttpServletResponse response) {
        return problem(request, response, Code.UNSUPPORTED_MEDIA, mediaHeaders(request));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> accept(HttpServletRequest request, HttpServletResponse response) {
        return problem(request, response, Code.NOT_ACCEPTABLE, HttpHeaders.EMPTY);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ProblemDetail> absent(HttpServletRequest request, HttpServletResponse response) {
        return problem(request, response, Code.NOT_FOUND, null, HttpHeaders.EMPTY, Outcome.ROUTE_NOT_FOUND);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> size(HttpServletRequest request, HttpServletResponse response) {
        return problem(request, response, Code.REQUEST_TOO_LARGE, HttpHeaders.EMPTY);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
            TypeMismatchException.class})
    ResponseEntity<ProblemDetail> binding(HttpServletRequest request, HttpServletResponse response) {
        return problem(request, response, Code.INVALID_INPUT, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(HttpServletRequest request, HttpServletResponse response) {
        return problem(request, response, Code.INTERNAL_ERROR, HttpHeaders.EMPTY);
    }

    private static HttpHeaders mediaHeaders(HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (request.getMethod().equals("PATCH")) {
            headers.set(HttpHeaders.ACCEPT_PATCH, MediaType.APPLICATION_JSON_VALUE);
        }
        return headers;
    }

    private static HttpHeaders encodingHeaders() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, RestInputValidator.SUPPORTED_ENCODINGS);
        return headers;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpServletRequest request, HttpServletResponse response,
                                                         Code code, HttpHeaders headers) {
        return problem(request, response, code, null, headers, code.outcome());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpServletRequest request, HttpServletResponse response,
                                                         Code code, Field field, HttpHeaders headers, Outcome outcome) {
        if (response.isCommitted()) {
            RequestEvidenceFilter.problem(request, outcome);
            return null;
        }
        // Discard partially prepared route data and headers before writing a single safe response.
        response.reset();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return RestProblems.response(request, code, field, headers, outcome);
    }
}
