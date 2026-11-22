package com.company.requests.integration;

import static com.company.requests.integration.InboundCoordinator.Outcome.ACKNOWLEDGED;
import static com.company.requests.integration.InboundCoordinator.Outcome.REJECTED;
import static com.company.requests.integration.InboundCoordinator.Outcome.UNAVAILABLE;
import static com.company.requests.integration.InboundNotice.Hint.CHANGE;
import static com.company.requests.integration.InboundNotice.Hint.CORRECTION;
import static com.company.requests.integration.InboundNotice.Type.DETAILS_CHANGED;
import static com.company.requests.integration.InboundNotice.Type.STATUS_CHANGED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PolicyGuardStore;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthority.Genesis;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.InboundInbox.Key;
import com.company.requests.integration.InboundInbox.Processing;
import com.company.requests.integration.InboundInbox.Receipt;
import com.company.requests.integration.InboundInbox.Scope;
import com.company.requests.integration.InboundNotice.Hint;
import com.company.requests.integration.InboundNotice.Identity;
import com.company.requests.integration.InboundNotice.Type;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class InboundCoordinatorTest {
    private static final OrganizationId ORG = new OrganizationId("fixture-org");
    private static final OrganizationId SECOND_ORG = new OrganizationId("fixture-second-org");
    private static final AuthenticatedActor SERVICE = new AuthenticatedActor("inbound-fixture", ORG,
            "fixture-service", AuthenticatedActor.Kind.SERVICE, Optional.empty());
    private static final AuthenticatedActor SECOND_SERVICE = new AuthenticatedActor("inbound-fixture", SECOND_ORG,
            "fixture-service", AuthenticatedActor.Kind.SERVICE, Optional.empty());
    private static final PolicyGuard GUARD = new PolicyGuard(ORG, 1);
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 1);
    private static final Target TARGET = new Target(ORG, RequestType.HR_TICKET, new RequestRef("A".repeat(32)));
    private static final Target OTHER_TARGET = new Target(ORG, RequestType.HR_TICKET, new RequestRef("B".repeat(32)));
    private static final Target CROSS_ORG_TARGET =
            new Target(SECOND_ORG, RequestType.HR_TICKET, new RequestRef("C".repeat(32)));
    private static final Identity MAP = new Identity(new byte[]{(byte) 255, 0, 'A'});
    private static final Identity OTHER_MAP = new Identity(new byte[]{(byte) 255, 0, 'a'});
    private static final Identity ID = new Identity(new byte[]{0, (byte) 254, 'A'});
    private static final Identity OTHER_ID = new Identity(new byte[]{0, (byte) 254, 'a'});
    private static final Scope A = new Scope("fixture-source", "fixture-tenant", "domain-a");
    private static final Scope B = new Scope("fixture-source", "fixture-tenant", "domain-b");
    private static final byte[] KEY_A = HexFormat.of().parseHex("31".repeat(32));
    private static final byte[] KEY_B = HexFormat.of().parseHex("42".repeat(32));

    private final Workflow workflow = new Workflow();
    private final Set<Scope> admitted = new HashSet<>(Set.of(A, B));
    private long targetRevision = 3;
    private boolean mappingAllowed = true;
    private boolean targetAllowed = true;
    private WriterEpoch currentWriter = WRITER;
    private final SyntheticInboundSources source = new SyntheticInboundSources(List.of(
            route("route-a", KEY_A, A), route("route-b", KEY_B, B)));
    private final InboundCoordinator inbound = new InboundCoordinator(source, workflow, workflow,
            guard -> new Admission(new Genesis(ORG, 1, "a".repeat(64)), currentWriter),
            (guard, admission, scope) -> WRITER.equals(admission.writer()) && admitted.contains(scope),
            (actor, guard, key) -> {
                assertTrue(workflow.inTransaction);
                assertEquals(SERVICE, actor);
                assertEquals(GUARD, guard);
                assertEquals(new ExternalMapping.Scope(A.sourceSystem(), A.tenant(), "record"), key.scope());
                return mappingAllowed ? Optional.of(new Identity(key.externalId()).equals(MAP) ? TARGET : OTHER_TARGET)
                        : Optional.empty();
            },
            (actor, guard, target) -> targetAllowed ? OptionalLong.of(targetRevision) : OptionalLong.empty(), workflow);

    @Test
    void receiptAndObligationCommitBeforeAckAndExactRetryWritesNothing() throws Exception {
        var notice = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        assertEquals(ACKNOWLEDGED, sendA(notice));
        assertFalse(workflow.inTransaction);
        assertEquals(1, workflow.receipts.size());
        assertEquals(1, workflow.obligations.size());
        assertEquals(1, workflow.acceptWrites);
        assertEquals(MAP, workflow.obligations.get(new Key(A, ID)).externalId());
        targetRevision++;
        assertEquals(ACKNOWLEDGED, sendA(notice));
        assertEquals(1, workflow.acceptWrites);
        assertEquals(0, workflow.quarantineWrites);
    }

    @Test
    void authenticatesCoordinatesAndEntireEnvelopeBeforeUsingWorkflow() throws Exception {
        var notice = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        byte[] proof = sign("route-a", notice, KEY_A);
        var wrongTenant = new InboundNotice(A.sourceSystem(), "another-tenant", ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        var wrongSource = new InboundNotice("another-source", A.tenant(), ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        assertEquals(REJECTED, inbound.receive("unknown", notice, proof));
        assertEquals(REJECTED, inbound.receive("route-b", notice, proof));
        assertEquals(REJECTED, inbound.receive("route-a", wrongTenant, sign("route-a", wrongTenant, KEY_A)));
        assertEquals(REJECTED, inbound.receive("route-a", wrongSource, sign("route-a", wrongSource, KEY_A)));
        assertEquals(REJECTED, inbound.receive("route-a", notice, null));
        for (var altered : List.of(notice(OTHER_ID, STATUS_CHANGED, MAP, 7, 3, CHANGE),
                notice(ID, DETAILS_CHANGED, MAP, 7, 3, CHANGE), notice(ID, STATUS_CHANGED, OTHER_MAP, 7, 3, CHANGE),
                notice(ID, STATUS_CHANGED, MAP, 8, 3, CHANGE), notice(ID, STATUS_CHANGED, MAP, 7, 4, CHANGE),
                notice(ID, STATUS_CHANGED, MAP, 7, 3, CORRECTION))) {
            assertEquals(REJECTED, inbound.receive("route-a", altered, proof));
        }
        assertEquals(0, workflow.transactions);
    }

    @Test
    void validatesSourceConfigurationAndDefensivelyCopiesSecretsAndIdentities() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new SyntheticInboundSources(List.of(
                route("route-a", KEY_A, A), route("route-a", KEY_B, B))));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticInboundSources(List.of(
                route("route-a", KEY_A, A), route("route-b", KEY_A, B))));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticInboundSources(List.of(
                route("route-a", KEY_A, A), route("route-b", KEY_B, A))));
        assertThrows(IllegalArgumentException.class, () -> route("route-a", new byte[16], A));
        var secret = KEY_A.clone();
        var configured = route("route-a", secret, A);
        secret[0]++;
        configured.secret()[0]++;
        var verifier = new SyntheticInboundSources(List.of(configured));
        var notice = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        assertTrue(verifier.verify("route-a", notice, sign("route-a", notice, KEY_A)).isPresent());
        var bytes = new byte[]{1, 2, 3};
        var exact = new Identity(bytes);
        bytes[0]++;
        exact.bytes()[0]++;
        assertEquals(new Identity(new byte[]{1, 2, 3}), exact);
    }

    @Test
    void conflictsPreserveOriginalAndSeparateDomainsAndBinaryIdentitiesRemainDistinct() throws Exception {
        var first = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        assertEquals(ACKNOWLEDGED, sendA(first));
        var original = workflow.receipts.get(new Key(A, ID));
        for (var conflict : List.of(notice(ID, DETAILS_CHANGED, MAP, 7, 3, CHANGE),
                notice(ID, STATUS_CHANGED, MAP, 7, 3, CORRECTION), notice(ID, STATUS_CHANGED, OTHER_MAP, 7, 3, CHANGE),
                notice(ID, STATUS_CHANGED, MAP, 8, 3, CHANGE), notice(ID, STATUS_CHANGED, MAP, 7, 4, CHANGE))) {
            assertEquals(REJECTED, sendA(conflict));
            assertEquals(REJECTED, sendA(conflict));
        }
        assertEquals(5, workflow.discrepancies.size());
        assertEquals(original, workflow.receipts.get(new Key(A, ID)));
        assertEquals(original, workflow.obligations.get(new Key(A, ID)).receipt());
        var distinct = notice(ID, DETAILS_CHANGED, MAP, 7, 3, CORRECTION);
        assertEquals(ACKNOWLEDGED, inbound.receive("route-b", distinct, sign("route-b", distinct, KEY_B)));
        assertEquals(ACKNOWLEDGED, sendA(notice(OTHER_ID, STATUS_CHANGED, MAP, 9, 3, CHANGE)));
        assertEquals(3, workflow.receipts.size());
        assertEquals(3, workflow.obligations.size());
    }

    @Test
    void authorizedCrossOrganizationConflictQuarantinesAgainstTheFullSourceKey() throws Exception {
        var first = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        assertEquals(ACKNOWLEDGED, sendA(first));
        var original = workflow.receipts.get(new Key(A, ID));
        var cross = notice(ID, STATUS_CHANGED, OTHER_MAP, 7, 3, CHANGE);
        var proof = sign("route-a", cross, KEY_A);
        var second = receiverInSecondOrganization();
        int reads = workflow.reads;
        targetAllowed = false;
        assertEquals(REJECTED, second.receive("route-a", cross, proof));
        targetAllowed = true;
        mappingAllowed = false;
        assertEquals(REJECTED, second.receive("route-a", cross, proof));
        mappingAllowed = true;
        assertEquals(reads, workflow.reads);
        assertEquals(0, workflow.quarantineWrites);

        assertEquals(REJECTED, second.receive("route-a", cross, proof));
        assertEquals(REJECTED, second.receive("route-a", cross, proof));
        assertEquals(1, workflow.discrepancies.size());
        assertEquals(CROSS_ORG_TARGET, workflow.discrepancies.iterator().next().target());
        assertEquals(original, workflow.receipts.get(new Key(A, ID)));
        assertEquals(original, workflow.obligations.get(new Key(A, ID)).receipt());
        assertEquals(1, workflow.acceptWrites);
        assertEquals(ACKNOWLEDGED, sendA(first));
    }

    private InboundCoordinator receiverInSecondOrganization() {
        var source = new SyntheticInboundSources(List.of(
                new SyntheticInboundSources.Route("route-a", KEY_A, A, "record", SECOND_SERVICE)));
        return new InboundCoordinator(source, workflow, workflow,
                guard -> new Admission(new Genesis(SECOND_ORG, 1, "b".repeat(64)), WRITER),
                (guard, admission, scope) -> WRITER.equals(admission.writer()) && scope.equals(A),
                (actor, guard, key) -> mappingAllowed ? Optional.of(CROSS_ORG_TARGET) : Optional.empty(),
                (actor, guard, target) -> targetAllowed ? OptionalLong.of(targetRevision) : OptionalLong.empty(), workflow);
    }

    @Test
    void closesUnprovedScopeBeforeLedgerAndChecksCurrentMappingTargetAndFreshVersions() throws Exception {
        var first = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        admitted.remove(A);
        assertEquals(UNAVAILABLE, sendA(first));
        assertEquals(0, workflow.reads);
        assertEquals(ACKNOWLEDGED, inbound.receive("route-b", first, sign("route-b", first, KEY_B)));
        currentWriter = new WriterEpoch(UUID.randomUUID(), 2);
        assertEquals(UNAVAILABLE, inbound.receive("route-b", first, sign("route-b", first, KEY_B)));
        currentWriter = WRITER;
        admitted.add(A);
        mappingAllowed = false;
        assertEquals(REJECTED, sendA(first));
        mappingAllowed = true;
        targetAllowed = false;
        assertEquals(REJECTED, sendA(first));
        targetAllowed = true;
        assertEquals(1, workflow.reads);
        assertEquals(ACKNOWLEDGED, sendA(first));
        assertEquals(REJECTED, sendA(notice(OTHER_ID, STATUS_CHANGED, MAP, 6, 3, CHANGE)));
        assertEquals(REJECTED, sendA(notice(OTHER_ID, DETAILS_CHANGED, MAP, 7, 3, CHANGE)));
        assertEquals(REJECTED, sendA(notice(OTHER_ID, STATUS_CHANGED, MAP, 8, 2, CHANGE)));
        assertEquals(REJECTED, sendA(notice(OTHER_ID, STATUS_CHANGED, MAP, 8, 4, CHANGE)));
        assertEquals(2, workflow.receipts.size());
        assertEquals(2, workflow.obligations.size());
        assertTrue(workflow.discrepancies.isEmpty());
    }

    @Test
    void unknownCommitNeverAcksAndRetriesResolveAgainstDurableState() throws Exception {
        var notice = notice(ID, STATUS_CHANGED, MAP, 7, 3, CHANGE);
        workflow.failCommit = true;
        assertEquals(UNAVAILABLE, sendA(notice));
        assertTrue(workflow.receipts.isEmpty());
        assertTrue(workflow.obligations.isEmpty());
        workflow.failCommit = false;
        workflow.loseCommitResponse = true;
        assertEquals(UNAVAILABLE, sendA(notice));
        assertEquals(1, workflow.receipts.size());
        assertEquals(1, workflow.obligations.size());
        workflow.loseCommitResponse = false;
        assertEquals(ACKNOWLEDGED, sendA(notice));
        assertEquals(2, workflow.acceptWrites);
    }

    private InboundCoordinator.Outcome sendA(InboundNotice notice) throws GeneralSecurityException {
        return inbound.receive("route-a", notice, sign("route-a", notice, KEY_A));
    }

    private static InboundNotice notice(Identity sourceId, Type type, Identity mapping, long version,
                                        long revision, Hint hint) {
        return new InboundNotice(A.sourceSystem(), A.tenant(), sourceId, type, mapping, version, revision, hint);
    }

    private static SyntheticInboundSources.Route route(String credential, byte[] secret, Scope scope) {
        return new SyntheticInboundSources.Route(credential, secret, scope, "record", SERVICE);
    }

    private static byte[] sign(String credential, InboundNotice notice, byte[] key) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(SyntheticInboundSources.signingBytes(credential, notice));
    }

    private static final class Workflow implements TransactionRunner, PolicyGuardStore, InboundInbox {
        private final Map<Key, Receipt> receipts = new HashMap<>();
        private final Map<Key, Processing> obligations = new HashMap<>();
        private final Set<Receipt> discrepancies = new HashSet<>();
        private boolean inTransaction;
        private boolean failCommit;
        private boolean loseCommitResponse;
        private int acceptWrites;
        private int quarantineWrites;
        private int transactions;
        private int reads;

        @Override
        public <T> T run(Supplier<T> work) {
            assertFalse(inTransaction);
            var oldReceipts = Map.copyOf(receipts);
            var oldObligations = Map.copyOf(obligations);
            var oldDiscrepancies = Set.copyOf(discrepancies);
            transactions++;
            inTransaction = true;
            try {
                var outcome = work.get();
                if (failCommit) {
                    receipts.clear();
                    receipts.putAll(oldReceipts);
                    obligations.clear();
                    obligations.putAll(oldObligations);
                    discrepancies.clear();
                    discrepancies.addAll(oldDiscrepancies);
                }
                if (failCommit || loseCommitResponse) {
                    throw new CommitOutcomeUnknownException(new IllegalStateException("fixture commit failure"));
                }
                return outcome;
            } finally {
                inTransaction = false;
            }
        }

        @Override public void insert(PolicyGuard guard) { throw new AssertionError(); }
        @Override public Optional<PolicyGuard> find(OrganizationId organization) { throw new AssertionError(); }
        @Override public Optional<PolicyGuard> lockShared(OrganizationId organization) { throw new AssertionError(); }

        @Override
        public Optional<PolicyGuard> lockExclusive(OrganizationId organization) {
            assertTrue(inTransaction);
            assertTrue(ORG.equals(organization) || SECOND_ORG.equals(organization));
            return Optional.of(new PolicyGuard(organization, 1));
        }

        @Override
        public Prior classifyAndLock(PolicyGuard guard, Receipt submitted) {
            assertTrue(inTransaction);
            assertEquals(guard.organization(), submitted.target().organization());
            reads++;
            var original = receipts.get(submitted.key());
            if (original == null) {
                return Prior.ABSENT;
            }
            return original.equals(submitted) ? Prior.EXACT : Prior.CONFLICT;
        }

        @Override
        public long lastSourceVersion(PolicyGuard guard, Scope scope, Target target) {
            assertTrue(inTransaction);
            return receipts.values().stream()
                    .filter(receipt -> scope.equals(receipt.key().scope()) && target.equals(receipt.target()))
                    .mapToLong(Receipt::sourceVersion).max().orElse(0);
        }

        @Override
        public void accept(PolicyGuard guard, Processing processing) {
            assertTrue(inTransaction);
            acceptWrites++;
            var receipt = processing.receipt();
            if (receipts.putIfAbsent(receipt.key(), receipt) != null) {
                throw new AuthorityUnavailableException();
            }
            obligations.put(receipt.key(), processing);
        }

        @Override
        public void quarantine(PolicyGuard guard, Receipt discrepancy) {
            assertTrue(inTransaction);
            quarantineWrites++;
            discrepancies.add(discrepancy);
        }
    }
}
