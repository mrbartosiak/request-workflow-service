package com.company.requests.bootstrap;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.company.requests.adapter.http.RequestEvidenceFilter;
import com.company.requests.adapter.http.RestInputValidator;
import com.company.requests.adapter.http.SyntheticCreateController;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.application.evidence.RequestEvidenceEvent;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.application.evidence.RestrictedEvidenceLookup;
import com.company.requests.integration.OutboundEvidenceRecorder;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static com.company.requests.application.evidence.RequestEvidenceEvent.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class RequestEvidenceTest {
    private static final String BEARER = credential("evidence-control-bearer");
    private static final String UNRECOGNIZED = credential("evidence-unrecognized-bearer");
    private static final String BODY = "evidence-sensitive-body";
    private static final String NAME = "evidence-sensitive-name";
    private static final String URI = "evidence-sensitive-uri";
    private static final String KEY = "evidence-sensitive-invalid-key";
    private static final String EXCEPTION = "evidence-sensitive-exception-diagnostic";
    private static final String SECRET = "evidence-sensitive-signing-secret";
    private static final String THIRD_PARTY = "evidence-sensitive-third-party-payload";
    private static final String CLIENT_CORRELATION = "evidence-untrusted-client-correlation";
    private static final String CREATE = """
            {"type":"HR_TICKET","name":"%s","body":"%s"}
            """.formatted(NAME, BODY);
    private static final String FAULT_HEADER = "X-Evidence-Test-Transport";
    private static final Instant NOW = Instant.parse("2026-11-22T14:30:12.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void reconcilesMvcAndOutboundAttemptEvidenceAndExcludesSensitiveValues() throws Exception {
        var records = new ArrayList<RequestEvidenceEvent>();
        var recorder = new RequestEvidenceRecorder(CLOCK, records::add);
        var calls = new AtomicInteger();
        var controller = createController(calls);

        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(RequestsConfiguration.class);
            context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("createEvidenceFixture", controller));
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestEvidenceFilter(recorder),
                    (request, response, chain) -> chain.doFilter(withSyntheticReadTimeout(request), response)).build();

            var issue = mvc.perform(post("/operation-keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER)).andReturn();
            var issued = issue.getResponse();
            assertEquals(201, issued.getStatus());
            assertEquals("no-store", issued.getHeader(HttpHeaders.CACHE_CONTROL));
            assertTrue(issued.getContentType().startsWith("application/json"));
            assertEquals(1, calls.get());
            String signedKey = JSON.readTree(issued.getContentAsByteArray()).get("key").asString();
            var issuance = records.getFirst().correlationId();
            assertEvidence(records, issuance, Operation.ISSUE_OPERATION_KEY, Destination.REQUESTS_API,
                    Direction.INGRESS, Outcome.COMPLETED, 201, false);

            var unauthenticated = mvc.perform(post("/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + UNRECOGNIZED)
                    .header("Idempotency-Key", KEY).content(CREATE)).andReturn();
            assertEquals("Bearer", unauthenticated.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE));
            checkProblem(unauthenticated, records, 401, "authentication_required", Operation.CREATE_REQUEST,
                    Outcome.AUTHENTICATION_REQUIRED);

            var invalid = mvc.perform(post("/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER)
                    .header("Idempotency-Key", KEY).header("X-Request-Id", CLIENT_CORRELATION)
                    .contentType("application/json").content("{\"body\":\"" + BODY + "\"}")).andReturn();
            checkProblem(invalid, records, 400, "invalid_input", Operation.CREATE_REQUEST, Outcome.INVALID_INPUT);
            assertEquals(1, calls.get(), "Early authentication and validation rejects reached the command transaction.");

            var unknown = mvc.perform(post("/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER)
                    .header("Idempotency-Key", signedKey).contentType("application/json").content(CREATE)).andReturn();
            checkProblem(unknown, records, 503, "outcome_unknown", Operation.CREATE_REQUEST, Outcome.OUTCOME_UNKNOWN);

            var readTimeout = mvc.perform(post("/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER)
                    .header("Idempotency-Key", signedKey).header(FAULT_HEADER, "1")
                    .contentType("application/json").content(CREATE)).andReturn();
            checkProblem(readTimeout, records, 400, "invalid_input", Operation.CREATE_REQUEST, Outcome.TRANSPORT_TIMEOUT);
            assertEquals(2, calls.get(), "A transport read failure reached the command transaction.");

            var failed = mvc.perform(post("/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER)
                    .header("Idempotency-Key", signedKey).contentType("application/json").content(CREATE)).andReturn();
            checkProblem(failed, records, 500, "internal_error", Operation.CREATE_REQUEST, Outcome.INTERNAL_ERROR);

            var lookup = mvc.perform(get("/audit/evidence/" + URI + "?query=" + URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER)).andReturn();
            checkProblem(lookup, records, 404, "not_found", Operation.UNMATCHED_HTTP, Outcome.ROUTE_NOT_FOUND);
            assertEquals(3, calls.get());

            var outbound = new OutboundEvidenceRecorder(recorder);
            var sent = outbound.start(OutboundEvidenceRecorder.Destination.FAKE_ALPHA);
            sent.finish(OutboundEvidenceRecorder.Result.CONFIRMED);
            assertEvidence(records, sent.correlationId(), Operation.VENDOR_SEND, Destination.FAKE_ALPHA,
                    Direction.EGRESS, Outcome.DELIVERY_CONFIRMED, 0, false);

            var timedOut = outbound.start(OutboundEvidenceRecorder.Destination.FAKE_BETA);
            try {
                syntheticSendTimeout(THIRD_PARTY);
                fail("Fixture should time out.");
            } catch (SocketTimeoutException expected) {
                timedOut.finish(OutboundEvidenceRecorder.Result.TIMEOUT);
            }
            assertEvidence(records, timedOut.correlationId(), Operation.VENDOR_SEND, Destination.FAKE_BETA,
                    Direction.EGRESS, Outcome.DELIVERY_TIMEOUT, 0, true);

            assertEquals(25, records.size());
            assertEquals(9, records.stream().map(RequestEvidenceEvent::correlationId).distinct().count());
            assertEquals(9, count(records, Phase.START));
            assertEquals(9, count(records, Phase.OUTCOME));
            assertEquals(7, count(records, Phase.ERROR));
            var secrets = List.of(BEARER, UNRECOGNIZED, BODY, NAME, URI, KEY, CLIENT_CORRELATION,
                    EXCEPTION, SECRET, THIRD_PARTY, signedKey, B08SyntheticScope.ORGANIZATION.value());
            assertTrue(records.stream().map(RequestEvidenceEvent::json)
                    .noneMatch(line -> secrets.stream().anyMatch(line::contains)), "Sensitive value escaped into evidence.");
        }
    }

    @Test
    void lookupRequiresTheIssuedCapabilityAndAuditsDenialsWithoutReading() {
        var records = new ArrayList<RequestEvidenceEvent>();
        var recorder = new RequestEvidenceRecorder(CLOCK, records::add);
        var incident = recorder.start(Direction.INGRESS, Operation.CREATE_REQUEST, Destination.REQUESTS_API);
        incident.finish(Outcome.OUTCOME_UNKNOWN, 503);

        var access = new ArrayList<RestrictedEvidenceLookup.Access>();
        var reads = new ArrayList<UUID>();
        var operator = new RestrictedEvidenceLookup.Capability();
        var lookup = new RestrictedEvidenceLookup(CLOCK, operator, id -> {
            reads.add(id);
            return records.stream().filter(record -> record.correlationId().equals(id)).toList();
        }, access::add);

        assertThrows(SecurityException.class, () -> lookup.find(null, incident.correlationId()));
        assertThrows(SecurityException.class, () -> lookup.find(new RestrictedEvidenceLookup.Capability(),
                incident.correlationId()));
        assertTrue(reads.isEmpty());
        assertEquals(records, lookup.find(operator, incident.correlationId()));
        assertEquals(List.of(incident.correlationId()), reads);
        assertEquals(List.of(new RestrictedEvidenceLookup.Access(NOW, false),
                new RestrictedEvidenceLookup.Access(NOW, false), new RestrictedEvidenceLookup.Access(NOW, true)), access);
        assertEquals(3, records.size(), "Lookup access belongs to a separate audit stream.");
    }

    private static SyntheticCreateController createController(AtomicInteger calls) {
        var writer = new WriterEpoch(UUID.randomUUID(), 1);
        var access = new CurrentAccess(admission -> { }, writer, List.of(B08SyntheticScope.POLICY));
        byte[] signing = Arrays.copyOf(SECRET.getBytes(UTF_8), 32);
        var secrets = new OperationSecrets(1, Map.of(1, signing), 1, Map.of(1, new byte[32]));
        var keys = new OperationKeyIssuer(access, CLOCK::instant, secrets, OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        var transactions = new TransactionRunner() {
            @Override
            public <T> T run(Supplier<T> work) {
                return switch (calls.incrementAndGet()) {
                    case 1 -> work.get();
                    case 2 -> {
                        work.get();
                        throw new CommitOutcomeUnknownException(new IOException(EXCEPTION));
                    }
                    default -> throw new IllegalStateException(EXCEPTION);
                };
            }
        };
        var create = new CreateCommand(transactions, null, null, null, null, access, admission -> { },
                null, keys, null, null, null);
        return new SyntheticCreateController(B08SyntheticScope.ORGANIZATION,
                B08SyntheticScope.actors(BEARER, credential("fixture-alias"),
                        credential("fixture-key-only"), credential("fixture-no-issue")),
                new RestInputValidator(), create);
    }

    private static String credential(String label) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(label.getBytes(UTF_8), 32));
    }

    private static ServletRequest withSyntheticReadTimeout(ServletRequest request) {
        if (!(request instanceof HttpServletRequest http) || http.getHeader(FAULT_HEADER) == null) {
            return request;
        }
        return new HttpServletRequestWrapper(http) {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
                throw new SocketTimeoutException(EXCEPTION);
            }
        };
    }

    private static void syntheticSendTimeout(String thirdPartyBody) throws SocketTimeoutException {
        throw new SocketTimeoutException(thirdPartyBody);
    }

    private static void checkProblem(MvcResult result, List<RequestEvidenceEvent> records, int status, String code,
                                     Operation operation, Outcome outcome) {
        var response = result.getResponse();
        assertEquals(status, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertNull(response.getHeader(HttpHeaders.LOCATION));
        var problem = JSON.readTree(response.getContentAsByteArray());
        var correlation = UUID.fromString(problem.get("correlationId").asString());
        assertEquals("urn:uuid:" + correlation, problem.get("instance").asString());
        assertEquals("urn:requests:problem:" + code, problem.get("type").asString());
        assertEvidence(records, correlation, operation, Destination.REQUESTS_API, Direction.INGRESS, outcome, status, true);
    }

    private static void assertEvidence(List<RequestEvidenceEvent> records, UUID id, Operation operation,
                                       Destination destination, Direction direction, Outcome outcome, int status,
                                       boolean error) {
        var events = records.stream().filter(event -> event.correlationId().equals(id)).toList();
        assertEquals(error ? List.of(Phase.START, Phase.ERROR, Phase.OUTCOME) : List.of(Phase.START, Phase.OUTCOME),
                events.stream().map(RequestEvidenceEvent::phase).toList());
        assertTrue(events.stream().allMatch(event -> event.at().equals(NOW) && event.operation() == operation
                && event.destination() == destination && event.direction() == direction && event.elapsedNanos() >= 0));
        assertEquals(Outcome.STARTED, events.getFirst().outcome());
        assertEquals(0, events.getFirst().elapsedNanos());
        assertTrue(events.subList(1, events.size()).stream().allMatch(event -> event.outcome() == outcome
                && event.status() == status && event.elapsedNanos() == events.getLast().elapsedNanos()));
        assertFalse(outcome.description().isBlank());
    }

    private static long count(List<RequestEvidenceEvent> events, Phase phase) {
        return events.stream().filter(event -> event.phase() == phase).count();
    }
}
