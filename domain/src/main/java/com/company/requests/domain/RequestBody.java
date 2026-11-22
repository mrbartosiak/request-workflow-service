package com.company.requests.domain;

/** Protected body payload, stored separately from request metadata. */
public record RequestBody(String value) {
    public RequestBody {
        RequiredText.check(value, "body");
    }

    @Override
    public String toString() {
        return "RequestBody[protected]";
    }
}
