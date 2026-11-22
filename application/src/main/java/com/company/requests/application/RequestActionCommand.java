package com.company.requests.application;

import com.company.requests.application.ActionContextService.Verified;
import com.company.requests.application.OperationKeyIssuer.Eligibility;
import com.company.requests.application.OperationKeyIssuer.VerifiedKey;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.ReceiptReplay.ExecutionWindowClosed;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import com.company.requests.domain.TransitionReason;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static com.company.requests.application.ReceiptReplay.requireBudget;

/** Atomic existing-request commands under the certified writer and current scoped grants. */
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
    private final Optional<PublishExecution> publication;

    public RequestActionCommand(TransactionRunner transactions, CurrentAccess access, WriterLineage lineage,
                                OperationKeyIssuer keys, IntentDigest digests, ReceiptStore receipts,
                                ReceiptClaims claims, RequestStore requests, ExistingRequestStore effects,
                                ActionContextService contexts, SyntheticScopePolicy policy, EffectiveScopeStore scope,
                                ScopeAuthorityMutation scopeMutation, GuardedRelease release,
                                ReleasePreparation preparation, OriginalReceiptProof originalReceipts) {
        this(transactions, access, lineage, keys, digests, receipts, claims, requests, effects, contexts,
                policy, scope, scopeMutation, release, preparation, originalReceipts, Optional.empty());
    }

    public RequestActionCommand(TransactionRunner transactions, CurrentAccess access, WriterLineage lineage,
                                OperationKeyIssuer keys, IntentDigest digests, ReceiptStore receipts,
                                ReceiptClaims claims, RequestStore requests, ExistingRequestStore effects,
                                ActionContextService contexts, SyntheticScopePolicy policy, EffectiveScopeStore scope,
                                ScopeAuthorityMutation scopeMutation, GuardedRelease release,
                                ReleasePreparation preparation, OriginalReceiptProof originalReceipts,
                                Optional<PublishExecution> publication) {
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
        this.publication = Objects.requireNonNull(publication);
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
        return execute(actor, key, new Submission(intent.action(), intent.ref(), intent.observation(), intent), encode);
    }

    private Result execute(AuthenticatedActor actor, String signedKey, Submission submitted,
                           Function<OperationReceipt, byte[]> encode) {
        long started = System.nanoTime();
        try {
            var authorized = transactions.run(() -> {
                var target = access.requireSubmittedAction(actor, submitted.ref(), submitted.action());
                requireBudget(started);
                return new AuthorizedKey(target.admission(), keys.verify(signedKey, actor, target.admission()),
                        target.lookupOnly());
            });
            var key = authorized.key();
            Function<PolicyGuard, Admission> authorize = guard ->
                    access.requireSubmittedAction(actor, guard, submitted.ref(), submitted.action()).admission();
            if (key.eligibility() == Eligibility.ACTIVE_CAN_EXECUTE && !authorized.lookupOnly()) {
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
                    new ReleaseBuffer(ReleaseBuffer.MAX_BYTES), started, authorized.lookupOnly()
                            ? ReceiptReplay.Rejection.ILLEGAL_STATE : ReceiptReplay.Rejection.OUTCOME_UNKNOWN)) {
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
        if (submitted.action() == Action.PUBLISH) {
            var publisher = publication.orElseThrow(AuthorityUnavailableException::new);
            if (publisher.journal().activeBinding(admission.writer(), guard.organization(), request.type(),
                    request.ref()).isPresent()) {
                throw new AuthorityUnavailableException();
            }
        }
        var scopeEdit = policy.visibilityChange(submitted.action(), request, verified.visibility());
        OperationReceipt receipt;
        if (scopeEdit.isPresent()) {
            var change = new EffectiveScopeChange(guard, actor, scopeEdit.orElseThrow());
            receipt = scopeMutation.apply(admission, guard, new ScopeAuthorityMutation.Plan<>(change.delta(), prepared -> {
                requireEffectWindow(actor, key, admission, started);
                var applied = applyEffect(actor, submitted, key, digest, verified);
                scope.apply(guard, prepared, change);
                return applied;
            })).result();
        } else {
            receipt = applyEffect(actor, submitted, key, digest, verified);
        }
        if (submitted.action() == Action.PUBLISH) {
            publication.orElseThrow().outbox().filter(trigger -> trigger.type() == request.type()).ifPresent(trigger ->
                    trigger.appender().append(OutboundObligation.publishReference(trigger.destination(), receipt,
                            keys.requireFreshInstant(key))));
        }
        lineage.requireCurrent(admission);
        requireEffectWindow(actor, key, admission, started);
    }

    private OperationReceipt applyEffect(AuthenticatedActor actor, Submission submitted, VerifiedKey key,
                                         ReceiptIntentDigest digest, Verified verified) {
        var guard = verified.guard();
        var before = verified.request();
        Request after;
        Optional<Entry> reserved = Optional.empty();
        if (submitted.intent() instanceof IntentDigest.Edit edit) {
            var effect = Lifecycle.edit(before, new RequestBody(edit.body()));
            after = effect.request();
            effects.advance(guard, before, after);
            effects.replaceBody(guard, before.ref(), effect.body());
        } else {
            var intent = (IntentDigest.Transition) submitted.intent();
            if (submitted.action() == Action.PUBLISH) {
                reserved = Optional.of(reserve(verified, key));
            }
            Lifecycle.TransitionCommand transition = switch (submitted.action()) {
                case VERIFY -> new Lifecycle.Verify();
                case ACCEPT -> new Lifecycle.Accept();
                case DELETE -> new Lifecycle.Delete(new TransitionReason(intent.reason().orElseThrow()));
                case REJECT -> new Lifecycle.Reject(new TransitionReason(intent.reason().orElseThrow()));
                case PUBLISH -> new Lifecycle.Publish(reserved.flatMap(Entry::allocation).orElseThrow());
                default -> throw new IllegalStateException("Unexpected request action.");
            };
            var effect = Lifecycle.transition(before, transition,
                    new TransitionFact.Occurrence(keys.requireFreshInstant(key), actor.principal()));
            after = effect.request();
            effects.advance(guard, before, after);
            requests.appendFact(guard.organization(), effect.transition());
            effect.reason().ifPresent(reason -> effects.appendReason(guard, effect.transition(), reason));
        }
        var receipt = new OperationReceipt(key.receiptKey(), digest, key.generation(), key.expiresAt(),
                submitted.action(), after.ref(), after.revision(), after.state(), after.publicationId(), 200);
        claims.finalizeReceipt(receipt);
        reserved.ifPresent(entry -> publication.orElseThrow().gate().appendWitness(entry, receipt));
        return receipt;
    }

    private Entry reserve(Verified verified, VerifiedKey key) {
        var publisher = publication.orElseThrow();
        var before = verified.request();
        var attempt = publisher.gate().acquire(verified.guard(), verified.admission().writer(),
                before.ref(), key.receiptKey());
        var registered = publisher.journal().register(attempt);
        if (!registered.attempt().equals(attempt) || registered.state() != PublicationJournal.State.REGISTERED) {
            throw new AuthorityUnavailableException();
        }
        var reserved = publisher.journal().reserve(attempt);
        if (!reserved.attempt().equals(attempt) || reserved.state() != PublicationJournal.State.PENDING) {
            throw new AuthorityUnavailableException();
        }
        return reserved;
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

    private record AuthorizedKey(Admission admission, VerifiedKey key, boolean lookupOnly) { }

    private record Submission(Action action, RequestRef ref, String observation, IntentDigest.Intent intent) { }

    /** Command-side publication dependencies. Resolution is held on a separate private path. */
    public record PublishExecution(PublishGate gate, PublicationJournal journal, Optional<PublishOutbox> outbox) {
        public PublishExecution(PublishGate gate, PublicationJournal journal) {
            this(gate, journal, Optional.empty());
        }

        public PublishExecution {
            Objects.requireNonNull(gate);
            Objects.requireNonNull(journal);
            Objects.requireNonNull(outbox);
        }
    }

    /** Opt-in Publish trigger for one trusted synthetic request type and destination. */
    public record PublishOutbox(RequestType type, String destination, OutboxAppendPort appender) {
        public PublishOutbox {
            Objects.requireNonNull(type);
            OutboundObligation.requireDestination(destination);
            Objects.requireNonNull(appender);
        }
    }

    private static final class RejectedEffect extends RuntimeException {
        private final Rejection rejection;

        private RejectedEffect(Rejection rejection) {
            this.rejection = rejection;
        }
    }
}
