package com.company.requests.integration;

import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable local dispatch transitions. Each operation owns and closes its transaction. */
public interface DispatchStore {
    Optional<Claim> claim(WriterEpoch writer);

    ArmResult arm(Claim claim);

    /** Record a scoped synthetic receipt or hold the attempt as unknown. */
    boolean finish(Claim claim, Optional<Receipt> receipt);

    record EffectKey(String destination, OrganizationId tenant, String effectId) {
        public EffectKey {
            Objects.requireNonNull(tenant);
            if (destination == null || !destination.matches("[a-z][a-z0-9-]{0,63}")
                    || effectId == null || !effectId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid scoped dispatch effect key.");
            }
        }

        @Override
        public String toString() {
            return "DispatchEffectKey[protected]";
        }
    }

    record AttemptId(EffectKey effect, long number) {
        public AttemptId {
            Objects.requireNonNull(effect);
            if (number < 1) {
                throw new IllegalArgumentException("Dispatch attempt number must be positive.");
            }
        }
    }

    record Claim(AttemptId id, UUID token, WriterEpoch writer) {
        public Claim {
            Objects.requireNonNull(id);
            Objects.requireNonNull(token);
            Objects.requireNonNull(writer);
        }

        @Override
        public String toString() {
            return "DispatchClaim[protected]";
        }
    }

    enum ArmState { ARMED, DEFERRED, SUPPRESSED, STALE }

    record ArmResult(ArmState state, Optional<Invocation> invocation) {
        public ArmResult {
            Objects.requireNonNull(state);
            Objects.requireNonNull(invocation);
            if ((state == ArmState.ARMED) != invocation.isPresent()) {
                throw new IllegalArgumentException("Only a committed arm authorizes a dispatch invocation.");
            }
        }
    }

    /** The synthetic destination sees only the approved reference and scoped attempt. */
    record Invocation(AttemptId id, RequestRef ref, long revision) {
        public Invocation {
            Objects.requireNonNull(id);
            Objects.requireNonNull(ref);
            if (revision < 2) {
                throw new IllegalArgumentException("Invalid outbound result revision.");
            }
        }

        @Override
        public String toString() {
            return "DispatchInvocation[protected]";
        }
    }

    record Receipt(AttemptId id, UUID proofId) {
        public Receipt {
            Objects.requireNonNull(id);
            Objects.requireNonNull(proofId);
        }

        @Override
        public String toString() {
            return "DispatchReceipt[protected]";
        }
    }
}
