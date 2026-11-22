package com.company.requests.adapter.http;

import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HttpIntentDigestTest {
    private static final String REF = "ref-a";
    private final RestInputValidator inputs = new RestInputValidator();
    private final IntentDigest digests = new IntentDigest(
            new OperationSecrets(1, Map.of(1, secret((byte) 41)), 1, Map.of(1, secret((byte) 42))));

    @Test
    void equivalentCreateMemberOrderAndEscapesPreserveOnlyDecodedInputs() {
        var first = create("""
                {"type":"HR_TICKET","name":"café","body":" text\\r\\n"}
                """, "fixture-key-one");
        var reordered = create("""
                {"body":" text\\r\\n","name":"caf\\u00e9","type":"HR_TICKET"}
                """, "fixture-key-two");
        var changed = create("""
                {"type":"HR_TICKET","name":"cafe\\u0301","body":" text\\r\\n"}
                """, "fixture-key-one");
        assertEquals(first, reordered);
        assertNotEquals(first, changed);
    }

    @Test
    void editAndTransitionPreserveExactObservationAndReasonAfterJsonDecoding() {
        var edit = edit("""
                {"body":"edit","expectedRevision":"tok.en/a"}
                """);
        assertEquals(edit, edit("""
                {"expectedRevision":"tok.en\\/a","body":"edit"}
                """));
        assertNotEquals(edit, edit("""
                {"expectedRevision":"tok.en/a ","body":"edit"}
                """));

        var rejection = reject("""
                {"reason":"prévu","expectedRevision":"tok.en/a"}
                """);
        assertEquals(rejection, reject("""
                {"expectedRevision":"tok.en/a","reason":"pr\\u00e9vu"}
                """));
        assertNotEquals(rejection, reject("""
                {"expectedRevision":"tok.en/a","reason":"prévu "}
                """));
    }

    private ReceiptIntentDigest create(String json, String key) {
        var input = inputs.create(request("POST", "/requests", key, json));
        return digests.calculate(new IntentDigest.Create(RequestType.valueOf(input.type()), input.name(), input.body()));
    }

    private ReceiptIntentDigest edit(String json) {
        var input = inputs.edit(request("PATCH", "/requests/" + REF + "/body", "fixture-key", json), REF);
        return digests.calculate(new IntentDigest.Edit(new RequestRef(input.ref()), input.body(), input.expectedRevision()));
    }

    private ReceiptIntentDigest reject(String json) {
        var input = inputs.action(request("POST", "/requests/" + REF + "/actions/reject", "fixture-key", json),
                REF, "reject");
        return digests.calculate(new IntentDigest.Transition(Action.valueOf(input.action().toUpperCase(Locale.ROOT)),
                new RequestRef(input.ref()), input.reason(), input.expectedRevision()));
    }

    private static MockHttpServletRequest request(String method, String path, String key, String json) {
        var request = new MockHttpServletRequest(method, path);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader(RestInputValidator.KEY_HEADER, key);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static byte[] secret(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
