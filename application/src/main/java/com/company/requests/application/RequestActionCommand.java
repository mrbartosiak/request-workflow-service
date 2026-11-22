package com.company.requests.application;

import com.company.requests.application.ActionContextService.Verified;
import com.company.requests.application.OperationKeyIssuer.Eligibility;
import com.company.requests.application.OperationKeyIssuer.VerifiedKey;
import com.company.requests.application.ReceiptReplay.ExecutionWindowClosed;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.TransitionFact;
import java.util.Optional;
import java.util.function.Function;

import static com.company.requests.application.ReceiptReplay.requireBudget;

/** Atomic Edit, Verify and Accept under the certified writer and current scoped grants. */
public final class RequestActionCommand {
    private final TransactionRunner transactions;
    private final CurrentAccess access;
    private final WriterLineage lineage;
    private final OperationKeyIssuer keys;
    private final IntentDigest digests;
    private final ReceiptClaims claims;
    private final RequestStore requests;
    private final ExistingRequestStore effects;
    private final ActionContextService contexts;
    private final SyntheticScopePolicy policy;
    private final EffectiveScopeStore scope;
    private final ScopeAuthorityMutation scopeMutation;
    private final GuardedRelease release;
    private final ReceiptReplay replay;

    public RequestActionCommand(TransactionRunner transactions, CurrentAccess access, WriterLineage lineage,
                                OperationKeyIssuer keys, IntentDigest digests, ReceiptStore receipts,
                                ReceiptClaims claims, RequestStore requests, ExistingRequestStore effects,
                                ActionContextService contexts, SyntheticScopePolicy policy, EffectiveScopeStore scope,
                                ScopeAuthorityMutation scopeMutation, GuardedRelease release,
                                ReleasePreparation preparation, OriginalReceiptProof originalReceipts) {
        this.transactions = transactions;
        this.access = access;
        this.lineage = lineage;
        this.keys = keys;
        this.digests = digests;
        this.claims = claims;
        this.requests = requests;
        this.effects = effects;
        this.contexts = contexts;
        this.policy = policy;
        this.scope = scope;
        this.scopeMutation = scopeMutation;
        this.release = release;
        replay = new ReceiptReplay(receipts, access, keys, digests, preparation, release, originalReceipts);
    }

    public sealed interface Result permits Rejection, Released { }

    public enum Rejection implements Result {
        CONCEALED, DENIED, MALFORMED, STALE, ILLEGAL_STATE, CONFLICT, EXPIRED, FENCED, OUTCOME_UNKNOWN
    }

    /** Response bytes available only after bounded guarded handoff. */
    public record Released(byte[] envelope) implements Result { }

    public Result edit(AuthenticatedActor actor, String key, IntentDigest.Edit intent,
                       Function<OperationReceipt, byte[]> encode) {
        return execute(actor, key, new Submission(Action.EDIT, intent.ref(), intent.observation(), intent), encode);
    }

    public Result transition(AuthenticatedActor actor, String key, IntentDigest.Transition intent,
                             Function<OperationReceipt, byte[]> encode) {
        if (intent.action() != Action.VERIFY && intent.action() != Action.ACCEPT) {
            throw new IllegalArgumentException("This command accepts Verify and Accept transitions.");
        }
        return execute(actor, key, new Submission(intent.action(), intent.ref(), intent.observation(), intent), encode);
    }

    private Result execute(AuthenticatedActor actor, String signedKey, Submission submitted,
                           Function<OperationReceipt, byte[]> encode) {
        long started = System.nanoTime();
        try {
            var authorized = transactions.run(() -> {
                var admission = access.requireSubmittedAction(actor, submitted.ref(), submitted.action());
                requireBudget(started);
                return new AuthorizedKey(admission, keys.verify(signedKey, actor, admission));
            });
            var key = authorized.key();
            Function<PolicyGuard, Admission> authorize = guard ->
                    access.requireSubmittedAction(actor, guard, submitted.ref(), submitted.action());
            if (key.eligibility() == Eligibility.ACTIVE_CAN_EXECUTE) {
                try {
                    transactions.run(() -> {
                        arbitrate(actor, submitted, authorized, started);
                        return null;
                    });
                } catch (CommitOutcomeUnknownException uncertain) {
                    // Only the independent replay phase can resolve this commit. No second effect is attempted.
                } catch (RejectedEffect rejected) {
                    return releaseRejection(actor, key, authorize, rejected.rejection, started);
                }
            }

            return switch (replay.resolve(actor, key, submitted.intent(), authorize, encode,
                    new ReleaseBuffer(ReleaseBuffer.MAX_BYTES), started)) {
                case ReceiptReplay.Released released -> new Released(released.envelope());
                case ReceiptReplay.Rejection rejection -> Rejection.valueOf(rejection.name());
            };
        } catch (CurrentAccess.TargetConcealed concealed) {
            return Rejection.CONCEALED;
        } catch (AccessDeniedException denied) {
            return Rejection.DENIED;
        } catch (CommitOutcomeUnknownException | TransactionBusyException | TransactionResourceUnavailableException
                 | ExecutionWindowClosed unavailable) {
            return Rejection.OUTCOME_UNKNOWN;
        }
    }

    private void arbitrate(AuthenticatedActor actor, Submission submitted, AuthorizedKey authorized, long started) {
        var key = authorized.key();
        lineage.requireCurrent(authorized.admission());
        if (keys.recheck(key, actor, authorized.admission()) != Eligibility.ACTIVE_CAN_EXECUTE) {
            return;
        }
        requireBudget(started);
        var digest = digests.calculate(submitted.intent());
        if (!claims.claim(key.receiptKey(), digest, key.generation(), key.expiresAt())) {
            return;
        }

        var verification = contexts.verifyNewIntent(actor, submitted.ref(), submitted.action(), submitted.observation(),
                admission -> requireEffectWindow(actor, key, admission, started),
                Optional.of((guard, material) -> effects.lockForChange(guard, submitted.ref(), material)));
        if (verification instanceof ActionContextService.Rejection rejection) {
            throw new RejectedEffect(Rejection.valueOf(rejection.name()));
        }
        var verified = (Verified) verification;
        var request = verified.request();
        var guard = verified.guard();
        var admission = verified.admission();
        var scopeEdit = policy.visibilityChange(submitted.action(), request, verified.visibility());
        if (scopeEdit.isPresent()) {
            var change = new EffectiveScopeChange(guard, actor, scopeEdit.orElseThrow());
            scopeMutation.apply(admission, guard, new ScopeAuthorityMutation.Plan<>(change.delta(), prepared -> {
                requireEffectWindow(actor, key, admission, started);
                applyEffect(actor, submitted, key, digest, verified);
                scope.apply(guard, prepared, change);
                return null;
            }));
        } else {
            applyEffect(actor, submitted, key, digest, verified);
        }
        lineage.requireCurrent(admission);
        requireEffectWindow(actor, key, admission, started);
    }

    private void applyEffect(AuthenticatedActor actor, Submission submitted, VerifiedKey key,
                             ReceiptIntentDigest digest, Verified verified) {
        var guard = verified.guard();
        var before = verified.request();
        Request after;
        if (submitted.intent() instanceof IntentDigest.Edit edit) {
            var effect = Lifecycle.edit(before, new RequestBody(edit.body()));
            after = effect.request();
            effects.advance(guard, before, after);
            effects.replaceBody(guard, before.ref(), effect.body());
        } else {
            Lifecycle.TransitionCommand transition = switch (submitted.action()) {
                case VERIFY -> new Lifecycle.Verify();
                case ACCEPT -> new Lifecycle.Accept();
                default -> throw new IllegalStateException("Unexpected request action.");
            };
            var effect = Lifecycle.transition(before, transition,
                    new TransitionFact.Occurrence(keys.requireFreshInstant(key), actor.principal()));
            after = effect.request();
            effects.advance(guard, before, after);
            requests.appendFact(guard.organization(), effect.transition());
        }
        claims.finalizeReceipt(new OperationReceipt(key.receiptKey(), digest, key.generation(), key.expiresAt(),
                submitted.action(), after.ref(), after.revision(), after.state(), Optional.empty(), 200));
    }

    private Result releaseRejection(AuthenticatedActor actor, VerifiedKey key, Function<PolicyGuard, Admission> authorize,
                                    Rejection rejection, long started) {
        var buffer = new ReleaseBuffer(1);
        var outcome = release.handoff(actor.organization(), authorize, () -> {
            if (!keys.isFresh(key)) {
                return false;
            }
            requireBudget(started);
            return true;
        }, context -> Optional.of(new GuardedRelease.Prepared(
                new GuardedRelease.Payload(new byte[]{(byte) rejection.ordinal()}, Optional.empty()),
                checked -> true)), buffer);
        return switch (outcome) {
            case HANDED_OFF -> Rejection.values()[buffer.take().orElseThrow()[0]];
            case FRESHNESS_LOST -> Rejection.EXPIRED;
            default -> Rejection.OUTCOME_UNKNOWN;
        };
    }

    private void requireEffectWindow(AuthenticatedActor actor, VerifiedKey key, Admission admission, long started) {
        if (keys.recheck(key, actor, admission) != Eligibility.ACTIVE_CAN_EXECUTE) {
            throw new ExecutionWindowClosed();
        }
        requireBudget(started);
    }

    private record AuthorizedKey(Admission admission, VerifiedKey key) { }

    private record Submission(Action action, RequestRef ref, String observation, IntentDigest.Intent intent) { }

    private static final class RejectedEffect extends RuntimeException {
        private final Rejection rejection;

        private RejectedEffect(Rejection rejection) {
            this.rejection = rejection;
        }
    }
}
