package com.company.requests.adapter.http;

import java.util.Optional;

/** Validated edge inputs. Strings retain their original decoded characters. */
public final class RestInputs {
    private RestInputs() {
    }

    public record Create(String key, String type, String name, String body) {
    }

    public record Edit(String key, String ref, String body, String expectedRevision) {
    }

    public record ActionCommand(String key, String ref, String action, String expectedRevision,
                                Optional<String> reason) {
    }

    public record ActionContext(String ref, String action) {
    }

    public record Browse(Optional<String> name, Optional<String> state, int size, Optional<String> cursor) {
    }
}
