package com.company.requests.domain;

import java.util.Objects;
import java.util.regex.Pattern;

final class RequiredText {
    private static final Pattern BLANK = Pattern.compile("\\p{IsWhite_Space}*");

    private RequiredText() {}

    static void check(String value, String field) {
        Objects.requireNonNull(value, field);
        if (BLANK.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be nonblank.");
        }
    }
}
