package com.company.requests.application;

import com.company.requests.application.OperationKeyIssuer.Eligibility;
import com.company.requests.application.OperationKeyIssuer.VerifiedKey;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/** Shared proof, current disclosure and bounded handoff for committed command receipts. */
final class ReceiptReplay {
    private static final long BUDGET_NANOS = Duration.ofMillis(2400).toNanos();
    private static final byte CONFLICT = 0;
    private static final byte SUCCESS = 1;

    private final ReceiptStore receipts;
    private final CurrentAccess access;
    private final OperationKeyIssuer keys;
    private final IntentDigest digests;
    private final ReleasePreparation preparation;
    private final GuardedRelease release;
    private final OriginalReceiptProof originalReceipts;

    ReceiptReplay(ReceiptStore receipts, CurrentAccess access, OperationKeyIssuer keys, IntentDigest digests,
                  ReleasePreparation preparation, GuardedRelease release, OriginalReceiptProof originalReceipts) {
        this.receipts = receipts;
        this.access = access;
        this.keys = keys;
        this.digests = digests;
        this.preparation = preparation;
        this.release = release;
        this.originalReceipts = originalReceipts;
    }

    sealed interface Result permits Rejection, Released { }

    enum Rejection implements Result { CONCEALED, CONFLICT, EXPIRED, FENCED, ILLEGAL_STATE, OUTCOME_UNKNOWN }

    record Released(byte[] envelope) implements Result { }

    Result resolve(AuthenticatedActor actor, VerifiedKey key, IntentDigest.Intent intent,
                   Function<PolicyGuard, Admission> authorize, Function<OperationReceipt, byte[]> encode,
                   ReleaseBuffer buffer, long started) {
        return resolve(actor, key, intent, authorize, encode, buffer, started, Rejection.OUTCOME_UNKNOWN);
    }

    Result resolve(AuthenticatedActor actor, VerifiedKey key, IntentDigest.Intent intent,
                   Function<PolicyGuard, Admission> authorize, Function<OperationReceipt, byte[]> encode,
                   ReleaseBuffer buffer, long started, Rejection missingActiveReceipt) {
        boolean past = key.eligibility() == Eligibility.PAST_GENERATION_LOOKUP_ONLY;
        var foundTrusted = new boolean[1];
        var publicationUnproved = new boolean[1];
        var outcome = release.handoff(actor.organization(), authorize, () -> {
            if (!keys.isFresh(key)) {
                return false;
            }
            requireBudget(started);
            return true;
        }, current -> {
            var found = find(current.admission(), key, past)
                    .filter(receipt -> access.usesEffectiveScope() || receipt.action() == Action.CREATE);
            foundTrusted[0] = found.isPresent();
            return found.filter(receipt -> release.permitsReceipt(actor, current.guard(), receipt))
                    .filter(receipt -> publicationProved(current, receipt, publicationUnproved))
                    .map(receipt -> new GuardedRelease.Prepared(payload(current.admission(), receipt, intent, encode),
                            checked -> release.permitsReceipt(actor, checked.guard(), receipt)
                                    && publicationProved(checked, receipt, publicationUnproved)));
        }, buffer);
        return switch (outcome) {
            case HANDED_OFF -> {
                byte[] bytes = buffer.take().orElseThrow();
                yield bytes[0] == CONFLICT ? Rejection.CONFLICT
                        : new Released(Arrays.copyOfRange(bytes, 1, bytes.length));
            }
            case FRESHNESS_LOST -> Rejection.EXPIRED;
            case WITHHELD -> past ? Rejection.FENCED
                    : publicationUnproved[0] ? Rejection.OUTCOME_UNKNOWN
                    : foundTrusted[0] ? Rejection.CONCEALED : missingActiveReceipt;
            case BUFFER_UNAVAILABLE, BUDGET_EXCEEDED -> Rejection.OUTCOME_UNKNOWN;
        };
    }

    private boolean publicationProved(GuardedRelease.Context current, OperationReceipt receipt, boolean[] unproved) {
        if (receipt.action() != Action.PUBLISH) {
            return true;
        }
        unproved[0] = !access.hasOriginalPublication(current.admission(), current.guard(), receipt);
        return !unproved[0];
    }

    private Optional<OperationReceipt> find(Admission current, VerifiedKey key, boolean past) {
        long generation = current.writer().generation();
        if (key.generation() > generation || past != (key.generation() < generation)) {
            throw new AuthorityUnavailableException();
        }
        Optional<OperationReceipt> found;
        if (past) {
            try {
                found = originalReceipts.find(current, key);
            } catch (AuthorityUnavailableException unproved) {
                return Optional.empty();
            }
        } else {
            found = receipts.find(key.receiptKey());
        }
        return found.filter(receipt -> receipt.key().equals(key.receiptKey())
                && receipt.keyGeneration() == key.generation() && receipt.expiresAt().equals(key.expiresAt()));
    }

    private GuardedRelease.Payload payload(Admission current, OperationReceipt receipt, IntentDigest.Intent intent,
                                           Function<OperationReceipt, byte[]> encode) {
        if (!digests.matches(receipt.intentDigest(), intent)) {
            return new GuardedRelease.Payload(new byte[]{CONFLICT}, Optional.empty());
        }
        byte[] envelope = encode.apply(receipt);
        if (envelope.length == 0 || envelope.length >= ReleaseBuffer.MAX_BYTES) {
            return new GuardedRelease.Payload(new byte[0], Optional.empty());
        }
        Optional<ReleaseEvidence.Correlation> correlation;
        try {
            correlation = preparation.prepare(current, receipt);
        } catch (AuthorityUnavailableException unavailable) {
            // Current disclosure is rechecked even when preparation cannot be retained.
            return new GuardedRelease.Payload(new byte[0], Optional.empty());
        }
        byte[] bytes = new byte[envelope.length + 1];
        bytes[0] = SUCCESS;
        System.arraycopy(envelope, 0, bytes, 1, envelope.length);
        return new GuardedRelease.Payload(bytes, correlation);
    }

    static void requireBudget(long started) {
        if (System.nanoTime() - started >= BUDGET_NANOS) {
            throw new ExecutionWindowClosed();
        }
    }

    static final class ExecutionWindowClosed extends RuntimeException { }
}
