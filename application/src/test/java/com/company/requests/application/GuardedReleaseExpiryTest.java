package com.company.requests.application;

import com.company.requests.application.GuardedRelease.Outcome;
import com.company.requests.domain.Action;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedReleaseExpiryTest {
    private static final OrganizationId ORGANIZATION = new OrganizationId("release-test");
    private static final PersonId PERSON = new PersonId("original-person");
    private static final AuthenticatedActor ACTOR = new AuthenticatedActor("issuer", ORGANIZATION, "principal",
            AuthenticatedActor.Kind.HUMAN, Optional.of(PERSON));
    private static final RequestType VISIBLE = RequestType.HR_TICKET;
    private static final RequestType CONCEALED = RequestType.BUG_REPORT;
    private static final RequestRef REF = new RequestRef("original-ref");
    private static final OperationReceipt RECEIPT = new OperationReceipt(
            new ReceiptKey(ACTOR.issuer(), ORGANIZATION, ACTOR.principal(), "token"),
            new ReceiptIntentDigest(1, "aa"), 1, Instant.EPOCH.plusSeconds(10),
            Action.CREATE, REF, 1, RequestState.CREATED, Optional.empty(), 201);

    enum Cut { ABSENT_RECEIPT, CONCEALED_TARGET, EMPTY_PREPARATION, OVERSIZED_PREPARATION }

    @ParameterizedTest
    @EnumSource(Cut.class)
    void receiptDerivedOutcomeYieldsToExpiry(Cut cut) {
        var fresh = new AtomicBoolean(true);
        var release = guarded(() -> {
            RequestType type = VISIBLE;
            if (cut == Cut.CONCEALED_TARGET) {
                fresh.set(false);
                type = CONCEALED;
            }
            return Optional.of(new Request(REF, type, ORGANIZATION, "request", RequestState.CREATED,
                    1, 1, new CreationOrder(1, 1), PERSON, Optional.empty()));
        }, new AtomicBoolean(true));
        var buffer = new ReleaseBuffer(8);
        var outcome = release.handoffCreate(ACTOR, VISIBLE, fresh::get, () -> {
            if (cut == Cut.ABSENT_RECEIPT) {
                fresh.set(false);
                return Optional.empty();
            }
            return Optional.of(RECEIPT);
        }, ignored -> {
            fresh.set(false);
            return switch (cut) {
                case EMPTY_PREPARATION -> new byte[0];
                case OVERSIZED_PREPARATION -> new byte[9];
                default -> throw new AssertionError("Undisclosable result reached preparation");
            };
        }, buffer);
        assertEquals(Outcome.FRESHNESS_LOST, outcome);
        assertTrue(buffer.take().isEmpty());
    }

    @Test
    void expiredKeyStopsBeforeLookup() {
        var release = guarded(GuardedReleaseExpiryTest::unexpectedCall, new AtomicBoolean(true));
        var buffer = new ReleaseBuffer(8);
        assertEquals(Outcome.FRESHNESS_LOST, release.handoffCreate(ACTOR, VISIBLE, () -> false,
                GuardedReleaseExpiryTest::unexpectedCall, ignored -> unexpectedCall(), buffer));
        assertTrue(buffer.take().isEmpty());
    }

    @Test
    void unprovedAuthorityAfterLookupStillClosesRelease() {
        var proofAvailable = new AtomicBoolean(true);
        var release = guarded(GuardedReleaseExpiryTest::unexpectedCall, proofAvailable);
        var buffer = new ReleaseBuffer(8);
        assertThrows(AuthorityUnavailableException.class,
                () -> release.handoffCreate(ACTOR, VISIBLE, () -> true, () -> {
                    proofAvailable.set(false);
                    return Optional.empty();
                }, ignored -> unexpectedCall(), buffer));
        assertTrue(buffer.take().isEmpty());
    }

    private static GuardedRelease guarded(Supplier<Optional<Request>> findRequest, AtomicBoolean proofAvailable) {
        var policy = new SyntheticScopePolicy(ORGANIZATION, 1,
                List.of(new SyntheticScopePolicy.Grant(ACTOR, true, Set.of(VISIBLE, CONCEALED), Set.of(VISIBLE))));
        var access = new CurrentAccess(admission -> {
            if (!proofAvailable.get()) {
                throw new AuthorityUnavailableException();
            }
        }, new WriterEpoch(UUID.randomUUID(), 1), List.of(policy));
        var transactions = new TransactionRunner() {
            @Override
            public <T> T run(Supplier<T> work) {
                return work.get();
            }
        };
        var store = new ReadStore(findRequest);
        return new GuardedRelease(transactions, store, store, access, Duration.ofMillis(2400));
    }

    private static <T> T unexpectedCall() {
        throw new AssertionError("Unexpected store or preparation call");
    }

    private record ReadStore(Supplier<Optional<Request>> findRequest) implements PolicyGuardStore, RequestStore {
        @Override public void insert(PolicyGuard guard) { unexpectedCall(); }
        @Override public Optional<PolicyGuard> find(OrganizationId org) { return Optional.of(new PolicyGuard(org, 1)); }
        @Override public Optional<PolicyGuard> lockShared(OrganizationId org) { return find(org); }
        @Override public Optional<PolicyGuard> lockExclusive(OrganizationId org) { return unexpectedCall(); }
        @Override public void insert(Request request) { unexpectedCall(); }
        @Override public void insertBody(OrganizationId org, RequestRef ref, RequestBody body) { unexpectedCall(); }
        @Override public void appendFact(OrganizationId org, TransitionFact fact) { unexpectedCall(); }
        @Override public Optional<Request> find(OrganizationId org, RequestRef ref) { return findRequest.get(); }
        @Override public Optional<RequestBody> findRetainedBody(OrganizationId org, RequestRef ref) { return unexpectedCall(); }
        @Override public List<TransitionFact> findFacts(OrganizationId org, RequestRef ref) { return unexpectedCall(); }
    }
}
