package com.company.requests.adapter.http;

import com.company.requests.adapter.http.RestProblems.Code;
import com.company.requests.application.evidence.RequestEvidenceEvent.Outcome;

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
    private final Outcome outcome;

    RestInputException(Code code, Field field, boolean unsupportedEncoding) {
        this(code, field, unsupportedEncoding, code.outcome());
    }

    RestInputException(Code code, Field field, boolean unsupportedEncoding, Outcome outcome) {
        super(code.name());
        this.code = code;
        this.field = field;
        this.unsupportedEncoding = unsupportedEncoding;
        this.outcome = outcome;
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

    Outcome outcome() {
        return outcome;
    }
}
