package com.company.requests.bootstrap;

import static com.company.requests.bootstrap.OpenApiMvcFixture.CREATED_REF;
import static com.company.requests.bootstrap.OpenApiMvcFixture.FULL_BEARER;
import static com.company.requests.bootstrap.OpenApiMvcFixture.MASKED_BEARER;
import static com.company.requests.bootstrap.OpenApiMvcFixture.ORIGINAL_BODY;
import static com.company.requests.bootstrap.OpenApiMvcFixture.ORIGINAL_NAME;
import static com.company.requests.bootstrap.OpenApiWire.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.company.requests.domain.Action;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class OpenApiContractTest {
    private static final String KEYS = "/operation-keys";
    private static final String REQUESTS = "/requests";
    private static final String DETAIL = "/requests/{ref}";
    private static final String CONTEXT = DETAIL + "/action-context";
    private static final String EDIT = DETAIL + "/body";
    private static final String HISTORY = DETAIL + "/history";
    private static final String TRANSITION = DETAIL + "/actions/";
    private static final String KEY = "Idempotency-Key";
    private static final String PROBLEM = "application/problem+json";
    private static final String APPLICATION_JSON = "application/json";
    private static final String MAX_PUBLICATION_ID = "9223372036854775807";
    private static final Pattern RESTRICTED_ACTION = Pattern.compile("\\{action:([a-z|]+)}");
    private static final Set<String> METHODS = Set.of("get", "post", "patch");
    private static final JsonNode TEXT = JSON.getNodeFactory().stringNode("a-nonblank-value");
    private static OpenApiWire wire;

    @BeforeAll
    static void readSource() throws Exception {
        wire = new OpenApiWire();
    }

    @Test
    void openApiOperationsAndLocalReferencesMatchTheRealSyntheticMappings() {
        wire.verifyDocument();
        try (var fixture = new OpenApiMvcFixture()) {
            var published = declaredOperations(wire);
            assertEquals(12, published.size());
            assertEquals(springOperations(fixture), published);
            assertEquals(routeCases().map(RouteCase::route).collect(java.util.stream.Collectors.toSet()), published);

            for (var route : published) {
                var operation = wire.operation(route.method(), route.path());
                if (route.path().contains("{ref}")) {
                    var reference = wire.resolve(wire.document.required("paths").required(route.path())
                            .required("parameters").get(0));
                    assertParameter(reference, "ref", "path", true);
                }
                if (operation.has("requestBody")) {
                    assertTrue(operation.required("requestBody").required("required").asBoolean());
                    assertParameter(wire.resolve(operation.required("parameters").get(0)), KEY, "header", true);
                    assertParameter(wire.resolve(operation.required("parameters").get(1)), "Content-Encoding", "header", false);
                    assertTrue(wire.matches(wire.requestMedia(route.method(), route.path()).required("schema"),
                            requestExample(route)));
                } else {
                    assertFalse(operation.path("parameters").valueStream().map(wire::resolve)
                            .anyMatch(parameter -> parameter.required("name").asString().equals(KEY)));
                }
                int success = route.path().equals(KEYS) || route.equals(new Route("post", REQUESTS)) ? 201 : 200;
                var media = wire.response(route.method(), route.path(), success).required("content").required(APPLICATION_JSON);
                for (var example : media.required("examples")) {
                    assertTrue(wire.matches(media.required("schema"), example.required("value")),
                            () -> route + " invalid declared example " + example);
                }
            }
            var query = wire.operation("get", REQUESTS).required("parameters");
            assertEquals(List.of("name", "state", "size", "cursor"),
                    query.valueStream().map(node -> node.required("name").asString()).toList());
            var size = query.get(2).required("schema");
            assertEquals("10", size.required("default").asString());
            assertTrue(wire.matches(size, JSON.getNodeFactory().stringNode("0050")));
            assertFalse(wire.matches(size, JSON.getNodeFactory().stringNode("0051")));
            assertFalse(wire.matches(size, JSON.getNodeFactory().stringNode("+10")));
            assertParameter(wire.operation("get", CONTEXT).required("parameters").get(0), "action", "query", true);
        }
    }

    @Test
    void everyOperationRequiresTheHttpBearerSchemeEvenWithAnOverride() throws Exception {
        var missingType = (ObjectNode) wire.document.deepCopy();
        ((ObjectNode) missingType.requiredAt("/components/securitySchemes/BearerAuth")).remove("type");
        var invalidScheme = assertThrows(IllegalArgumentException.class, () -> new OpenApiWire(missingType).verifyDocument());
        assertEquals("Expected BearerAuth with type http and scheme bearer", invalidScheme.getMessage());

        for (String security : List.of("[]", "[{}]", "[{\"BearerAuth\":[]},{}]")) {
            var createOverride = (ObjectNode) wire.document.deepCopy();
            ((ObjectNode) createOverride.requiredAt("/paths/~1requests/post")).set("security", JSON.readTree(security));
            var rejected = assertThrows(IllegalArgumentException.class, () -> new OpenApiWire(createOverride).verifyDocument());
            assertEquals("Expected required BearerAuth for post /requests", rejected.getMessage());
        }

        var anonymousDefault = (ObjectNode) wire.document.deepCopy();
        anonymousDefault.remove("security");
        ((ObjectNode) anonymousDefault.requiredAt("/paths/~1requests/post")).remove("security");
        assertThrows(IllegalArgumentException.class, () -> new OpenApiWire(anonymousDefault).verifyDocument());

        var explicitRequirements = (ObjectNode) wire.document.deepCopy();
        explicitRequirements.remove("security");
        var required = JSON.readTree("[{\"BearerAuth\":[]}]");
        for (var route : declaredOperations(wire)) {
            ((ObjectNode) explicitRequirements.required("paths").required(route.path()).required(route.method()))
                    .set("security", required.deepCopy());
        }
        assertDoesNotThrow(() -> new OpenApiWire(explicitRequirements).verifyDocument());
    }

    @Test
    void rejectsMissingOpenApiStructureAndUnknownLocalReferences() {
        var required = List.of("/openapi", "/info", "/info/title", "/info/version", "/paths",
                "/paths/~1requests/get/responses", "/components/responses/NotFound/description");
        assertAll(required.stream().map(pointer -> () -> {
            var mutant = (ObjectNode) wire.document.deepCopy();
            int separator = pointer.lastIndexOf('/');
            String field = pointer.substring(separator + 1);
            ((ObjectNode) mutant.at(pointer.substring(0, separator))).remove(field);
            var rejected = assertThrows(IllegalArgumentException.class, () -> new OpenApiWire(mutant).verifyDocument());
            assertTrue(rejected.getMessage().endsWith(field), rejected.getMessage());
        }));
        var wrongInfo = ((ObjectNode) wire.document.deepCopy()).put("info", "wrong object type");
        assertThrows(IllegalArgumentException.class, () -> new OpenApiWire(wrongInfo).verifyDocument());
        var unknownRef = (ObjectNode) wire.document.deepCopy();
        ((ObjectNode) unknownRef.requiredAt("/components/schemas/RequestView/properties/ref"))
                .put("$ref", "#/components/schemas/Unknown");
        var rejected = assertThrows(IllegalArgumentException.class, () -> new OpenApiWire(unknownRef).verifyDocument());
        assertTrue(rejected.getMessage().endsWith("/Unknown"), rejected.getMessage());
    }

    @ParameterizedTest
    @MethodSource("routeCases")
    void everyMatchedOperationChallengesBeforeInputOrStorage(RouteCase candidate) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var response = execute(fixture, candidate.route().method(), candidate.uri(), null, null);
            problem(response, wire.response(candidate.route().method(), candidate.route().path(), 401),
                    401, "authentication_required", null);
            requireHeader(wire.response(candidate.route().method(), candidate.route().path(), 401),
                    response, "WWW-Authenticate", "Bearer");
            assertEquals(0, fixture.transactionEntries);
        }
    }

    @Test
    void signsKeysAndReplaysOneCreateWithOriginalLocationAndExactUtf8() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var route = new Route("post", REQUESTS);
            String issued = issue(fixture);
            var input = requestExample(route);
            var created = fixture.mvc.perform(mutation(route, REQUESTS, issued)
                    .header(HttpHeaders.CONTENT_ENCODING, "GZIP").content(gzip(JSON.writeValueAsBytes(input))))
                    .andReturn().getResponse();
            var original = successExample(created, route, 201, "created");
            var reordered = JSON.createObjectNode().set("body", input.required("body"))
                    .set("name", input.required("name")).set("type", input.required("type"));
            var replay = fixture.mvc.perform(mutation(route, REQUESTS, issued).content(JSON.writeValueAsBytes(reordered)))
                    .andReturn().getResponse();
            assertEquals(original, successExample(replay, route, 201, "created"));
            assertArrayEquals(created.getContentAsByteArray(), replay.getContentAsByteArray());
            for (var response : List.of(created, replay)) {
                requireHeader(wire.response("post", REQUESTS, 201), response, "Location", "/requests/" + CREATED_REF);
            }
            assertEquals(1, fixture.createEffects);

            var detail = successful(execute(fixture, "get", "/requests/" + CREATED_REF, FULL_BEARER, null),
                    wire.response("get", DETAIL, 200), 200, APPLICATION_JSON);
            assertEquals(ORIGINAL_NAME, detail.required("name").asString());
            assertEquals(ORIGINAL_BODY, detail.required("body").asString());
            assertEquals("1", detail.required("revision").asString());
            assertFalse(detail.has("expectedRevision"));
            var changed = reordered.deepCopy().put("name", "different");
            var conflict = fixture.mvc.perform(mutation(route, REQUESTS, issued).content(JSON.writeValueAsBytes(changed)))
                    .andReturn().getResponse();
            problem(conflict, wire.response("post", REQUESTS, 409), 409, "key_conflict", null);
            assertEquals(1, fixture.createEffects);
        }
    }

    @ParameterizedTest
    @MethodSource("existingActions")
    void exactCommittedMutationEnvelopesFollowEachActionSchema(Action action) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            String name = action.name().toLowerCase(Locale.ROOT);
            var route = action == Action.EDIT ? new Route("patch", EDIT) : new Route("post", TRANSITION + name);
            String key = issue(fixture);
            String observation = fixture.observationBeforeCommit(action);
            fixture.seedCommittedReplay(action, key, observation);
            var response = fixture.mvc.perform(mutation(route, route.path().replace("{ref}", name + "-ref"), key)
                    .content(observedInput(route, observation))).andReturn().getResponse();
            var result = successExample(response, route, 200, action == Action.EDIT ? "edited" : name);
            assertTrue(result.required("resultRevision").isString());
            assertFalse(result.has("expectedRevision"));
            assertEquals(action == Action.PUBLISH, result.has("publicationId"));
            assertNull(response.getHeader("Location"));
            assertEquals(0, fixture.createEffects);
        }
    }

    @ParameterizedTest
    @MethodSource("existingActions")
    void contextReturnsOneActionSpecificFactAndARealObservation(Action action) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            String name = action.name().toLowerCase(Locale.ROOT);
            var route = new Route("get", CONTEXT);
            String ref = example(route, 200, name).required("ref").asString();
            var response = execute(fixture, "get", "/requests/" + ref + "/action-context?action=" + name,
                    FULL_BEARER, null);
            var actual = successExample(response, route, 200, name, "expectedRevision");
            assertNotEquals(ref, actual.required("expectedRevision").asString());
            var denied = execute(fixture, "get", "/requests/" + ref + "/action-context?action=" + name, MASKED_BEARER, null);
            problem(denied, wire.response("get", CONTEXT, 403), 403, "action_denied", null);
        }
    }

    @Test
    void projectionsOmitMaskedFieldsAndKeepPublicationPrecision() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var route = new Route("get", DETAIL);
            var published = successExample(execute(fixture, "get", "/requests/publish-ref", FULL_BEARER, null),
                    route, 200, "published");
            assertEquals(MAX_PUBLICATION_ID, published.required("publicationId").asString());
            assertEquals("9007199254740993", published.required("revision").asString());
            successExample(execute(fixture, "get", "/requests/publish-ref", MASKED_BEARER, null), route, 200, "masked");
            successExample(execute(fixture, "get", "/requests/delete-ref", FULL_BEARER, null), route, 200, "deleted");
            fixture.removePublicationConfirmation();
            problem(execute(fixture, "get", "/requests/publish-ref", FULL_BEARER, null),
                    wire.response("get", DETAIL, 503), 503, "authority_unavailable", null);
        }
    }

    @Test
    void deletedBrowseDoesNotExposeRetainedNameOrPublicationId() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var response = execute(fixture, "get", "/requests?state=DELETED", FULL_BEARER, null);
            var page = successful(response, wire.response("get", REQUESTS, 200), 200, APPLICATION_JSON);
            var expected = JSON.createObjectNode();
            expected.putArray("items").addObject().put("ref", "delete-ref").put("state", "DELETED");
            assertEquals(expected, page);

            var item = (ObjectNode) page.required("items").get(0);
            var schema = wire.schema("BrowseItem");
            var detail = (ObjectNode) successful(execute(fixture, "get", "/requests/delete-ref", FULL_BEARER, null),
                    wire.response("get", DETAIL, 200), 200, APPLICATION_JSON);
            assertEquals(item, detail);
            var detailSchema = wire.schema("RequestView");
            assertAll(
                    () -> assertFalse(wire.matches(schema, item.deepCopy().put("name", ORIGINAL_NAME)),
                            "Deleted Browse item must omit the retained name"),
                    () -> assertFalse(wire.matches(schema, item.deepCopy().put("publicationId", MAX_PUBLICATION_ID)),
                            "Deleted Browse item must omit the publication ID"),
                    () -> assertFalse(wire.matches(detailSchema, detail.deepCopy().put("name", ORIGINAL_NAME))),
                    () -> assertFalse(wire.matches(detailSchema, detail.deepCopy().put("body", ORIGINAL_BODY))),
                    () -> assertFalse(wire.matches(detailSchema, detail.deepCopy().put("revision", "2"))),
                    () -> assertFalse(wire.matches(detailSchema, detail.deepCopy().put("publicationId", MAX_PUBLICATION_ID))));
        }
    }

    @ParameterizedTest
    @CsvSource({"edit-ref, CREATED", "verify-ref, VERIFIED"})
    void publicationIdRequiresPublishedWhenStateIsVisible(String ref, String state) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var detail = (ObjectNode) successful(execute(fixture, "get", "/requests/" + ref, FULL_BEARER, null),
                    wire.response("get", DETAIL, 200), 200, APPLICATION_JSON);
            var page = successful(execute(fixture, "get", "/requests?state=" + state, FULL_BEARER, null),
                    wire.response("get", REQUESTS, 200), 200, APPLICATION_JSON);
            assertEquals(1, page.required("items").size());
            var item = (ObjectNode) page.required("items").get(0);
            for (var projection : List.of(detail, item)) {
                assertEquals(ref, projection.required("ref").asString());
                assertEquals(state, projection.required("state").asString());
                assertFalse(projection.has("publicationId"));
            }
            assertAll(
                    () -> assertFalse(wire.matches(wire.schema("RequestView"),
                            detail.deepCopy().put("publicationId", MAX_PUBLICATION_ID)),
                            state + " Detail must omit the publication ID"),
                    () -> assertFalse(wire.matches(wire.schema("BrowseItem"),
                            item.deepCopy().put("publicationId", MAX_PUBLICATION_ID)),
                            state + " Browse item must omit the publication ID"));
        }
    }

    @Test
    void publicationIdRemainsVisibleWhenStateIsMasked() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            fixture.grantMaskedReaderPublicationId();
            var expected = JSON.createObjectNode().put("ref", "publish-ref").put("publicationId", MAX_PUBLICATION_ID);
            var actual = successful(execute(fixture, "get", "/requests/publish-ref", MASKED_BEARER, null),
                    wire.response("get", DETAIL, 200), 200, APPLICATION_JSON);
            assertEquals(expected, actual);
            var page = successful(execute(fixture, "get", "/requests?size=1", MASKED_BEARER, null),
                    wire.response("get", REQUESTS, 200), 200, APPLICATION_JSON);
            assertEquals(1, page.required("items").size());
            assertEquals(expected, page.required("items").get(0));
        }
    }

    @Test
    void browseOmitsAbsentCursorAndMasksEachRowBeforeLookahead() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var route = new Route("get", REQUESTS);
            successExample(execute(fixture, "get", "/requests?state=PUBLISHED&size=01", FULL_BEARER, null),
                    route, 200, "published");
            successExample(execute(fixture, "get", "/requests?name=nonexistent", FULL_BEARER, null), route, 200, "empty");
            var page = successExample(execute(fixture, "get", "/requests?size=1", MASKED_BEARER, null),
                    route, 200, "maskedContinuation", "nextCursor");
            String cursor = page.required("nextCursor").asString();
            var next = successful(execute(fixture, "get", "/requests?size=01&cursor=" + cursor, MASKED_BEARER, null),
                    wire.response("get", REQUESTS, 200), 200, APPLICATION_JSON);
            assertEquals("reject-ref", next.required("items").get(0).required("ref").asString());
            problem(execute(fixture, "get", "/requests?size=2&cursor=" + cursor, MASKED_BEARER, null),
                    wire.response("get", REQUESTS, 400), 400, "invalid_input", "cursor");
        }
    }

    @Test
    void historyHasRequiredNullOriginAndItsOwnGrant() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var route = new Route("get", HISTORY);
            successExample(execute(fixture, "get", "/requests/edit-ref/history", FULL_BEARER, null), route, 200, "created");
            problem(execute(fixture, "get", "/requests/edit-ref/history", MASKED_BEARER, null),
                    wire.response("get", HISTORY, 404), 404, "not_found", null);
            problem(execute(fixture, "get", "/requests/absent-ref/history", FULL_BEARER, null),
                    wire.response("get", HISTORY, 404), 404, "not_found", null);
        }
    }

    @Test
    void ineligibleContextReturnsNoObservation() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var response = execute(fixture, "get", "/requests/accept-ref/action-context?action=verify",
                    FULL_BEARER, null);
            problem(response, wire.response("get", CONTEXT, 409), 409, "illegal_state", null);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void exactBodyShapesRejectMismatchBeforeTransactions(BadBody bad) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var media = wire.requestMedia(bad.route().method(), bad.route().path());
            if (bad.schemaCanRepresent()) {
                assertFalse(wire.matches(media.required("schema"), JSON.readTree(bad.body())));
            }
            var response = fixture.mvc.perform(mutation(bad.route(), bad.route().path().replace("{ref}", "edit-ref"),
                    "synthetic-single-key").content(bad.body())).andReturn().getResponse();
            problem(response, wire.response(bad.route().method(), bad.route().path(), 400),
                    400, "invalid_input", bad.field());
            assertEquals(0, fixture.transactionEntries);
        }
    }

    @ParameterizedTest
    @MethodSource("badQuery")
    void rejectsUnexpectedRepeatedAndMalformedQueryBeforeTransactions(BadQuery bad) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            problem(execute(fixture, bad.route().method(), bad.uri(), FULL_BEARER, null),
                    wire.response(bad.route().method(), bad.route().path(), 400), 400, "invalid_input", bad.field());
            assertEquals(0, fixture.transactionEntries);
        }
    }

    @Test
    void validatesOneSignedKeyAndRejectsInvalidSignatureAndExpiry() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var route = new Route("post", REQUESTS);
            var input = JSON.writeValueAsBytes(requestExample(route));
            var invalid = wire.response("post", REQUESTS, 400);
            var missing = base("post", REQUESTS, FULL_BEARER).contentType(APPLICATION_JSON).content(input);
            problem(fixture.mvc.perform(missing).andReturn().getResponse(), invalid, 400, "invalid_input", KEY);
            var repeated = mutation(route, REQUESTS, "key-one").header(KEY, "key-two").content(input);
            problem(fixture.mvc.perform(repeated).andReturn().getResponse(), invalid, 400, "invalid_input", KEY);
            var combined = mutation(route, REQUESTS, "key-one, key-two").content(input);
            problem(fixture.mvc.perform(combined).andReturn().getResponse(), invalid, 400, "invalid_input", KEY);
            assertEquals(0, fixture.transactionEntries);
            assertFalse(wire.matches(wire.schema("SignedKey"), JSON.getNodeFactory().stringNode("key-one, key-two")));

            String issued = issue(fixture);
            int signature = issued.lastIndexOf('.') + 1;
            assertTrue(signature > 0);
            char replacement = issued.charAt(signature) == 'A' ? 'B' : 'A';
            String altered = issued.substring(0, signature) + replacement + issued.substring(signature + 1);
            problem(fixture.mvc.perform(mutation(route, REQUESTS, altered).content(input)).andReturn().getResponse(),
                    invalid, 400, "invalid_input", KEY);
            fixture.expireKey();
            problem(fixture.mvc.perform(mutation(route, REQUESTS, issued).content(input)).andReturn().getResponse(),
                    wire.response("post", REQUESTS, 409), 409, "key_expired", null);
            assertEquals(0, fixture.createEffects);
        }
    }

    @Test
    void mediaProblemsAdvertiseOnlyTheApplicableProtocolHeaders() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var route = new Route("patch", EDIT);
            String uri = "/requests/edit-ref/body";
            var declared = wire.response("patch", EDIT, 415);
            var media = fixture.mvc.perform(mutation(route, uri, "synthetic-key")
                    .contentType("text/plain").content("{}")).andReturn().getResponse();
            problem(media, declared, 415, "unsupported_media", null);
            requireHeader(declared, media, "Accept", APPLICATION_JSON);
            requireHeader(declared, media, "Accept-Patch", APPLICATION_JSON);
            assertNull(media.getHeader("Accept-Encoding"));
            var encoding = fixture.mvc.perform(mutation(route, uri, "synthetic-key")
                    .header(HttpHeaders.CONTENT_ENCODING, "br").content("{}")).andReturn().getResponse();
            problem(encoding, declared, 415, "unsupported_media", null);
            requireHeader(declared, encoding, "Accept-Encoding", "gzip, identity");
            assertNull(encoding.getHeader("Accept"));
            assertNull(encoding.getHeader("Accept-Patch"));
            var tooLarge = fixture.mvc.perform(mutation(route, uri, "synthetic-key")
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip").content(gzip(new byte[131_073]))).andReturn().getResponse();
            problem(tooLarge, wire.response("patch", EDIT, 413), 413, "request_too_large", null);
            var unacceptable = fixture.mvc.perform(base("get", "/requests/edit-ref", FULL_BEARER)
                    .accept(MediaType.TEXT_HTML)).andReturn().getResponse();
            problem(unacceptable, wire.response("get", DETAIL, 406), 406, "not_acceptable", null);
            assertEquals(0, fixture.transactionEntries);
        }
    }

    @Test
    void bodylessOperationsRejectBytesAndKeyIssueRequiresItsGrant() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            for (var candidate : List.of(new RouteCase(new Route("post", KEYS), KEYS),
                    new RouteCase(new Route("get", REQUESTS), REQUESTS),
                    new RouteCase(new Route("get", DETAIL), "/requests/edit-ref"),
                    new RouteCase(new Route("get", HISTORY), "/requests/edit-ref/history"),
                    new RouteCase(new Route("get", CONTEXT), "/requests/edit-ref/action-context?action=edit"))) {
                var route = candidate.route();
                problem(execute(fixture, route.method(), candidate.uri(), FULL_BEARER, "{}"),
                        wire.response(route.method(), route.path(), 400), 400, "invalid_input", null);
            }
            assertEquals(0, fixture.transactionEntries);
            problem(execute(fixture, "post", KEYS, MASKED_BEARER, null), wire.response("post", KEYS, 403),
                    403, "action_denied", null);
        }
    }

    @ParameterizedTest
    @MethodSource("missingRoutes")
    void pathMismatchesAreSafeNotFound(String uri) throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            problem(execute(fixture, "post", uri, FULL_BEARER, null), wire.routingResponse(404), 404, "not_found", null);
            assertEquals(0, fixture.transactionEntries);
        }
    }

    @Test
    void methodMismatchUsesSpringAllowAndDoesNotRevealRequestData() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var response = execute(fixture, "put", "/requests/edit-ref", FULL_BEARER, null);
            var declared = wire.routingResponse(405);
            problem(response, declared, 405, "method_not_allowed", null);
            var allowed = Arrays.stream(response.getHeader("Allow").split(",")).map(String::strip).toList();
            assertEquals(Set.of("GET", "HEAD", "OPTIONS"), Set.copyOf(allowed));
            assertTrue(wire.matches(wire.resolve(declared.requiredAt("/headers/Allow")).required("schema"),
                    JSON.valueToTree(allowed)));
        }
    }

    @Test
    void returnsSafeScopedAvailabilityErrorsAndRetryAdviceMatchesTheOperation() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            fixture.removeAuthority();
            problem(execute(fixture, "post", KEYS, FULL_BEARER, null),
                    wire.response("post", KEYS, 503), 503, "authority_unavailable", null);
        }
        try (var fixture = new OpenApiMvcFixture()) {
            String key = issue(fixture);
            String observation = fixture.observationBeforeCommit(Action.PUBLISH);
            fixture.seedCommittedReplay(Action.PUBLISH, key, observation);
            fixture.removePublicationConfirmation();
            var route = new Route("post", TRANSITION + "publish");
            var unavailable = fixture.mvc.perform(mutation(route, "/requests/publish-ref/actions/publish", key)
                    .content(observedInput(route, observation))).andReturn().getResponse();
            problem(unavailable, wire.response("post", route.path(), 503), 503, "outcome_unknown", null);
        }
    }

    @Test
    void seededRouteRemovalAndResponseDriftFailTheOriginalOracles() throws Exception {
        try (var fixture = new OpenApiMvcFixture()) {
            var deleted = (ObjectNode) wire.document.deepCopy();
            ((ObjectNode) deleted.required("paths")).remove(TRANSITION + "publish");
            assertNotEquals(springOperations(fixture), declaredOperations(new OpenApiWire(deleted)));

            var publish = new Route("post", TRANSITION + "publish");
            var numericSchemaDocument = (ObjectNode) wire.document.deepCopy();
            ((ObjectNode) numericSchemaDocument.requiredAt("/components/schemas/PublishResult/properties"))
                    .set("publicationId", JSON.createObjectNode().put("type", "number"));
            String key = issue(fixture);
            String observation = fixture.observationBeforeCommit(Action.PUBLISH);
            fixture.seedCommittedReplay(Action.PUBLISH, key, observation);
            var response = fixture.mvc.perform(mutation(publish, "/requests/publish-ref/actions/publish", key)
                    .content(observedInput(publish, observation))).andReturn().getResponse();
            var actual = successExample(response, publish, 200, "publish");
            var numericWire = new OpenApiWire(numericSchemaDocument);
            assertFalse(numericWire.matches(numericWire.schema("PublishResult"), actual));
            assertFalse(wire.matches(wire.schema("PublishResult"),
                    ((ObjectNode) actual.deepCopy()).put("publicationId", Long.MAX_VALUE)));
            assertFalse(wire.matches(wire.schema("PublishResult"),
                    ((ObjectNode) actual.deepCopy()).put("publicationId", "9223372036854775808")));
            assertFalse(wire.matches(wire.schema("PublishResult"), ((ObjectNode) actual.deepCopy()).putNull("publicationId")));
            var withoutId = (ObjectNode) actual.deepCopy();
            withoutId.remove("publicationId");
            assertFalse(wire.matches(wire.schema("PublishResult"), withoutId));

            var masked = (ObjectNode) example(new Route("get", DETAIL), 200, "masked").deepCopy();
            assertFalse(wire.matches(wire.schema("RequestView"), masked.deepCopy().putNull("body")));
            assertFalse(wire.matches(wire.schema("RequestView"), masked.put("actor", "must-not-leak")));
            var history = (ObjectNode) example(new Route("get", HISTORY), 200, "created").deepCopy();
            ((ObjectNode) history.required("changes").get(0)).remove("from");
            assertFalse(wire.matches(wire.schema("RequestHistory"), history));
            var bearerResponse = wire.response("get", DETAIL, 401);
            assertFalse(wire.matches(wire.resolve(bearerResponse.requiredAt("/headers/WWW-Authenticate")).required("schema"),
                    TEXT));
        }
    }

    private static Set<Route> declaredOperations(OpenApiWire contract) {
        var routes = new java.util.HashSet<Route>();
        for (var path : contract.document.required("paths").properties()) {
            for (var element : path.getValue().properties()) {
                if (!element.getKey().equals("parameters")) {
                    assertTrue(METHODS.contains(element.getKey()), "Unexpected HTTP method " + element.getKey());
                    routes.add(new Route(element.getKey(), path.getKey()));
                }
            }
        }
        return routes;
    }

    private static Set<Route> springOperations(OpenApiMvcFixture fixture) {
        var routes = new java.util.HashSet<Route>();
        var mappings = fixture.context().getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
        mappings.forEach((mapping, handler) -> {
            assertTrue(handler.getBeanType().getPackageName().equals("com.company.requests.adapter.http"));
            for (var method : mapping.getMethodsCondition().getMethods()) {
                for (var path : mapping.getPatternValues()) {
                    var restricted = RESTRICTED_ACTION.matcher(path);
                    if (restricted.find()) {
                        for (var action : restricted.group(1).split("\\|")) {
                            routes.add(new Route(method.name().toLowerCase(Locale.ROOT), restricted.replaceFirst(action)));
                        }
                    } else {
                        routes.add(new Route(method.name().toLowerCase(Locale.ROOT), path));
                    }
                }
            }
        });
        return routes;
    }

    private static Stream<RouteCase> routeCases() {
        return Stream.concat(Stream.of(
                new RouteCase(new Route("post", KEYS), KEYS),
                new RouteCase(new Route("post", REQUESTS), REQUESTS),
                new RouteCase(new Route("get", REQUESTS), REQUESTS),
                new RouteCase(new Route("get", DETAIL), "/requests/edit-ref"),
                new RouteCase(new Route("get", CONTEXT), "/requests/edit-ref/action-context"),
                new RouteCase(new Route("patch", EDIT), "/requests/edit-ref/body"),
                new RouteCase(new Route("get", HISTORY), "/requests/edit-ref/history")),
                Stream.of("verify", "accept", "reject", "delete", "publish")
                        .map(action -> new RouteCase(new Route("post", TRANSITION + action),
                                "/requests/edit-ref/actions/" + action)));
    }

    private static Stream<Action> existingActions() {
        return Arrays.stream(Action.values()).filter(action -> action != Action.CREATE);
    }

    private static Stream<BadBody> invalidBodies() {
        var create = new Route("post", REQUESTS);
        var edit = new Route("patch", EDIT);
        return Stream.concat(Stream.of(
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"name\",\"body\":\"body\",\"secret\":\"hint\"}", null),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"name\"}", "body"),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"name\",\"body\":null}", "body"),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":123,\"body\":\"body\"}", "name"),
                new BadBody(create, "{\"type\":\"TICKET\",\"name\":\"name\",\"body\":\"body\"}", "type"),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"\\u0085\\u00a0\",\"body\":\"body\"}", "name"),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"a\\u0000b\",\"body\":\"body\"}", "name"),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"" + "ą".repeat(129) + "\",\"body\":\"body\"}", "name"),
                new BadBody(create, "{\"type\":\"HR_TICKET\",\"name\":\"name\",\"body\":\"x\",\"body\":\"y\"}", "body", false),
                new BadBody(edit, "{\"expectedRevision\":\"9007199254740993\",\"body\":\"text\"}", "expectedRevision"),
                new BadBody(edit, "{\"expectedRevision\":9007199254740993,\"body\":\"text\"}", "expectedRevision"),
                new BadBody(edit, "{\"expectedRevision\":null,\"body\":\"text\"}", "expectedRevision"),
                new BadBody(edit, "{\"expectedRevision\":\"opaque\",\"body\":\"text\",\"name\":\"name\"}", null)),
                Stream.of("verify", "accept", "publish", "delete", "reject").flatMap(action -> {
                    var route = new Route("post", TRANSITION + action);
                    if (action.equals("delete") || action.equals("reject")) {
                        return Stream.of(new BadBody(route, "{\"expectedRevision\":\"opaque\"}", "reason"),
                                new BadBody(route, "{\"expectedRevision\":\"opaque\",\"reason\":null}", "reason"));
                    }
                    return Stream.of(new BadBody(route, "{\"expectedRevision\":\"opaque\",\"reason\":\"unexpected\"}", null));
                }));
    }

    private static Stream<BadQuery> badQuery() {
        var browse = new Route("get", REQUESTS);
        var context = new Route("get", CONTEXT);
        return Stream.of(
                new BadQuery(browse, "/requests?unknown=must-not-leak", null),
                new BadQuery(browse, "/requests?size=1&size=2", null),
                new BadQuery(browse, "/requests?size=51", "size"),
                new BadQuery(browse, "/requests?state=published", "state"),
                new BadQuery(browse, "/requests?cursor=%FF", "cursor"),
                new BadQuery(context, "/requests/edit-ref/action-context", "action"),
                new BadQuery(context, "/requests/edit-ref/action-context?action=create", "action"),
                new BadQuery(context, "/requests/edit-ref/action-context?action=edit&action=verify", null),
                new BadQuery(new Route("get", DETAIL), "/requests/edit-ref?unexpected=1", null));
    }

    private static Stream<String> missingRoutes() {
        return Stream.of("/v1/requests", "/requests/edit-ref/actions/edit", "/requests/edit-ref/actions/Verify",
                "/requests/edit-ref/actions/unknown", "/requests/edit-ref/actions/verify/", "/operation-keys/");
    }

    private static String issue(OpenApiMvcFixture fixture) throws Exception {
        return successExample(execute(fixture, "post", KEYS, FULL_BEARER, null),
                new Route("post", KEYS), 201, "issued", "key").required("key").asString();
    }

    private static MockHttpServletRequestBuilder mutation(Route route, String uri, String key) {
        return base(route.method(), uri, FULL_BEARER).header(KEY, key).contentType("application/json;charset=UTF-8");
    }

    private static MockHttpServletResponse execute(OpenApiMvcFixture fixture, String method, String uri,
                                                   String bearer, String body) throws Exception {
        var request = base(method, uri, bearer);
        if (body != null) {
            request.content(body);
        }
        return fixture.mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder base(String method, String uri, String bearer) {
        var request = request(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)), URI.create(uri));
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return request;
    }

    private static byte[] gzip(byte[] content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(bytes)) {
            output.write(content);
        }
        return bytes.toByteArray();
    }

    private static JsonNode requestExample(Route route) {
        return wire.requestMedia(route.method(), route.path()).requiredAt("/examples/input/value");
    }

    private static byte[] observedInput(Route route, String observation) {
        var actual = ((ObjectNode) requestExample(route).deepCopy()).put("expectedRevision", observation);
        assertTrue(wire.matches(wire.requestMedia(route.method(), route.path()).required("schema"), actual));
        return JSON.writeValueAsBytes(actual);
    }

    private static JsonNode example(Route route, int status, String label) {
        return wire.response(route.method(), route.path(), status).required("content").required(APPLICATION_JSON)
                .required("examples").required(label).required("value");
    }

    private static JsonNode successExample(MockHttpServletResponse response, Route route, int status, String label,
                                           String... generated) throws Exception {
        var body = successful(response, wire.response(route.method(), route.path(), status), status, APPLICATION_JSON);
        var expected = example(route, status, label);
        var normalized = (ObjectNode) body.deepCopy();
        for (String field : generated) {
            assertNotEquals(expected.required(field), body.required(field), "Fixture placeholder must be replaced");
            normalized.set(field, expected.required(field));
        }
        assertEquals(expected, normalized);
        return body;
    }

    private static JsonNode successful(MockHttpServletResponse response, JsonNode declared, int status, String media)
            throws Exception {
        assertEquals(status, response.getStatus(), () -> new String(response.getContentAsByteArray(), UTF_8));
        var contentType = MediaType.parseMediaType(response.getContentType());
        assertEquals(media, contentType.getType() + "/" + contentType.getSubtype());
        assertEquals(UTF_8.name(), response.getCharacterEncoding());
        requireHeader(declared, response, "Cache-Control", "no-store");
        var body = JSON.readTree(response.getContentAsByteArray());
        assertTrue(wire.matches(declared.required("content").required(media).required("schema"), body),
                () -> "Unexpected " + status + " response shape: " + body);
        return body;
    }

    private static void problem(MockHttpServletResponse response, JsonNode declared, int status, String code,
                                String field) throws Exception {
        var body = successful(response, declared, status, PROBLEM);
        assertEquals("urn:requests:problem:" + code, body.required("type").asString());
        assertEquals("urn:uuid:" + body.required("correlationId").asString(), body.required("instance").asString());
        if (field != null) {
            assertEquals(List.of(field), body.required("invalidFields").valueStream().map(JsonNode::asString).toList());
        } else {
            assertFalse(body.has("invalidFields"));
        }
        assertNull(response.getHeader("Location"));
    }

    private static void requireHeader(JsonNode declared, MockHttpServletResponse response, String name, String value) {
        assertEquals(value, response.getHeader(name));
        assertTrue(wire.matches(wire.resolve(declared.required("headers").required(name)).required("schema"),
                JSON.getNodeFactory().stringNode(value)));
    }

    private static void assertParameter(JsonNode parameter, String name, String in, boolean required) {
        assertEquals(name, parameter.required("name").asString());
        assertEquals(in, parameter.required("in").asString());
        assertEquals(required, parameter.required("required").asBoolean());
    }

    private record Route(String method, String path) { }
    private record RouteCase(Route route, String uri) { }
    private record BadBody(Route route, String body, String field, boolean schemaCanRepresent) {
        BadBody(Route route, String body, String field) {
            this(route, body, field, true);
        }
    }
    private record BadQuery(Route route, String uri, String field) { }
}
