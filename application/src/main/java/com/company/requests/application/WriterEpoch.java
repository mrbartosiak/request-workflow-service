package com.company.requests.application;

import java.util.Objects;
import java.util.UUID;

/** Independently certified command deployment and positive generation. */
public record WriterEpoch(UUID writerId, long generation) {
    public WriterEpoch {
        Objects.requireNonNull(writerId, "writer ID");
        if (generation < 1) {
            throw new IllegalArgumentException("Writer generation must be positive.");
        }
    }
}
