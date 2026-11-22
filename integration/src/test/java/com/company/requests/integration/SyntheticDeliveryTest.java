package com.company.requests.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.SyntheticDelivery.Action;
import com.company.requests.integration.SyntheticDelivery.Coverage;
import com.company.requests.integration.SyntheticDelivery.Disposition;
import com.company.requests.integration.SyntheticDelivery.Effect;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.Exclusion;
import com.company.requests.integration.SyntheticDelivery.NoApplication;
import com.company.requests.integration.SyntheticDelivery.Observation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyntheticDeliveryTest {
    private static final Invocation INVOCATION = new Invocation(new AttemptId(
            new EffectKey("fixture-alpha", new OrganizationId("fixture-tenant"), "a".repeat(64)), 2),
            new RequestRef("A".repeat(32)), 4);
    private static final UUID ENDPOINT = UUID.randomUUID();

    @Test
    void distinguishesDurableAcceptanceFromApplicationAndAnUnobservedReply() {
        assertEquals(Disposition.ACCEPTED_PENDING, SyntheticDelivery.sent(evidence(Observation.ACCEPTED, Exclusion.NONE),
                Exclusion.NONE));
        assertEquals(Disposition.CONFIRMED, SyntheticDelivery.sent(evidence(Observation.CONFIRMED, Exclusion.NONE),
                Exclusion.NONE));
        assertEquals(Disposition.UNKNOWN, SyntheticDelivery.sent(Optional.empty(), Exclusion.NONE));
        assertEquals(Disposition.ACCEPTED_PENDING,
                reconciled(Action.RETRY, Observation.ACCEPTED, Exclusion.NONE));
    }

    @Test
    void absenceRequiresAStableSingleEffectContractOrActualExclusion() {
        assertEquals(Disposition.QUARANTINED,
                reconciled(Action.FENCE_AND_RETRY, Observation.ABSENT, Exclusion.NONE));
        assertEquals(Disposition.RETRYABLE,
                reconciled(Action.RETRY, Observation.ABSENT, Exclusion.SINGLE_EFFECT));
        assertEquals(Disposition.RETRYABLE,
                reconciled(Action.FENCE_AND_RETRY, Observation.ABSENT, Exclusion.FENCED));
        assertEquals(Disposition.QUARANTINED,
                reconciled(Action.SUPPRESS, Observation.ABSENT, Exclusion.SINGLE_EFFECT));
        assertEquals(Disposition.SUPPRESSED,
                reconciled(Action.FENCE_AND_SUPPRESS, Observation.ABSENT, Exclusion.FENCED));
    }

    @Test
    void terminalProofClosesOnlyWithCoverageForEveryDurablyArmedAttempt() {
        var currentClosed = new NoApplication(0, Set.of(2L));
        assertTrue(currentClosed.closesArmedLineage(2, List.of(2L)), "ordinal 1 never armed");
        assertFalse(currentClosed.closesArmedLineage(2, List.of(1L, 2L)), "ordinal 1 armed and could still arrive");
        assertFalse(currentClosed.closesArmedLineage(2, List.of(1L)), "the exact current ordinal must have armed");
        assertFalse(new NoApplication(0, Set.of(1L)).closesArmedLineage(2, List.of(1L, 2L)),
                "older closure alone leaves the current attempt uncertain");
        assertTrue(new NoApplication(1, Set.of(2L)).closesArmedLineage(2, List.of(1L, 2L)));
    }

    @Test
    void requiresExactScopeProjectionAttemptAndAuthoritativeCoverage() {
        var original = evidence(Observation.CONFIRMED, Exclusion.NONE).orElseThrow();
        var key = INVOCATION.id().effect();
        var alternatives = new Invocation[] {
            new Invocation(new AttemptId(new EffectKey("fixture-beta", key.tenant(), key.effectId()), 2),
                    INVOCATION.ref(), INVOCATION.revision()),
            new Invocation(new AttemptId(key, 1), INVOCATION.ref(), INVOCATION.revision()),
            new Invocation(INVOCATION.id(), INVOCATION.ref(), INVOCATION.revision() + 1)
        };
        for (var candidate : alternatives) {
            assertTrue(SyntheticDelivery.exact(candidate, Optional.of(original)).isEmpty());
        }
        for (var quality : new Coverage[]{Coverage.STALE, Coverage.PARTIAL}) {
            var evidence = new Evidence(INVOCATION, ENDPOINT, original.reference(), original.observation(),
                    original.exclusion(), original.effect(), quality, Optional.empty());
            assertTrue(SyntheticDelivery.exact(INVOCATION, Optional.of(evidence)).isEmpty());
        }
        var future = new Evidence(INVOCATION, ENDPOINT, original.reference(), original.observation(),
                original.exclusion(), Optional.of(new Effect(UUID.randomUUID(), 3)));
        assertTrue(SyntheticDelivery.exact(INVOCATION, Optional.of(future)).isEmpty());
        assertEquals(Optional.of(original), SyntheticDelivery.exact(INVOCATION, Optional.of(original)));
    }

    private static Optional<Evidence> evidence(Observation observation, Exclusion exclusion) {
        var applied = observation == Observation.CONFIRMED ? Optional.of(new Effect(UUID.randomUUID(), 1))
                : Optional.<Effect>empty();
        return Optional.of(new Evidence(INVOCATION, ENDPOINT, UUID.randomUUID(), observation, exclusion, applied));
    }

    private static Disposition reconciled(Action action, Observation observation, Exclusion exclusion) {
        return SyntheticDelivery.reconciled(action, evidence(observation, exclusion), exclusion);
    }
}
