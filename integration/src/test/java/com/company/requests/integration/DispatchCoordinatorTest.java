package com.company.requests.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.DispatchStore.ArmResult;
import com.company.requests.integration.DispatchStore.ArmState;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.DispatchStore.Receipt;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DispatchCoordinatorTest {
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 1);
    private static final Claim CLAIM = new Claim(new AttemptId(
            new EffectKey("fixture-alpha", new OrganizationId("fixture-tenant"), "a".repeat(64)), 1),
            UUID.randomUUID(), WRITER);
    private static final Invocation INVOCATION = new Invocation(CLAIM.id(), new RequestRef("A".repeat(32)), 4);

    private final Store store = new Store();
    private int recorded;

    @Test
    void invokesOnlyAfterTheDurableArmReturnsAndFinishesWithExactEvidence() {
        var receipt = new Receipt(CLAIM.id(), UUID.randomUUID());
        var coordinator = new DispatchCoordinator(store, WRITER, invocation -> {
            assertEquals(INVOCATION, invocation);
            assertTrue(store.armCommitted);
            assertFalse(store.inTransaction);
            recorded++;
            return receipt;
        });

        assertEquals(DispatchCoordinator.Result.CONFIRMED, coordinator.dispatchNext());
        assertEquals(1, recorded);
        assertEquals(Optional.of(receipt), store.finished);
    }

    @Test
    void rejectsAnUnconfirmedArmCommitWithoutInvokingOrFinishing() {
        store.failArmCommit = true;
        var coordinator = new DispatchCoordinator(store, WRITER, ignored -> {
            throw new AssertionError("Unconfirmed arm invoked the recorder.");
        });

        assertThrows(CommitOutcomeUnknownException.class, coordinator::dispatchNext);
        assertEquals(0, store.finalizations);
    }

    @Test
    void neverInvokesWhenTheStoreWithholdsAnArm() {
        var coordinator = new DispatchCoordinator(store, WRITER, ignored -> {
            throw new AssertionError("Withheld arm invoked the recorder.");
        });

        for (var state : new ArmState[]{ArmState.DEFERRED, ArmState.SUPPRESSED, ArmState.STALE}) {
            store.armState = state;
            assertEquals(DispatchCoordinator.Result.valueOf(state.name()), coordinator.dispatchNext());
        }
        assertEquals(0, store.finalizations);
    }

    @Test
    void ambiguousRecorderFailureAndWrongDestinationReceiptBothFinishUnknown() {
        var failed = new DispatchCoordinator(store, WRITER, ignored -> {
            throw new IllegalStateException("Synthetic acknowledgement unavailable.");
        });
        assertEquals(DispatchCoordinator.Result.UNKNOWN, failed.dispatchNext());
        assertEquals(Optional.empty(), store.finished);

        var alternate = new AttemptId(new EffectKey("fixture-beta", CLAIM.id().effect().tenant(),
                CLAIM.id().effect().effectId()), CLAIM.id().number());
        var wrong = new DispatchCoordinator(store, WRITER, ignored -> new Receipt(alternate, UUID.randomUUID()));
        assertEquals(DispatchCoordinator.Result.UNKNOWN, wrong.dispatchNext());
        assertEquals(Optional.empty(), store.finished);
        assertEquals(2, store.finalizations);
    }

    @Test
    void neverUsesAnotherWorkersEpochAndReportsStaleFinalization() {
        var coordinator = new DispatchCoordinator(store, WRITER, ignored -> new Receipt(CLAIM.id(), UUID.randomUUID()));
        var old = new Claim(CLAIM.id(), CLAIM.token(), new WriterEpoch(WRITER.writerId(), 2));
        assertEquals(DispatchCoordinator.Result.STALE, coordinator.dispatch(old));
        assertEquals(0, store.arms);

        store.canFinalize = false;
        assertEquals(DispatchCoordinator.Result.STALE, coordinator.dispatchNext());
        assertEquals(1, store.finalizations);
    }

    private static final class Store implements DispatchStore {
        private ArmState armState = ArmState.ARMED;
        private boolean armCommitted;
        private boolean inTransaction;
        private boolean failArmCommit;
        private boolean canFinalize = true;
        private int arms;
        private int finalizations;
        private Optional<Receipt> finished;

        @Override
        public Optional<Claim> claim(WriterEpoch writer) {
            assertEquals(WRITER, writer);
            return Optional.of(CLAIM);
        }

        @Override
        public ArmResult arm(Claim claim) {
            assertEquals(CLAIM, claim);
            arms++;
            inTransaction = true;
            try {
                if (failArmCommit) {
                    throw new CommitOutcomeUnknownException(new IllegalStateException());
                }
                armCommitted = armState == ArmState.ARMED;
            } finally {
                inTransaction = false;
            }
            return new ArmResult(armState, armCommitted ? Optional.of(INVOCATION) : Optional.empty());
        }

        @Override
        public boolean finish(Claim claim, Optional<Receipt> receipt) {
            assertEquals(CLAIM, claim);
            assertTrue(armCommitted);
            assertFalse(inTransaction);
            finished = receipt;
            finalizations++;
            return canFinalize;
        }
    }
}
