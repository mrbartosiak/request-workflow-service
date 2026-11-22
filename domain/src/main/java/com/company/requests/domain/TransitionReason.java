package com.company.requests.domain;

/** Protected reason payload, stored separately from the transition fact. */
public record TransitionReason(String value) {
    public TransitionReason {
        RequiredText.check(value, "reason");
    }

    @Override
    public String toString() {
        return "TransitionReason[protected]";
    }
}
