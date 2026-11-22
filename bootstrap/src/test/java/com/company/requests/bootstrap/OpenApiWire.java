package com.company.requests.bootstrap;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the JSON-compatible YAML document and evaluates its wire-schema vocabulary for fixtures. */
final class OpenApiWire {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Set<String> KEYWORDS = Set.of("$ref", "description", "default", "type", "const", "enum",
            "allOf", "oneOf", "not", "if", "then", "else", "properties", "required", "additionalProperties", "items",
            "minItems", "maxItems", "uniqueItems", "minimum", "maximum", "minLength", "pattern", "format",
            "x-utf8MaxBytes", "x-decimalMaximum");
    private static final Set<String> PATH_METHODS = Set.of("get", "post", "patch");
    private static final String BEARER_AUTH = "BearerAuth";
    private static final Pattern COMPONENT_REFERENCE = Pattern.compile(
            "#/components/(schemas|parameters|headers|responses)/[A-Za-z0-9._-]+");

    final JsonNode document;

    OpenApiWire() throws IOException {
        this(JSON.readTree(Path.of(System.getProperty("requests.reactorRoot"), "adapter-http", "src", "main", "openapi",
                "requests.yaml").toFile()));
    }

    OpenApiWire(JsonNode document) {
        this.document = document;
    }

    JsonNode resolve(JsonNode node) {
        if (!node.has("$ref")) {
            return node;
        }
        String pointer = requiredText(node, "$ref");
        if (!COMPONENT_REFERENCE.matcher(pointer).matches()) {
            throw new IllegalArgumentException("Expected a local OpenAPI component reference: " + pointer);
        }
        var target = document.at(pointer.substring(1));
        if (!target.isObject()) {
            throw new IllegalArgumentException("Missing OpenAPI component: " + pointer);
        }
        return target;
    }

    JsonNode operation(String method, String path) {
        return document.required("paths").required(path).required(method);
    }

    JsonNode requestMedia(String method, String path) {
        return operation(method, path).required("requestBody").required("content").required("application/json");
    }

    JsonNode response(String method, String path, int status) {
        return resolve(operation(method, path).required("responses").required(Integer.toString(status)));
    }

    JsonNode routingResponse(int status) {
        return resolve(document.required("x-routing-problems").required(Integer.toString(status)));
    }

    JsonNode schema(String name) {
        return document.required("components").required("schemas").required(name);
    }

    void verifyDocument() {
        if (!requiredText(document, "openapi").equals("3.1.0")) {
            throw new IllegalArgumentException("Expected the approved OpenAPI 3.1.0 document");
        }
        var info = requiredObject(document, "info");
        requiredText(info, "title");
        requiredText(info, "version");
        var components = requiredObject(document, "components");
        var bearer = requiredObject(requiredObject(components, "securitySchemes"), BEARER_AUTH);
        if (!"http".equals(bearer.path("type").asString("")) || !"bearer".equals(bearer.path("scheme").asString(""))) {
            throw new IllegalArgumentException("Expected BearerAuth with type http and scheme bearer");
        }
        var inheritedSecurity = document.path("security");
        var paths = requiredObject(document, "paths");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Expected the approved OpenAPI paths");
        }
        for (var path : paths.propertyNames()) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Expected an absolute OpenAPI path: " + path);
            }
            var item = requiredObject(paths, path);
            for (var method : item.propertyNames()) {
                if (PATH_METHODS.contains(method)) {
                    var operation = requiredObject(item, method);
                    var security = operation.has("security") ? operation.path("security") : inheritedSecurity;
                    verifyBearerRequirement(security, method, path);
                    verifyResponses(requiredObject(operation, "responses"));
                    if (operation.has("requestBody")) {
                        verifyContent(requiredObject(operation, "requestBody"));
                    }
                }
            }
        }
        verifyResponses(requiredObject(components, "responses"));
        verifyReferences(document);
    }

    private static void verifyBearerRequirement(JsonNode security, String method, String path) {
        if (security.isArray() && !security.isEmpty() && security.valueStream().allMatch(requirement -> {
            var scopes = requirement.path(BEARER_AUTH);
            return requirement.isObject() && requirement.size() == 1 && scopes.isArray() && scopes.isEmpty();
        })) {
            return;
        }
        throw new IllegalArgumentException("Expected required BearerAuth for " + method + " " + path);
    }

    private void verifyResponses(JsonNode responses) {
        if (responses.isEmpty()) {
            throw new IllegalArgumentException("Expected at least one OpenAPI response");
        }
        for (var declared : responses) {
            var response = resolve(declared);
            requiredText(response, "description");
            verifyContent(response);
        }
    }

    private static void verifyContent(JsonNode container) {
        var content = requiredObject(container, "content");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Expected an OpenAPI media type");
        }
        for (var media : content) {
            requiredObject(media, "schema");
        }
    }

    private static JsonNode requiredObject(JsonNode container, String key) {
        var node = container.path(key);
        if (!node.isObject()) {
            throw new IllegalArgumentException("Expected OpenAPI object: " + key);
        }
        return node;
    }

    private static String requiredText(JsonNode container, String key) {
        var node = container.path(key);
        if (!node.isString() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Expected nonblank OpenAPI text: " + key);
        }
        return node.asString();
    }

    private void verifyReferences(JsonNode node) {
        if (node.has("$ref")) {
            resolve(node);
        }
        node.forEach(this::verifyReferences);
    }

    boolean matches(JsonNode declared, JsonNode value) {
        if (declared.isBoolean()) {
            return declared.asBoolean();
        }
        var schema = resolve(declared);
        if (!schema.isObject() || !KEYWORDS.containsAll(schema.propertyNames())) {
            throw new IllegalArgumentException("Unsupported fixture schema: " + schema);
        }
        if (schema.has("type") && !matchesType(schema.required("type").asString(), value)) {
            return false;
        }
        if (schema.has("const") && !schema.required("const").equals(value)) {
            return false;
        }
        if (schema.has("enum") && schema.required("enum").valueStream().noneMatch(value::equals)) {
            return false;
        }
        if (schema.path("allOf").valueStream().anyMatch(branch -> !matches(branch, value))) {
            return false;
        }
        if (schema.has("oneOf") && schema.required("oneOf").valueStream().filter(branch -> matches(branch, value)).count() != 1) {
            return false;
        }
        if (schema.has("not") && matches(schema.required("not"), value)) {
            return false;
        }
        if (schema.has("if")) {
            String branch = matches(schema.required("if"), value) ? "then" : "else";
            if (schema.has(branch) && !matches(schema.required(branch), value)) {
                return false;
            }
        }
        if (value.isObject()) {
            return matchesObject(schema, value);
        }
        if (value.isArray()) {
            return matchesArray(schema, value);
        }
        if (value.isString()) {
            return matchesString(schema, value.asString());
        }
        if (value.isIntegralNumber()) {
            var integer = value.bigIntegerValue();
            return (!schema.has("minimum") || integer.compareTo(schema.required("minimum").bigIntegerValue()) >= 0)
                    && (!schema.has("maximum") || integer.compareTo(schema.required("maximum").bigIntegerValue()) <= 0);
        }
        return true;
    }

    private boolean matchesObject(JsonNode schema, JsonNode value) {
        for (var field : schema.path("required")) {
            if (!value.has(field.asString())) {
                return false;
            }
        }
        var properties = schema.path("properties");
        for (var member : value.properties()) {
            var property = properties.get(member.getKey());
            if (property == null) {
                property = schema.get("additionalProperties");
            }
            if (property != null && !matches(property, member.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesArray(JsonNode schema, JsonNode value) {
        if (value.size() < schema.path("minItems").asInt(0)
                || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE)) {
            return false;
        }
        var seen = new HashSet<JsonNode>();
        for (var item : value) {
            if (schema.path("uniqueItems").asBoolean(false) && !seen.add(item)) {
                return false;
            }
            if (schema.has("items") && !matches(schema.required("items"), item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesString(JsonNode schema, String value) {
        if (schema.has("pattern") && !Pattern.compile(schema.required("pattern").asString()).matcher(value).find()) {
            return false;
        }
        if (value.codePointCount(0, value.length()) < schema.path("minLength").asInt(0)) {
            return false;
        }
        if (schema.has("x-utf8MaxBytes") && (!StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.indexOf(0) >= 0 || value.getBytes(StandardCharsets.UTF_8).length > schema.required("x-utf8MaxBytes").asInt()
                || value.codePoints().allMatch(OpenApiWire::whiteSpace))) {
            return false;
        }
        if (schema.has("x-decimalMaximum")
                && new BigInteger(value).compareTo(new BigInteger(schema.required("x-decimalMaximum").asString())) > 0) {
            return false;
        }
        return matchesFormat(schema.path("format").asString(""), value);
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> throw new IllegalArgumentException("Unsupported fixture type: " + type);
        };
    }

    private static boolean matchesFormat(String format, String value) {
        try {
            return switch (format) {
                case "" -> true;
                case "date-time" -> OffsetDateTime.parse(value) != null;
                case "uuid" -> UUID.fromString(value).toString().equals(value);
                case "uri" -> URI.create(value).isAbsolute();
                case "uri-reference" -> URI.create(value) != null;
                default -> throw new IllegalStateException("Unsupported fixture format: " + format);
            };
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
            return false;
        }
    }

    private static boolean whiteSpace(int codePoint) {
        // Unicode White_Space is broader than Java isWhitespace and is not identical to isSpaceChar.
        return codePoint >= 0x09 && codePoint <= 0x0d || codePoint == 0x85 || Character.isSpaceChar(codePoint);
    }
}
