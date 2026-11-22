package com.company.requests.application;

import com.company.requests.application.OperationKeyIssuer.Eligibility;
import com.company.requests.application.OperationKeyIssuer.VerifiedKey;
import com.company.requests.application.ReceiptReplay.ExecutionWindowClosed;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.TransitionFact;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static com.company.requests.application.ReceiptReplay.requireBudget;

/** Create and exact retry under the certified writer and current organization policy. */
public final class CreateCommand {
    private final TransactionRunner transactions;
    private final PolicyGuardStore guards;
    private final RequestStore requests;
    private final CreateStore creates;
    private final CurrentAccess access;
    private final WriterLineage lineage;
    private final IdentityAuthority identities;
    private final OperationKeyIssuer keys;
    private final IntentDigest digests;
    private final ReceiptReplay replay;

    public CreateCommand(TransactionRunner transactions, PolicyGuardStore guards, RequestStore requests,
                         ReceiptStore receipts, CreateStore creates, CurrentAccess access, WriterLineage lineage,
                         IdentityAuthority identities, OperationKeyIssuer keys, IntentDigest digests,
                         ReleasePreparation preparation, GuardedRelease release) {
        this(transactions, guards, requests, receipts, creates, access, lineage, identities, keys, digests,
                preparation, release, OriginalReceiptProof.unavailable());
    }

    public CreateCommand(TransactionRunner transactions, PolicyGuardStore guards, RequestStore requests,
                         ReceiptStore receipts, CreateStore creates, CurrentAccess access, WriterLineage lineage,
                         IdentityAuthority identities, OperationKeyIssuer keys, IntentDigest digests,
                         ReleasePreparation preparation, GuardedRelease release, OriginalReceiptProof originalReceipts) {
        this.transactions = transactions;
        this.guards = guards;
        this.requests = requests;
        this.creates = creates;
        this.access = access;
        this.lineage = lineage;
        this.identities = identities;
        this.keys = keys;
        this.digests = digests;
        this.replay = new ReceiptReplay(receipts, access, keys, digests, preparation, release,
                Objects.requireNonNull(originalReceipts, "earlier receipt proof"));
    }

    public sealed interface Result permits Rejection, Released { }

    public enum Rejection implements Result { CONCEALED, CONFLICT, EXPIRED, FENCED, OUTCOME_UNKNOWN }

    /** A bounded, already authorized HTTP envelope. Its bytes become available after guarded handoff. */
    public record Released(byte[] envelope) implements Result { }

    public OperationKeyIssuer.IssuedKey issue(AuthenticatedActor actor) {
        try {
            if (access.usesEffectiveScope()) {
                return transactions.run(() -> keys.issue(actor, access.requireKeyIssueOnCommand(actor)));
            }
            var admission = access.requireKeyIssue(actor);
            return transactions.run(() -> {
                lineage.requireCurrent(admission);
                return keys.issue(actor);
            });
        } catch (CommitOutcomeUnknownException | TransactionBusyException unavailable) {
            throw new AuthorityUnavailableException();
        }
    }

    public Result execute(AuthenticatedActor actor, String signedKey, IntentDigest.Create intent,
                          Function<OperationReceipt, byte[]> encode) {
        return execute(actor, signedKey, intent, encode, new ReleaseBuffer(ReleaseBuffer.MAX_BYTES));
    }

    public Result execute(AuthenticatedActor actor, String signedKey, IntentDigest.Create intent,
                          Function<OperationReceipt, byte[]> encode, ReleaseBuffer buffer) {
        Objects.requireNonNull(encode, "result encoder");
        Objects.requireNonNull(buffer, "release buffer");
        long started = System.nanoTime();
        try {
            var immutableAdmission = access.usesEffectiveScope() ? null : access.requireCreate(actor, intent.type());
            var verified = transactions.run(() -> {
                var current = immutableAdmission;
                if (current == null) {
                    current = access.requireCreateOnCommand(actor, intent.type());
                } else {
                    lineage.requireCurrent(current);
                }
                requireBudget(started);
                return new AuthorizedKey(current, keys.verify(signedKey, actor, current));
            });
            var key = verified.key();
            if (key.eligibility() == Eligibility.ACTIVE_CAN_EXECUTE) {
                try {
                    transactions.run(() -> {
                        arbitrate(actor, key, intent, verified.admission(), started);
                        return null;
                    });
                } catch (CommitOutcomeUnknownException uncertain) {
                    // The next phase can prove and release the effect. It never executes Create again.
                }
            }
            var result = replay.resolve(actor, key, intent, guard -> {
                var admission = access.requireCreate(actor, intent.type(), guard);
                if (!access.usesEffectiveScope()) {
                    lineage.requireCurrent(admission);
                }
                return admission;
            }, encode, buffer, started);
            return switch (result) {
                case ReceiptReplay.Released released -> new Released(released.envelope());
                case ReceiptReplay.Rejection rejection -> Rejection.valueOf(rejection.name());
            };
        } catch (CommitOutcomeUnknownException | TransactionBusyException | TransactionResourceUnavailableException
                 | ExecutionWindowClosed unavailable) {
            return Rejection.OUTCOME_UNKNOWN;
        }
    }

    private void arbitrate(AuthenticatedActor actor, VerifiedKey key, IntentDigest.Create intent,
                           Admission verifiedAdmission, long started) {
        // Effective submitted access was proved under a shared guard in the completed verification transaction.
        // Claim the receipt before this transaction takes the exclusive guard. The claim is still tentative.
        var admission = access.usesEffectiveScope() ? verifiedAdmission : access.requireCreate(actor, intent.type());
        lineage.requireCurrent(admission);
        if (keys.recheck(key, actor, admission) != Eligibility.ACTIVE_CAN_EXECUTE) {
            return;
        }
        requireBudget(started);
        ReceiptIntentDigest digest = digests.calculate(intent);
        if (!creates.claim(key.receiptKey(), digest, key.generation(), key.expiresAt())) {
            return;
        }

        var guard = guards.lockExclusive(actor.organization()).orElseThrow(AuthorityUnavailableException::new);
        admission = access.requireCreate(actor, intent.type(), guard);
        requireEffectWindow(key, actor, admission, started);
        var reservation = identities.reserve(admission, UUID.randomUUID());
        admission = access.requireCreate(actor, intent.type(), guard);
        if (!access.usesEffectiveScope()) {
            lineage.requireCurrent(admission);
        }
        requireEffectWindow(key, actor, admission, started);
        var person = actor.person().orElseThrow();
        var effect = Lifecycle.create(new Lifecycle.Creation(reservation.ref(), intent.type(), actor.organization(),
                        intent.name(), new RequestBody(intent.body()), reservation.creationOrder(), person),
                new TransitionFact.Occurrence(keys.requireFreshInstant(key), actor.principal()));
        requests.insert(effect.request());
        requests.insertBody(actor.organization(), reservation.ref(), effect.body());
        requests.appendFact(actor.organization(), effect.transition());
        creates.insertInitialAssignment(actor.organization(), reservation.ref(), person);
        creates.finalizeReceipt(new OperationReceipt(key.receiptKey(), digest, key.generation(), key.expiresAt(),
                Action.CREATE, reservation.ref(), effect.request().revision(), effect.request().state(),
                Optional.empty(), 201));
        lineage.requireCurrent(admission);
        requireEffectWindow(key, actor, admission, started);
    }

    private void requireEffectWindow(VerifiedKey key, AuthenticatedActor actor, Admission admission,
                                     long started) {
        if (keys.recheck(key, actor, admission) != Eligibility.ACTIVE_CAN_EXECUTE) {
            throw new ExecutionWindowClosed();
        }
        requireBudget(started);
    }

    private record AuthorizedKey(Admission admission, VerifiedKey key) { }
}
