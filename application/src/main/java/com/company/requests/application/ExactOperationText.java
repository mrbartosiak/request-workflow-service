package com.company.requests.application;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

final class ExactOperationText {
    private static final Pattern BLANK = Pattern.compile("\\p{IsWhite_Space}*");

    private ExactOperationText() {
    }

    static byte[] encode(String value, int maxBytes) {
        if (value == null || value.length() > maxBytes || value.indexOf(0) >= 0
                || BLANK.matcher(value).matches() || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw invalid();
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw invalid();
        }
        return bytes;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid exact operation text.");
    }
}
