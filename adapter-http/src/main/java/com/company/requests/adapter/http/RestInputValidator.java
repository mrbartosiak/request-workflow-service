package com.company.requests.adapter.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

import com.company.requests.adapter.http.RestInputException.Field;
import com.company.requests.adapter.http.RestProblems.Code;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;

import static com.company.requests.adapter.http.RestInputException.Field.*;

/**
 * Controller entry checks. Call after security authentication and before passing a command
 * to application code. Signature checks, authorization and command decisions are downstream.
 */
public final class RestInputValidator {
    static final String KEY_HEADER = "Idempotency-Key";
    static final String SUPPORTED_ENCODINGS = "gzip, identity";
    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_NAME_BYTES = 256;
    private static final int MAX_BODY_BYTES = 16_384;
    private static final int MAX_REASON_BYTES = 2_048;
    private static final int MAX_KEY_BYTES = 1_024;
    private static final int MAX_TOKEN_BYTES = 2_048;
    private static final int MAX_REF_BYTES = 128;
    private static final int MAX_CURSOR_BYTES = 2_048;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private static final Set<String> TYPES = Set.of("HR_TICKET", "BUG_REPORT", "INTERNAL_REQUEST");
    private static final Set<String> STATES = Set.of("CREATED", "VERIFIED", "ACCEPTED",
            "REJECTED", "DELETED", "PUBLISHED");
    private static final Set<String> TRANSITIONS = Set.of("verify", "accept", "reject", "delete", "publish");
    private static final Set<String> CONTEXT_ACTIONS = Set.of("edit", "verify", "accept", "reject", "delete",
            "publish");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");

    private final JsonMapper json = new JsonMapper();

    public RestInputs.Create create(HttpServletRequest request) {
        noQuery(request);
        var key = key(request);
        var fields = object(request, TYPE, NAME, BODY);
        return new RestInputs.Create(key, choice(fields.get(TYPE), TYPES, TYPE),
                text(fields.get(NAME), MAX_NAME_BYTES, NAME), text(fields.get(BODY), MAX_BODY_BYTES, BODY));
    }

    public RestInputs.Edit edit(HttpServletRequest request, String ref) {
        noQuery(request);
        var key = key(request);
        var target = ref(request, ref);
        var fields = object(request, BODY, EXPECTED_REVISION);
        return new RestInputs.Edit(key, target, text(fields.get(BODY), MAX_BODY_BYTES, BODY),
                revision(fields.get(EXPECTED_REVISION)));
    }

    public RestInputs.ActionCommand action(HttpServletRequest request, String ref, String action) {
        noQuery(request);
        var key = key(request);
        var target = ref(request, ref);
        var command = choice(action, TRANSITIONS, ACTION);
        var requiresReason = command.equals("reject") || command.equals("delete");
        var fields = requiresReason ? object(request, EXPECTED_REVISION, REASON)
                : object(request, EXPECTED_REVISION);
        var reason = requiresReason ? Optional.of(text(fields.get(REASON), MAX_REASON_BYTES, REASON))
                : Optional.<String>empty();
        return new RestInputs.ActionCommand(key, target, command, revision(fields.get(EXPECTED_REVISION)), reason);
    }

    public RestInputs.ActionContext actionContext(HttpServletRequest request, String ref) {
        var query = query(request, Set.of(ACTION.wireName()));
        noBody(request);
        return new RestInputs.ActionContext(ref(request, ref),
                choice(query.get(ACTION.wireName()), CONTEXT_ACTIONS, ACTION));
    }

    public RestInputs.Browse browse(HttpServletRequest request) {
        var query = query(request, Set.of(NAME.wireName(), STATE.wireName(), SIZE.wireName(), CURSOR.wireName()));
        noBody(request);
        return new RestInputs.Browse(
                Optional.ofNullable(query.get(NAME.wireName())).map(value -> text(value, MAX_NAME_BYTES, NAME)),
                Optional.ofNullable(query.get(STATE.wireName())).map(value -> choice(value, STATES, STATE)),
                pageSize(query.get(SIZE.wireName())),
                Optional.ofNullable(query.get(CURSOR.wireName())).map(RestInputValidator::cursor));
    }

    /** Bodyless, parameterless input shared by Detail and History. */
    public String reference(HttpServletRequest request, String ref) {
        noQuery(request);
        noBody(request);
        return ref(request, ref);
    }

    public void keyIssuance(HttpServletRequest request) {
        noQuery(request);
        noBody(request);
    }

    private static String key(HttpServletRequest request) {
        var headers = request.getHeaders(KEY_HEADER);
        if (!headers.hasMoreElements()) {
            throw invalid(KEY);
        }
        var value = headers.nextElement();
        // Also reject a list coalesced into one field before it reaches the servlet.
        if (headers.hasMoreElements() || value.indexOf(',') >= 0) {
            throw invalid(KEY);
        }
        return text(value, MAX_KEY_BYTES, KEY);
    }

    private static String ref(HttpServletRequest request, String ref) {
        // A servlet path decoder may substitute for malformed percent-encoded UTF-8.
        decodeUriComponent(request.getRequestURI(), false);
        return text(ref, MAX_REF_BYTES, REF);
    }

    private static String revision(String value) {
        var token = text(value, MAX_TOKEN_BYTES, EXPECTED_REVISION);
        if (DECIMAL.matcher(token).matches()) {
            throw invalid(EXPECTED_REVISION);
        }
        return token;
    }

    private static int pageSize(String value) {
        if (value == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (!DECIMAL.matcher(value).matches()) {
            throw invalid(SIZE);
        }
        try {
            int size = Integer.parseInt(value);
            if (size < 1 || size > MAX_PAGE_SIZE) {
                throw invalid(SIZE);
            }
            return size;
        } catch (NumberFormatException ex) {
            throw invalid(SIZE);
        }
    }

    private static String cursor(String value) {
        if (!value.chars().allMatch(character -> character <= 0x7f)) {
            throw invalid(CURSOR);
        }
        return text(value, MAX_CURSOR_BYTES, CURSOR);
    }

    private Map<Field, String> object(HttpServletRequest request, Field... expected) {
        requireJson(request);
        var fields = new EnumMap<Field, String>(Field.class);
        var names = new HashMap<String, Field>();
        for (var field : expected) {
            names.put(field.wireName(), field);
        }
        try (var parser = json.createParser(readBody(request))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid(null);
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                var field = names.get(parser.currentName());
                if (field == null || fields.containsKey(field)) {
                    throw invalid(field);
                }
                if (parser.nextToken() != JsonToken.VALUE_STRING) {
                    throw invalid(field);
                }
                fields.put(field, parser.getString());
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                throw invalid(null);
            }
        } catch (JacksonException ex) {
            throw invalid(null);
        }
        for (var field : expected) {
            if (!fields.containsKey(field)) {
                throw invalid(field);
            }
        }
        return fields;
    }

    private static void requireJson(HttpServletRequest request) {
        var headers = request.getHeaders(HttpHeaders.CONTENT_TYPE);
        if (headers != null && headers.hasMoreElements()) {
            var header = headers.nextElement();
            if (!headers.hasMoreElements() && isJsonUtf8(header)) {
                return;
            }
        }
        throw new RestInputException(Code.UNSUPPORTED_MEDIA, null, false);
    }

    private static boolean isJsonUtf8(String header) {
        try {
            var type = MediaType.parseMediaType(header);
            var charset = type.getCharset();
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)
                    && (charset == null || StandardCharsets.UTF_8.equals(charset));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String readBody(HttpServletRequest request) {
        boolean gzip = gzipEncoding(request);
        try (var input = request.getInputStream()) {
            return readUtf8(gzip ? readGzip(input) : input.readNBytes(MAX_REQUEST_BYTES + 1));
        } catch (IOException ex) {
            throw transportInput(ex);
        }
    }

    private static byte[] readGzip(InputStream input) {
        var source = new GzipSource(input);
        try (var gzip = new GZIPInputStream(source)) {
            byte[] decoded = gzip.readNBytes(MAX_REQUEST_BYTES + 1);
            source.checkReadFailure();
            return decoded;
        } catch (IOException ex) {
            source.checkReadFailure();
            throw invalid(null);
        }
    }

    private static String readUtf8(byte[] bytes) {
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new RestInputException(Code.REQUEST_TOO_LARGE, null, false);
        }
        try {
            return utf8(bytes);
        } catch (CharacterCodingException ex) {
            throw invalid(null);
        }
    }

    private static boolean gzipEncoding(HttpServletRequest request) {
        var encodings = Collections.list(request.getHeaders(HttpHeaders.CONTENT_ENCODING));
        if (encodings.isEmpty() || (encodings.size() == 1 && encodings.getFirst().equalsIgnoreCase("identity"))) {
            return false;
        }
        if (encodings.size() == 1 && encodings.getFirst().equalsIgnoreCase("gzip")) {
            return true;
        }
        throw new RestInputException(Code.UNSUPPORTED_MEDIA, null, true);
    }

    private static void noBody(HttpServletRequest request) {
        try {
            if (request.getInputStream().read() != -1) {
                throw invalid(null);
            }
        } catch (IOException ex) {
            throw transportInput(ex);
        }
    }

    private static void noQuery(HttpServletRequest request) {
        var source = request.getQueryString();
        if (source != null && !source.isEmpty()) {
            throw invalid(null);
        }
    }

    private static Map<String, String> query(HttpServletRequest request, Set<String> allowed) {
        var source = request.getQueryString();
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var values = new HashMap<String, String>();
        for (var parameter : source.split("&", allowed.size() + 1)) {
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                throw invalid(null);
            }
            var name = decodeUriComponent(parameter.substring(0, equals), true);
            if (!allowed.contains(name) || values.containsKey(name)) {
                throw invalid(null);
            }
            values.put(name, decodeUriComponent(parameter.substring(equals + 1), true));
        }
        return values;
    }

    private static String decodeUriComponent(String value, boolean query) {
        var decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (character != '%') {
                decoded.append(query && character == '+' ? ' ' : character);
                index++;
                continue;
            }
            var bytes = new ByteArrayOutputStream();
            do {
                if (index + 2 >= value.length()
                        || !HexFormat.isHexDigit(value.charAt(index + 1))
                        || !HexFormat.isHexDigit(value.charAt(index + 2))) {
                    throw invalid(null);
                }
                bytes.write(HexFormat.fromHexDigit(value.charAt(index + 1)) * 16
                        + HexFormat.fromHexDigit(value.charAt(index + 2)));
                index += 3;
            } while (index < value.length() && value.charAt(index) == '%');
            try {
                decoded.append(utf8(bytes.toByteArray()));
            } catch (CharacterCodingException ex) {
                throw invalid(null);
            }
        }
        return decoded.toString();
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String text(String value, int maxBytes, Field field) {
        if (value.isEmpty() || value.indexOf(0) >= 0 || value.codePoints().allMatch(RestInputValidator::whiteSpace)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid(field);
        }
        return value;
    }

    // Unicode White_Space, which differs from Character.isWhitespace for several code points.
    private static boolean whiteSpace(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0d)
                || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || switch (codePoint) {
                    case 0x20, 0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 -> true;
                    default -> false;
                };
    }

    private static String choice(String value, Set<String> allowed, Field field) {
        if (value == null || !allowed.contains(value)) {
            throw invalid(field);
        }
        return value;
    }

    private static RestInputException invalid(Field field) {
        return new RestInputException(Code.INVALID_INPUT, field, false);
    }

    private static RestInputException transportInput(IOException failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return new RestInputException(Code.INVALID_INPUT, null, false, Outcome.TRANSPORT_TIMEOUT);
            }
        }
        return new RestInputException(Code.INVALID_INPUT, null, false, Outcome.TRANSPORT_FAILURE);
    }

    /** Tracks failures even when gzip member lookahead consumes an IOException. The caller owns closing the stream. */
    private static final class GzipSource extends InputStream {
        private final InputStream transport;
        private IOException readFailure;

        private GzipSource(InputStream transport) {
            this.transport = transport;
        }

        @Override
        public int read() throws IOException {
            try {
                return transport.read();
            } catch (IOException failure) {
                readFailure = failure;
                throw failure;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                return transport.read(buffer, offset, length);
            } catch (IOException failure) {
                readFailure = failure;
                throw failure;
            }
        }

        private void checkReadFailure() {
            if (readFailure != null) {
                throw transportInput(readFailure);
            }
        }
    }
}
