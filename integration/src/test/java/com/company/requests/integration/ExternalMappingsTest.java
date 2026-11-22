package com.company.requests.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PolicyGuardStore;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthority.Genesis;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.ExternalMapping.Association;
import com.company.requests.integration.ExternalMapping.Binding;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMapping.Publication;
import com.company.requests.integration.ExternalMapping.Scope;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.ExternalMappings.Authorization;
import com.company.requests.integration.ExternalMappings.Operation;
import com.company.requests.integration.ExternalMappings.Outcome;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ExternalMappingsTest {
    private static final OrganizationId ORG = new OrganizationId("synthetic-org");
    private static final AuthenticatedActor ACTOR =
            new AuthenticatedActor("synthetic", ORG, "mapping-service", AuthenticatedActor.Kind.SERVICE, Optional.empty());
    private static final AuthenticatedActor OTHER =
            new AuthenticatedActor("synthetic", ORG, "other", AuthenticatedActor.Kind.SERVICE, Optional.empty());
    private static final Scope SCOPE = new Scope("synthetic-source", "synthetic-tenant", "local");
    private static final Key KEY = new Key(SCOPE, new byte[]{0, -1, 65});
    private static final Target A = new Target(ORG, RequestType.HR_TICKET, new RequestRef("A".repeat(32)));
    private static final Target B = new Target(ORG, RequestType.HR_TICKET, new RequestRef("B".repeat(32)));
    private static final CreationOrder A_ORDER = new CreationOrder(1, 7);
    private static final CreationOrder B_ORDER = new CreationOrder(1, 8);
    private static final Publication PUBLISHED =
            new Publication(PublicationId.INITIAL_NAMESPACE, new PublicationId(Long.MAX_VALUE));

    private final Stores stores = new Stores();
    private final Map<Target, CreationOrder> create = new HashMap<>();
    private final Map<RequestRef, PublicationId> publication = new HashMap<>();
    private final Set<Operation> grants = new HashSet<>(Set.of(Operation.BIND, Operation.USE, Operation.RECOVER));
    private final Set<Binding> originalEffects = new HashSet<>();
    private WriterEpoch writer = new WriterEpoch(UUID.randomUUID(), 1);
    private boolean requirePublication;
    private boolean failCommit;
    private int createReads;
    private int effectReads;
    private boolean inTransaction;
    private final PolicyGuard guard = new PolicyGuard(ORG, 1);

    private final ExternalMappings mappings = new ExternalMappings(new TransactionRunner() {
        @Override
        public <T> T run(Supplier<T> work) {
            assertFalse(inTransaction);
            var prior = Map.copyOf(stores.projection);
            inTransaction = true;
            try {
                var result = work.get();
                if (failCommit) {
                    throw new IllegalStateException("fixture rollback");
                }
                return result;
            } catch (RuntimeException failure) {
                stores.projection.clear();
                stores.projection.putAll(prior);
                throw failure;
            } finally {
                inTransaction = false;
            }
        }
    }, new PolicyGuardStore() {
        @Override public void insert(PolicyGuard guard) { throw new AssertionError(); }
        @Override public Optional<PolicyGuard> find(OrganizationId organization) { throw new AssertionError(); }
        @Override public Optional<PolicyGuard> lockShared(OrganizationId organization) { return locked(organization); }
        @Override public Optional<PolicyGuard> lockExclusive(OrganizationId organization) { return locked(organization); }
        private Optional<PolicyGuard> locked(OrganizationId organization) {
            assertTrue(inTransaction);
            assertEquals(ORG, organization);
            return Optional.of(guard);
        }
    }, (actor, guard, scope, operation) -> actor.equals(ACTOR) && grants.contains(operation)
            ? Optional.of(new Authorization(Set.of(RequestType.HR_TICKET), requirePublication)) : Optional.empty(),
            guard -> new Admission(new Genesis(ORG, 1, "a".repeat(64)), writer),
            (guard, target) -> {
                assertTrue(inTransaction);
                createReads++;
                return Optional.ofNullable(create.get(target));
            },
            (guard, ref) -> Optional.ofNullable(publication.get(ref)), stores, stores, binding -> {
                effectReads++;
                return originalEffects.contains(binding);
            });

    @Test
    void missingOrRolledBackCreateLeavesNoMappingEvidence() {
        assertEquals(Outcome.FENCED, bind(A));
        assertEquals(Outcome.FENCED, bind(A));
        assertEquals(0, stores.entries.size());
        assertTrue(stores.projection.isEmpty());
        assertTrue(stores.quarantine.isEmpty());
        create.put(A, A_ORDER);
        assertEquals(Outcome.PROJECTED, bind(A));
        assertEquals(A, mapped().orElseThrow().target());
    }

    @Test
    void deniedScopeAndTypeStopBeforeEvidenceAndRegistry() {
        create.put(A, A_ORDER);
        assertEquals(Outcome.FENCED, mappings.bind(OTHER, KEY, A, Optional.empty()));
        var wrongType = new Target(ORG, RequestType.BUG_REPORT, A.ref());
        var wrongOrg = new Target(new OrganizationId("other-org"), RequestType.HR_TICKET, A.ref());
        assertEquals(Outcome.FENCED, mappings.bind(ACTOR, KEY, wrongType, Optional.empty()));
        assertEquals(Outcome.FENCED, mappings.bind(ACTOR, KEY, wrongOrg, Optional.empty()));
        assertEquals(0, createReads);
        assertTrue(stores.entries.isEmpty());
    }

    @Test
    void requiresExactConfirmedPublicationWhenConfiguredOrSupplied() {
        create.put(A, A_ORDER);
        requirePublication = true;
        assertEquals(Outcome.FENCED, bind(A));
        assertEquals(Outcome.FENCED, mappings.bind(ACTOR, KEY, A, Optional.of(PUBLISHED)));
        publication.put(A.ref(), PUBLISHED.id());
        var wrongNumber = new Publication(PUBLISHED.namespace(), new PublicationId(Long.MAX_VALUE - 1));
        var wrongNamespace = new Publication("other-namespace", PUBLISHED.id());
        assertEquals(Outcome.FENCED, mappings.bind(ACTOR, KEY, A, Optional.of(wrongNumber)));
        assertEquals(Outcome.FENCED, mappings.bind(ACTOR, KEY, A, Optional.of(wrongNamespace)));
        assertTrue(stores.entries.isEmpty());
        assertEquals(Outcome.PROJECTED, mappings.bind(ACTOR, KEY, A, Optional.of(PUBLISHED)));
        assertEquals(Optional.of(PUBLISHED), mapped().orElseThrow().publication());
        publication.clear();
        assertTrue(mapped().isEmpty());
    }

    @Test
    void exactRepeatReconcilesAndCommittedConflictQuarantinesWithoutRebinding() {
        create.put(A, A_ORDER);
        assertEquals(Outcome.PROJECTED, bind(A));
        assertEquals(Outcome.RECONCILED, bind(A));
        var original = stores.entries.get(KEY);
        assertEquals(Outcome.FENCED, bind(B));
        assertTrue(stores.quarantine.isEmpty());
        create.put(B, B_ORDER);
        assertEquals(Outcome.QUARANTINED, bind(B));
        assertEquals(Outcome.QUARANTINED, bind(B));
        assertEquals(original, stores.entries.get(KEY));
        assertEquals(1, stores.quarantine.size());
        assertEquals(1, stores.projection.size());
        assertTrue(mapped().isEmpty());
    }

    @Test
    void projectionRollbackKeepsReservationAndSameCertifiedWriterCanReconcile() {
        create.put(A, A_ORDER);
        failCommit = true;
        assertThrows(IllegalStateException.class, () -> bind(A));
        assertEquals(1, stores.entries.size());
        assertTrue(stores.projection.isEmpty());
        failCommit = false;
        assertTrue(mapped().isEmpty());
        assertEquals(Outcome.RECONCILED, bind(A));
        assertEquals(1, stores.entries.size());
        assertEquals(1, stores.projection.size());
    }

    @Test
    void oldOrMissingProjectionReopensOnlyFromTheExactOriginalEffect() {
        create.put(A, A_ORDER);
        assertEquals(Outcome.PROJECTED, bind(A));
        var original = stores.entries.get(KEY);
        var different = new Binding(UUID.randomUUID(), KEY, original.association(), original.writer());
        stores.projection.put(KEY, different);
        writer = new WriterEpoch(UUID.randomUUID(), 2);
        assertTrue(mapped().isEmpty());
        assertEquals(Outcome.FENCED, bind(A));
        assertEquals(Outcome.FENCED, mappings.recover(ACTOR, KEY));
        assertEquals(0, effectReads);
        stores.projection.clear();
        originalEffects.add(different);
        assertEquals(Outcome.FENCED, bind(A));
        assertEquals(Outcome.FENCED, mappings.recover(ACTOR, KEY));
        assertTrue(mapped().isEmpty());
        originalEffects.add(original);
        assertEquals(Outcome.PROJECTED, mappings.recover(ACTOR, KEY));
        assertEquals(Outcome.RECONCILED, mappings.recover(ACTOR, KEY));
        assertEquals(original.association(), mapped().orElseThrow());
        create.put(A, B_ORDER);
        assertTrue(mapped().isEmpty());
    }

    @Test
    void verifiedMappingChecksItsOwnGrantBeforeGuardedBusinessUse() {
        create.put(A, A_ORDER);
        assertEquals(Outcome.PROJECTED, bind(A));
        grants.remove(Operation.USE);
        assertTrue(mappings.withVerifiedMapping(ACTOR, KEY, association -> {
            throw new AssertionError("unauthorized callback");
        }).isEmpty());
        grants.add(Operation.USE);
        assertEquals(Optional.of(A.ref()), mappings.withVerifiedMapping(ACTOR, KEY, association -> {
            assertTrue(inTransaction);
            return association.target().ref();
        }));
        assertFalse(inTransaction);
    }

    @Test
    void byteValuesAreExactOwnedAndScoped() {
        byte[] bytes = {0, -1, 65};
        var equal = new Key(SCOPE, bytes);
        bytes[0] = 1;
        equal.externalId()[1] = 0;
        assertEquals(KEY, equal);
        assertEquals(KEY.hashCode(), equal.hashCode());
        assertArrayEquals(new byte[]{0, -1, 65}, equal.externalId());
        assertNotEquals(KEY, new Key(SCOPE, new byte[]{0, -1, 97}));
        assertNotEquals(KEY, new Key(new Scope("synthetic-source", "elsewhere", "local"), KEY.externalId()));
        assertNotEquals(KEY, new Key(new Scope("synthetic-source", "synthetic-tenant", "publication"), KEY.externalId()));
        assertNotEquals(KEY, new Key(new Scope("different-source", "synthetic-tenant", "local"), KEY.externalId()));
    }

    private Outcome bind(Target target) {
        return mappings.bind(ACTOR, KEY, target, Optional.empty());
    }

    private Optional<Association> mapped() {
        return mappings.withVerifiedMapping(ACTOR, KEY, value -> value);
    }

    private static final class Stores implements ExternalMappings.Registry, ExternalMappings.Projection {
        final Map<Key, Binding> entries = new HashMap<>();
        final Set<Key> quarantine = new HashSet<>();
        final Map<Key, Binding> projection = new HashMap<>();

        @Override
        public Reservation reserve(Key key, Association association, WriterEpoch writer) {
            var submitted = new Binding(UUID.randomUUID(), key, association, writer);
            var original = entries.putIfAbsent(key, submitted);
            if (original == null) {
                return new Reservation(Reservation.State.RESERVED, Optional.of(submitted));
            }
            if (!original.association().equals(association)) {
                quarantine.add(key);
            }
            return quarantine.contains(key) ? new Reservation(Reservation.State.QUARANTINED, Optional.empty())
                    : new Reservation(Reservation.State.EQUIVALENT, Optional.of(original));
        }

        @Override
        public Optional<Binding> find(Key key) {
            return quarantine.contains(key) ? Optional.empty() : Optional.ofNullable(entries.get(key));
        }

        @Override
        public Optional<Binding> read(PolicyGuard guard, Key key) {
            return Optional.ofNullable(projection.get(key));
        }

        @Override
        public void save(PolicyGuard guard, Binding binding, boolean original) {
            assertEquals(null, projection.putIfAbsent(binding.key(), binding));
        }
    }
}
