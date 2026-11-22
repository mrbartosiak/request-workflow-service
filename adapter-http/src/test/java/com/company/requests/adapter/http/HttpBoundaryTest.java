package com.company.requests.adapter.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class HttpBoundaryTest {
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String KEY = "first-key";
    private static final String REF = "ABc123_45-aBc123ABc123_45-aBc123";
    private static final String ID = "9223372036854775807";
    private static final String OBSERVATION = "synthetic.observation-fixture";
    private static final String VALID_CREATE = """
            {"type":"HR_TICKET","name":"name","body":"body"}
            """;
    private static final String VALID_TRANSITION = """
            {"expectedRevision":"synthetic.observation-fixture"}
            """;
    private static final String VALID_EDIT = """
            {"body":"edited","expectedRevision":"synthetic.observation-fixture"}
            """;
    private static final Set<String> SAFE_FIELDS = Set.of("type", "name", "body", "reason", "expectedRevision",
            "Idempotency-Key", "ref", "action", "state", "size", "cursor");
    private static final JsonMapper JSON = new JsonMapper();
    private static final AtomicInteger MUTATIONS = new AtomicInteger();
    private static final AtomicReference<Object> LAST_MUTATION = new AtomicReference<>();
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    @BeforeAll
    static void startMvc() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(RequestsMvcConfiguration.class, TestRoutes.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterAll
    static void stopMvc() {
        context.close();
    }

    @BeforeEach
    void resetMutations() {
        MUTATIONS.set(0);
        LAST_MUTATION.set(null);
    }

    @Test
    void malformedCreateNeverReachesTheMutationEntry() throws Exception {
        assertRejected(create("{\"body\":\"never-echo\""), 400, "invalid_input");
    }

    @Test
    void wrongMediaNeverReachesTheMutationEntry() throws Exception {
        var result = assertRejected(create("{}").contentType("text/plain"), 415, "unsupported_media");
        assertEquals("application/json", result.getHeader(HttpHeaders.ACCEPT));
        assertNull(result.getHeader(HttpHeaders.ACCEPT_ENCODING));
    }

    @Test
    void repeatedKeyNeverReachesTheMutationEntry() throws Exception {
        var response = assertRejected(create("""
                {"type":"HR_TICKET","name":"name","body":"body"}
                """).header(KEY_HEADER, "second-key"), 400, "invalid_input");
        assertEquals(KEY_HEADER, readJson(response).get("invalidFields").get(0).asString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateObjects")
    void rejectsMalformedOrInexactCreateObjects(String scenario, String body) throws Exception {
        assertRejected(create(body), 400, "invalid_input");
    }

    static Stream<Arguments> invalidCreateObjects() {
        return Stream.of(
                Arguments.of("empty body", ""),
                Arguments.of("JSON whitespace only", "  \n\t"),
                Arguments.of("array root", "[]"),
                Arguments.of("string root", "\"secret\""),
                Arguments.of("null root", "null"),
                Arguments.of("missing type", "{\"name\":\"n\",\"body\":\"b\"}"),
                Arguments.of("missing name", "{\"type\":\"HR_TICKET\",\"body\":\"b\"}"),
                Arguments.of("missing body", "{\"type\":\"HR_TICKET\",\"name\":\"n\"}"),
                Arguments.of("null type", "{\"type\":null,\"name\":\"n\",\"body\":\"b\"}"),
                Arguments.of("null name", createWithName("null")),
                Arguments.of("null body", "{\"type\":\"HR_TICKET\",\"name\":\"n\",\"body\":null}"),
                Arguments.of("unsupported type", "{\"type\":\"OTHER\",\"name\":\"n\",\"body\":\"b\"}"),
                Arguments.of("case-sensitive type", "{\"type\":\"hr_ticket\",\"name\":\"n\",\"body\":\"b\"}"),
                Arguments.of("type coercion", "{\"type\":1,\"name\":\"n\",\"body\":\"b\"}"),
                Arguments.of("number coercion", createWithName("4")),
                Arguments.of("boolean coercion", createWithName("true")),
                Arguments.of("array coercion", createWithName("[\"n\"]")),
                Arguments.of("object coercion", createWithName("{\"secret\":\"n\"}")),
                Arguments.of("duplicate member", "{\"type\":\"HR_TICKET\",\"name\":\"a\",\"name\":\"b\",\"body\":\"c\"}"),
                Arguments.of("escape-equivalent duplicate", "{\"type\":\"HR_TICKET\",\"name\":\"a\",\"n\\u0061me\":\"b\",\"body\":\"c\"}"),
                Arguments.of("unknown member", VALID_CREATE.replace("\"body\":", "\"raw-secret\":\"value\",\"body\":")),
                Arguments.of("client scope", VALID_CREATE.replace("\"body\"", "\"scope\":\"client-secret\",\"body\"")),
                Arguments.of("trailing JSON", VALID_CREATE + "{}"),
                Arguments.of("trailing comma", VALID_CREATE.replace("\"body\":\"body\"}", "\"body\":\"body\",}")),
                Arguments.of("comment", "/* secret */" + VALID_CREATE),
                Arguments.of("empty name", createWithName("\"\"")),
                Arguments.of("Unicode White_Space name", createWithName("\"\\u0085\\u00a0\\u2007\\u202f\"")),
                Arguments.of("Unicode White_Space body", VALID_CREATE.replace("\"body\"}", "\"\\u3000\\u2028\"}")),
                Arguments.of("unpaired high surrogate", createWithName("\"bad\\uD800value\"")),
                Arguments.of("unpaired low surrogate", createWithName("\"bad\\uDC00value\"")),
                Arguments.of("escaped NUL", createWithName("\"bad\\u0000value\"")),
                Arguments.of("raw NUL", createWithName("\"bad" + (char) 0 + "value\"")),
                Arguments.of("name over decoded byte bound", createWithName("\"" + "é".repeat(129) + "\"")),
                Arguments.of("body over decoded byte bound",
                        JSON.writeValueAsString(Map.of("type", "HR_TICKET", "name", "n", "body", "漢".repeat(5462)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUtf8")
    void rejectsInvalidRequestEncoding(String scenario, byte[] bytes) throws Exception {
        assertRejected(create("").content(bytes), 400, "invalid_input");
    }

    static Stream<Arguments> invalidUtf8() throws IOException {
        return Stream.of(
                Arguments.of("lone continuation byte", badNameBytes((byte) 0x80)),
                Arguments.of("invalid continuation", badNameBytes((byte) 0xc3, (byte) 0x28)),
                Arguments.of("overlong NUL", badNameBytes((byte) 0xc0, (byte) 0x80)),
                Arguments.of("UTF-8 encoding of a surrogate", badNameBytes((byte) 0xed, (byte) 0xa0, (byte) 0x80)),
                Arguments.of("unlabelled UTF-16LE", VALID_CREATE.getBytes(UTF_16LE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset=UTF-16", "application/problem+json", "application/*",
            "application/json, text/plain",
            "application/json; charset=invalid-charset"})
    void rejectsUnsupportedJsonMedia(String media) throws Exception {
        assertRejected(create(VALID_CREATE).contentType(media), 415, "unsupported_media");
    }

    @Test
    void missingContentTypeIsUnsupported() throws Exception {
        assertRejected(post("/requests").content(VALID_CREATE).header(KEY_HEADER, KEY), 415, "unsupported_media");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain"})
    void rejectsMultipleContentTypeFieldsBeforeMutation(String repeatedType) throws Exception {
        RequestBuilder request = servletContext -> {
            var types = List.of(MediaType.APPLICATION_JSON_VALUE, repeatedType);
            // MockHttpServletRequest.addHeader would collapse the two field lines.
            var mock = new MockHttpServletRequest(servletContext, "POST", "/requests") {
                @Override
                public Enumeration<String> getHeaders(String name) {
                    return HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                            ? Collections.enumeration(types) : super.getHeaders(name);
                }
            };
            mock.setContentType(types.getFirst());
            mock.addHeader(KEY_HEADER, KEY);
            mock.setContent(VALID_CREATE.getBytes(UTF_8));
            assertEquals(types, Collections.list(mock.getHeaders(HttpHeaders.CONTENT_TYPE)));
            assertEquals(MediaType.APPLICATION_JSON_VALUE, mock.getContentType());
            return mock;
        };
        var response = assertRejected(request, 415, "unsupported_media");
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getHeader(HttpHeaders.ACCEPT));
    }

    @Test
    void advertisesJsonForUnsupportedPatchMedia() throws Exception {
        var response = assertRejected(edit(VALID_EDIT).contentType("text/plain"), 415, "unsupported_media");
        assertEquals("application/json", response.getHeader(HttpHeaders.ACCEPT_PATCH));
    }

    @Test
    void advertisesSupportedCodingsForUnsupportedCoding() throws Exception {
        var response = assertRejected(create(VALID_CREATE).header(HttpHeaders.CONTENT_ENCODING, "br"),
                415, "unsupported_media");
        assertEquals("gzip, identity", response.getHeader(HttpHeaders.ACCEPT_ENCODING));
        assertNull(response.getHeader(HttpHeaders.ACCEPT));
    }

    @Test
    void malformedGzipIsInvalidInput() throws Exception {
        assertRejected(create(VALID_CREATE).header(HttpHeaders.CONTENT_ENCODING, "gzip"), 400, "invalid_input");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOneByteBeyondDecompressedBodyLimit(boolean gzip) throws Exception {
        assertRejected(sizedCreate(128 * 1024 + 1, gzip), 413, "request_too_large");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsExactDecompressedBodyLimit(boolean gzip) throws Exception {
        assertSuccess(sizedCreate(128 * 1024, gzip), 201);
        assertEquals(1, MUTATIONS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u00a0\u202f", "first, second", "bad\u0000key"})
    void rejectsBadKeyValues(String key) throws Exception {
        assertRejected(post("/requests").contentType("application/json").content(VALID_CREATE).header(KEY_HEADER, key),
                400, "invalid_input");
    }

    @Test
    void rejectsMissingKey() throws Exception {
        assertRejected(post("/requests").contentType("application/json").content(VALID_CREATE), 400, "invalid_input");
    }

    @Test
    void rejectsCaseVariantRepeatedKeyAndOversizeKey() throws Exception {
        assertRejected(create(VALID_CREATE).header("idempotency-key", "second-key"), 400, "invalid_input");
        assertRejected(post("/requests").contentType("application/json").content(VALID_CREATE)
                .header(KEY_HEADER, "k".repeat(1025)), 400, "invalid_input");
    }

    @Test
    void acceptsExactDecodedTextAndKeyBounds() throws Exception {
        var key = "k".repeat(1024);
        var name = "é".repeat(128);
        var body = "漢".repeat(5461) + "x";
        var request = post("/requests").contentType("application/json; charset=UTF-8").header(KEY_HEADER, key)
                .content(JSON.writeValueAsBytes(Map.of("type", "INTERNAL_REQUEST", "name", name, "body", body)));
        assertSuccess(request, 201);
        assertEquals(new RestInputs.Create(key, "INTERNAL_REQUEST", name, body), LAST_MUTATION.get());
        assertEquals(1, MUTATIONS.get());
    }

    @Test
    void preservesExactMultilingualBytesThroughCreateAndDetailAndExactIdStrings() throws Exception {
        var name = "  ŻóŁć Café Καλημέρα \u00a0";
        var rawBody = "\n日本語 e\u0301 العربية \\ quote\" 🐈 \u202f  ";
        var payload = JSON.writeValueAsBytes(Map.of("type", "BUG_REPORT", "name", name, "body", rawBody));
        var created = assertSuccess(create("").content(payload), 201);
        assertEquals("/requests/" + REF, created.getHeader(HttpHeaders.LOCATION));
        assertEquals(ID, readJson(created).get("resultRevision").asString());
        assertTrue(readJson(created).get("resultRevision").isString());
        assertEquals(new RestInputs.Create(KEY, "BUG_REPORT", name, rawBody), LAST_MUTATION.get());
        var detail = readJson(assertSuccess(get("/requests/{ref}", REF), 200));
        assertArrayEquals(name.getBytes(UTF_8), detail.get("name").asString().getBytes(UTF_8));
        assertArrayEquals(rawBody.getBytes(UTF_8), detail.get("body").asString().getBytes(UTF_8));
        assertTrue(detail.get("revision").isString());
        assertEquals(ID, detail.get("revision").asString());
        assertFalse(detail.has("expectedRevision"));
        assertEquals(1, MUTATIONS.get());
    }

    @Test
    void preservesEscapedSupplementaryPairAndNonWhiteSpaceUnicode() throws Exception {
        assertSuccess(create(createWithName("\"x\\uD83D\\uDC08\\u200B\\u001c\"")), 201);
        assertEquals("x🐈\u200B\u001c", assertInstanceOf(RestInputs.Create.class, LAST_MUTATION.get()).name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "9223372036854775807", "\"1\"", "\"9223372036854775807\"", "\"0001\"",
            "null", "true", "{}", "\"\"", "\"\\u00a0\\u202f\"", "\"bad\\u0000token\"", "\"\\uD800\""})
    void rejectsInvalidRevisionForms(String value) throws Exception {
        var editBody = "{\"body\":\"edited\",\"expectedRevision\":" + value + "}";
        assertRejected(edit(editBody), 400, "invalid_input");
    }

    @Test
    void acceptsExactTokenBoundAndRejectsOneByteExtra() throws Exception {
        var token = "é".repeat(1024);
        assertRejected(edit(JSON.writeValueAsString(Map.of("body", "ok", "expectedRevision", token + "x"))),
                400, "invalid_input");
        assertSuccess(edit(JSON.writeValueAsString(Map.of("body", "ok", "expectedRevision", token))), 200);
        assertEquals(new RestInputs.Edit(KEY, REF, "ok", token), LAST_MUTATION.get());
    }

    @Test
    void editAcceptsOnlyBodyAndObservation() throws Exception {
        assertRejected(edit("{\"body\":\"edited\"}"), 400, "invalid_input");
        assertRejected(edit("{\"expectedRevision\":\"observation\"}"), 400, "invalid_input");
        assertRejected(edit("{\"name\":\"ignored\",\"body\":\"edited\",\"expectedRevision\":\"observation\"}"),
                400, "invalid_input");
        assertSuccess(edit(VALID_EDIT), 200);
        assertEquals(new RestInputs.Edit(KEY, REF, "edited", OBSERVATION), LAST_MUTATION.get());
        assertEquals(1, MUTATIONS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"verify", "accept", "publish"})
    void namedTransitionAcceptsOnlyObservation(String action) throws Exception {
        assertRejected(action(action, "{\"expectedRevision\":\"observation\",\"reason\":\"extra\"}"), 400,
                "invalid_input");
        assertRejected(action(action, "{}"), 400, "invalid_input");
        var response = readJson(assertSuccess(action(action, VALID_TRANSITION), 200));
        assertEquals(new RestInputs.ActionCommand(KEY, REF, action, OBSERVATION, Optional.empty()), LAST_MUTATION.get());
        assertEquals(action, response.get("action").asString());
        if (action.equals("publish")) {
            assertTrue(response.get("publicationId").isString());
            assertEquals(ID, response.get("publicationId").asString());
        }
        assertEquals(1, MUTATIONS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "delete"})
    void reasonedTransitionRequiresObservationAndReason(String action) throws Exception {
        assertRejected(action(action, VALID_TRANSITION), 400, "invalid_input");
        assertRejected(action(action, "{\"reason\":\"because\"}"), 400, "invalid_input");
        assertRejected(action(action, "{\"expectedRevision\":\"observation\",\"reason\":null}"), 400, "invalid_input");
        assertRejected(action(action, "{\"expectedRevision\":\"observation\",\"reason\":\"\\u0085\\u00a0\"}"),
                400, "invalid_input");
        assertRejected(action(action, reasonBody("é".repeat(1024) + "x")), 400, "invalid_input");
        var reason = "  Ż" + "é".repeat(1022);
        assertSuccess(action(action, reasonBody(reason)), 200);
        assertEquals(new RestInputs.ActionCommand(KEY, REF, action, OBSERVATION, Optional.of(reason)),
                LAST_MUTATION.get());
        assertEquals(1, MUTATIONS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERIFY", "edit", "create", "raw-secret"})
    void invalidTransitionNameNeverMutates(String action) throws Exception {
        assertRejected(action(action, VALID_TRANSITION), 400, "invalid_input");
    }

    @Test
    void invalidCommandQueryNeverMutates() throws Exception {
        assertRejected(create(VALID_CREATE).queryParam("scope", "client-secret"), 400, "invalid_input");
        assertRejected(edit(VALID_EDIT).queryParam("unexpected", "q"), 400, "invalid_input");
        assertRejected(action("verify", VALID_TRANSITION).queryParam("action", "accept"), 400, "invalid_input");
    }

    @Test
    void keyIssuanceIsBodylessAndParameterless() throws Exception {
        assertRejected(post("/operation-keys").queryParam("scope", "client-secret"), 400, "invalid_input");
        assertRejected(post("/operation-keys").contentType("application/json").content("{}"), 400, "invalid_input");
        var response = readJson(assertSuccess(post("/operation-keys"), 201));
        assertEquals(Set.of("key", "expiresAt"), Set.copyOf(response.propertyNames()));
        assertEquals(0, MUTATIONS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "verify", "accept", "reject", "delete", "publish"})
    void acceptsExactlyOneNamedActionContextParameter(String action) throws Exception {
        var response = assertSuccess(get("/requests/{ref}/action-context", REF).queryParam("action", action), 200);
        assertEquals(action, readJson(response).get("action").asString());
        assertEquals(0, MUTATIONS.get());
    }

    @Test
    void rejectsMissingDuplicateUnknownAndMisCasedContextParameters() throws Exception {
        var route = "/requests/{ref}/action-context";
        assertRejected(get(route, REF), 400, "invalid_input");
        assertRejected(get(route, REF).queryParam("action", "edit", "verify"), 400, "invalid_input");
        assertRejected(get(route, REF).queryParam("action", "edit").queryParam("scope", "client-secret"),
                400, "invalid_input");
        assertRejected(get(route, REF).queryParam("action", "Edit"), 400, "invalid_input");
        assertRejected(get(route, REF).queryParam("action", "edit").content("{}"), 400, "invalid_input");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name=%C0%80", "name=%FF", "name=%2", "name=%00", "name=%C2%A0",
            "name=a&%6Eame=b", "name=a&", "name=a&&state=CREATED", "name", "tenant=secret",
            "state=created", "state=OTHER", "size=0", "size=51", "size=-1", "size=1.0", "size=2147483648",
            "size=1&size=2", "cursor=", "cursor=%C3%A9"})
    void rejectsInvalidBrowseQueriesFromRawRequest(String query) throws Exception {
        assertRejected(get("/requests").with(request -> {
            request.setQueryString(query);
            return request;
        }), 400, "invalid_input");
    }

    @Test
    void preservesBrowseFiltersAndEnforcesCursorAndPageLimits() throws Exception {
        var name = "  ŻóŁć+日本語\u202f ";
        var cursor = "c".repeat(2048);
        assertRejected(get("/requests").queryParam("cursor", cursor + "x"), 400, "invalid_input");
        assertRejected(get("/requests").queryParam("name", "é".repeat(129)), 400, "invalid_input");
        assertRejected(get("/requests").content("{}"), 400, "invalid_input");
        var defaults = readJson(assertSuccess(get("/requests"), 200));
        assertEquals(10, defaults.get("size").asInt());
        var query = get(URI.create("/requests?state=CREATED&size=50&cursor=" + cursor
                + "&name=%20%20%C5%BB%C3%B3%C5%81%C4%87%2B%E6%97%A5%E6%9C%AC%E8%AA%9E%E2%80%AF%20"));
        var response = readJson(assertSuccess(query, 200));
        assertArrayEquals(name.getBytes(UTF_8), response.get("name").asString().getBytes(UTF_8));
        assertEquals("CREATED", response.get("state").asString());
        assertEquals(50, response.get("size").asInt());
        assertEquals(cursor, response.get("cursor").asString());
        assertEquals(0, MUTATIONS.get());
    }

    @Test
    void detailAndHistoryRejectQueryAndBodyAndPreserveBoundedOpaqueRef() throws Exception {
        assertRejected(get("/requests/{ref}", REF).queryParam("scope", "client-secret"), 400, "invalid_input");
        assertRejected(get("/requests/{ref}/history", REF).content("{}"), 400, "invalid_input");
        assertRejected(get("/requests/{ref}/history", "é".repeat(65)), 400, "invalid_input");
        assertRejected(get(URI.create("/requests/%FF/history")), 400, "invalid_input");
        var ref = "Éé".repeat(32);
        var response = readJson(assertSuccess(get("/requests/{ref}/history", ref), 200));
        assertEquals(ref, response.get("ref").asString());
        assertEquals(0, MUTATIONS.get());
    }

    @Test
    void methodProblemMatchesImplicitHeadAndOptionsSupportedByMvc() throws Exception {
        assertEquals(200, perform(head("/requests")).getStatus());
        var options = perform(options("/requests"));
        assertEquals(200, options.getStatus());
        assertEquals(Set.of("GET", "HEAD", "POST", "OPTIONS"), allowedMethods(options));
        var wrongMethod = assertRejected(delete("/requests"), 405, "method_not_allowed");
        assertEquals(allowedMethods(options), allowedMethods(wrongMethod));
    }

    @Test
    void methodProblemDoesNotAddHeadForPatchOnlyRoute() throws Exception {
        var route = "/requests/{ref}/body";
        var options = perform(options(route, REF));
        assertEquals(200, options.getStatus());
        assertEquals(Set.of("PATCH", "OPTIONS"), allowedMethods(options));
        var wrongMethod = assertRejected(head(route, REF), 405, "method_not_allowed");
        assertEquals(allowedMethods(options), allowedMethods(wrongMethod));
    }

    @Test
    void otherProtocolFailuresUseSafeProblems() throws Exception {
        assertRejected(get("/unmapped/never-echo"), 404, "not_found");
        assertRejected(get("/requests/{ref}", REF).accept(MediaType.TEXT_PLAIN), 406, "not_acceptable");
        assertRejected(create("{\"secret\":null}").accept(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON),
                400, "invalid_input");
    }

    @Test
    void unexpectedFaultReplacesUncommittedBodyAndHeaders() throws Exception {
        var response = assertRejected(get("/fixture/unexpected").header("X-Correlation-Id", "never-echo"),
                500, "internal_error");
        var problem = readJson(response);
        assertEquals("Internal server error", problem.get("title").asString());
        assertFalse(response.getContentAsString(UTF_8).contains("never-echo"));
    }

    @Test
    void committedResponseIsNeverFollowedByASecondPayload() throws Exception {
        var response = perform(get("/fixture/committed"));
        assertEquals(200, response.getStatus());
        assertEquals("{}", response.getContentAsString(UTF_8));
    }

    private static String createWithName(String jsonValue) {
        return "{\"type\":\"HR_TICKET\",\"name\":" + jsonValue + ",\"body\":\"body\"}";
    }

    private static byte[] badNameBytes(byte... bad) throws IOException {
        var payload = new ByteArrayOutputStream();
        payload.write("{\"type\":\"HR_TICKET\",\"name\":\"".getBytes(UTF_8));
        payload.write(bad);
        payload.write("\",\"body\":\"body\"}".getBytes(UTF_8));
        return payload.toByteArray();
    }

    private static String reasonBody(String reason) {
        return JSON.writeValueAsString(Map.of("expectedRevision", OBSERVATION, "reason", reason));
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/requests").contentType("application/json").header(KEY_HEADER, KEY).content(body);
    }

    private static MockHttpServletRequestBuilder edit(String body) {
        return patch("/requests/{ref}/body", REF).contentType("application/json").header(KEY_HEADER, KEY).content(body);
    }

    private static MockHttpServletRequestBuilder action(String action, String body) {
        return post("/requests/{ref}/actions/{action}", REF, action)
                .contentType("application/json").header(KEY_HEADER, KEY).content(body);
    }

    private static RequestBuilder sizedCreate(int size, boolean gzip) throws IOException {
        var payload = VALID_CREATE + " ".repeat(size - VALID_CREATE.getBytes(UTF_8).length);
        if (!gzip) {
            return create(payload);
        }
        var compressed = new ByteArrayOutputStream();
        try (var stream = new GZIPOutputStream(compressed)) {
            stream.write(payload.getBytes(UTF_8));
        }
        assertTrue(compressed.size() < 1024, "encoded bytes alone must appear well within the limit");
        return create("").header(HttpHeaders.CONTENT_ENCODING, "gzip").content(compressed.toByteArray());
    }

    private static MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletResponse assertRejected(RequestBuilder request, int status, String code)
            throws Exception {
        var response = perform(request);
        assertAll(
                () -> assertEquals(status, response.getStatus(), code),
                () -> assertEquals(0, MUTATIONS.get(), "mutation entry calls"),
                () -> assertNull(LAST_MUTATION.get(), "mutation entry input"));
        assertWireHeaders(response, MediaType.APPLICATION_PROBLEM_JSON);
        assertNull(response.getHeader(HttpHeaders.LOCATION));
        assertNull(response.getHeader("X-Fixture-Secret"));
        var problem = readJson(response);
        assertEquals(status, problem.get("status").asInt());
        assertEquals("urn:requests:problem:" + code, problem.get("type").asString());
        assertFalse(problem.get("title").asString().isBlank());
        var correlation = problem.get("correlationId").asString();
        assertEquals(correlation, UUID.fromString(correlation).toString());
        assertEquals("urn:uuid:" + correlation, problem.get("instance").asString());
        var properties = Set.copyOf(problem.propertyNames());
        if (problem.has("invalidFields")) {
            assertEquals(Set.of("status", "type", "title", "correlationId", "instance", "invalidFields"), properties);
            for (var field : problem.get("invalidFields")) {
                assertTrue(SAFE_FIELDS.contains(field.asString()));
            }
        } else {
            assertEquals(Set.of("status", "type", "title", "correlationId", "instance"), properties);
        }
        return response;
    }

    private static MockHttpServletResponse assertSuccess(RequestBuilder request, int status) throws Exception {
        var response = perform(request);
        assertEquals(status, response.getStatus());
        assertWireHeaders(response, MediaType.APPLICATION_JSON);
        return response;
    }

    private static void assertWireHeaders(MockHttpServletResponse response, MediaType expected) {
        assertTrue(expected.equalsTypeAndSubtype(MediaType.parseMediaType(response.getContentType())));
        assertEquals("UTF-8", response.getCharacterEncoding());
        assertEquals(List.of("no-store"), List.copyOf(response.getHeaders(HttpHeaders.CACHE_CONTROL)));
    }

    private static JsonNode readJson(MockHttpServletResponse response) {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private static Set<String> allowedMethods(MockHttpServletResponse response) {
        return Set.copyOf(Arrays.asList(response.getHeader(HttpHeaders.ALLOW).split(",\\s*")));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestRoutes {
        @Bean
        TestController testController(RestInputValidator validator) {
            return new TestController(validator);
        }
    }

    /** Test endpoint implementations use the MVC beans without providing production command or authority behavior. */
    @RestController
    static class TestController {
        private final RestInputValidator validator;

        TestController(RestInputValidator validator) {
            this.validator = validator;
        }

        @PostMapping(path = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Map<String, String>> create(HttpServletRequest request) {
            enterMutation(validator.create(request));
            return ResponseEntity.created(URI.create("/requests/" + REF)).body(result("create"));
        }

        @PatchMapping(path = "/requests/{ref}/body", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> edit(HttpServletRequest request, @PathVariable("ref") String ref) {
            enterMutation(validator.edit(request, ref));
            return result("edit");
        }

        @PostMapping(path = "/requests/{ref}/actions/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> action(HttpServletRequest request, @PathVariable("ref") String ref,
                                   @PathVariable("action") String action) {
            enterMutation(validator.action(request, ref, action));
            return result(action);
        }

        @PostMapping(path = "/operation-keys", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Map<String, String>> issue(HttpServletRequest request) {
            validator.keyIssuance(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("key", "fixture", "expiresAt", "2026-11-23T00:00:00Z"));
        }

        @GetMapping(path = "/requests/{ref}", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> detail(HttpServletRequest request, @PathVariable("ref") String ref) {
            var target = validator.reference(request, ref);
            var created = assertInstanceOf(RestInputs.Create.class, LAST_MUTATION.get());
            return Map.of("ref", target, "type", created.type(), "name", created.name(), "body", created.body(),
                    "revision", ID, "state", "CREATED");
        }

        @GetMapping(path = "/requests/{ref}/history", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> history(HttpServletRequest request, @PathVariable("ref") String ref) {
            return Map.of("ref", validator.reference(request, ref), "changes", List.of());
        }

        @GetMapping(path = "/requests/{ref}/action-context", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> context(HttpServletRequest request, @PathVariable("ref") String ref) {
            var context = validator.actionContext(request, ref);
            return Map.of("ref", context.ref(), "action", context.action(), "state", "CREATED",
                    "context", Map.of(), "expectedRevision", OBSERVATION);
        }

        @GetMapping(path = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> browse(HttpServletRequest request) {
            var browse = validator.browse(request);
            // Echoes the validated values for the in-process wire fixture.
            return Map.of("name", browse.name().orElse(""), "state", browse.state().orElse(""),
                    "size", browse.size(), "cursor", browse.cursor().orElse(""));
        }

        @GetMapping("/fixture/unexpected")
        void unexpected(HttpServletResponse response) throws IOException {
            response.setHeader(HttpHeaders.LOCATION, "/never-echo");
            response.setHeader("X-Fixture-Secret", "never-echo");
            response.getOutputStream().print("never-echo");
            throw new IllegalStateException("never-echo");
        }

        @GetMapping("/fixture/committed")
        void committed(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().print("{}");
            response.flushBuffer();
            throw new IllegalStateException("never-echo");
        }

        private static void enterMutation(Object input) {
            LAST_MUTATION.set(input);
            MUTATIONS.incrementAndGet();
        }

        private static Map<String, String> result(String action) {
            if (action.equals("publish")) {
                return Map.of("ref", REF, "action", action, "resultRevision", ID, "state", "PUBLISHED",
                        "publicationId", ID);
            }
            return Map.of("ref", REF, "action", action, "resultRevision", ID, "state", "CREATED");
        }
    }
}
