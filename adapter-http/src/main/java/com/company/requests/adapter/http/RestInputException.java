package com.company.requests.adapter.http;

import com.company.requests.adapter.http.RestProblems.Code;

final class RestInputException extends RuntimeException {
    enum Field {
        TYPE("type"), NAME("name"), BODY("body"), REASON("reason"), EXPECTED_REVISION("expectedRevision"),
        KEY("Idempotency-Key"), REF("ref"), ACTION("action"), STATE("state"), SIZE("size"), CURSOR("cursor");

        private final String wireName;

        Field(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    private final Code code;
    private final Field field;
    private final boolean unsupportedEncoding;

    RestInputException(Code code, Field field, boolean unsupportedEncoding) {
        super(code.name());
        this.code = code;
        this.field = field;
        this.unsupportedEncoding = unsupportedEncoding;
    }

    Code code() {
        return code;
    }

    Field field() {
        return field;
    }

    boolean unsupportedEncoding() {
        return unsupportedEncoding;
    }
}
