package com.company.requests.bootstrap;

import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.application.ActionContextService;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.BrowseCursorCodec;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CreateStore;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeChange;
import com.company.requests.application.EffectiveScopeGate;
import com.company.requests.application.EffectiveScopeStore;
import com.company.requests.application.GuardedRelease;
import com.company.requests.application.IdentityAuthority;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PolicyGuardStore;
import com.company.requests.application.PublicationConfirmationStore;
import com.company.requests.application.PublicationJournal;
import com.company.requests.application.PublicationProof;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.ReceiptStore;
import com.company.requests.application.ReleasePreparation;
import com.company.requests.application.RequestActionCommand;
import com.company.requests.application.RequestBrowseQuery;
import com.company.requests.application.RequestBrowseStore.Candidate;
import com.company.requests.application.RequestDetailQuery;
import com.company.requests.application.RequestHistoryQuery;
import com.company.requests.application.RequestStore;
import com.company.requests.application.ScopeAuthority;
import com.company.requests.application.ScopeAuthorityChange;
import com.company.requests.application.ScopeCheckpoint;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.Action;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The real synthetic Spring composition, with in-process ports for contract examples. */
final class OpenApiMvcFixture implements AutoCloseable {
    static final String FULL_BEARER = "Bearer " + "A".repeat(43);
    static final String MASKED_BEARER = "Bearer " + "B" + "A".repeat(42);
    static final String CREATED_REF = "ABc123_45-aBc123ABc123_45-aBc123";
    static final String ORIGINAL_BODY = "  Treść العربية 😀  ";
    static final String ORIGINAL_NAME = "  Zażółć gęślą  ";
    static final String REASON = "  Powód  ";
    static final long PRECISE_REVISION = 9_007_199_254_740_993L;

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final OrganizationId ORG = new OrganizationId("contract-example");
    private static final PersonId ASSIGNEE = new PersonId("assignee");
    private static final PersonId ORIGINATOR = new PersonId("original-requester");
    private static final UUID WRITER_ID = UUID.fromString("a6b8bb7d-1aa2-4d44-8cd2-cdc88837db78");
    private static final WriterEpoch WRITER = new WriterEpoch(WRITER_ID, 1);
    private static final RequestType TYPE = RequestType.HR_TICKET;
    private static final PublicationId PUBLICATION = new PublicationId(Long.MAX_VALUE);

    private final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
    private final Stores stores = new Stores();
    private final IntentDigest digests;
    private final OperationKeyIssuer keys;
    private final ScopeAuthority.Admission admission;
    private boolean authorityAvailable = true;
    private boolean publicationConfirmed = true;
    private boolean maskedReaderMayReadPublication;
    private Instant clock = NOW;
    private ReceiptKey publicationKey;

    final MockMvc mvc;
    int transactionEntries;
    int createEffects;

    OpenApiMvcFixture() {
        var full = actor("full", ASSIGNEE);
        var masked = actor("masked", new PersonId("read-only"));
        var actors = new SyntheticActorResolver("contract-issuer", "requests", List.of(
                new SyntheticActorResolver.Binding("full-subject", FULL_BEARER.substring(7), full),
                new SyntheticActorResolver.Binding("masked-subject", MASKED_BEARER.substring(7), masked)));
        var policy = new SyntheticScopePolicy(ORG, 1, List.of(
                new SyntheticScopePolicy.Grant(full, true, Set.of(TYPE), Set.of(TYPE)),
                new SyntheticScopePolicy.Grant(masked, false, Set.of(), Set.of())));
        admission = new ScopeAuthority.Admission(policy.genesis(), WRITER);
        var secrets = new OperationSecrets(1, Map.of(1, secret(1)), 1, Map.of(1, secret(2)));
        digests = new IntentDigest(secrets);
        var gate = new EffectiveScopeGate(admission -> {
            if (!authorityAvailable) {
                throw new AuthorityUnavailableException();
            }
            return new ScopeCheckpoint(admission, 0, 1, "0".repeat(64));
        }, ignored -> { }, stores);
        var access = CurrentAccess.withEffectiveScope(WRITER, List.of(policy), stores, stores, stores, stores,
                gate, Optional.of(new PublicationProof(stores, stores)));
        var release = new GuardedRelease(stores, stores, stores, access, Duration.ofMillis(2400));
        keys = new OperationKeyIssuer(access, () -> clock, secrets, OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        ReleasePreparation preparation = (admission, receipt) -> { };
        var creates = new CreateCommand(stores, stores, stores, stores, stores, access, ignored -> { },
                (admission, attempt) -> new IdentityAuthority.Reservation(new RequestRef(CREATED_REF),
                        new CreationOrder(1, 1)), keys, digests, preparation, release);
        var observation = new ObservationCodec.Keys(1, Map.of(1, secret(3)));
        var contexts = new ActionContextService(access, stores, stores, release,
                new ObservationCodec(observation, secrets), () -> clock);
        // Existing command examples exercise exact committed replay. A new action write fails the fixture.
        var actions = new RequestActionCommand(stores, access, ignored -> { }, keys, digests, stores, stores,
                stores, null, contexts, policy, stores, null, release, preparation, OriginalReceiptProof.unavailable());
        var browse = new RequestBrowseQuery(access, (guard, below, limit) -> stores.rows.values().stream()
                .filter(row -> below.map(bound -> row.creationOrder().ordinal() < bound.order().ordinal()).orElse(true))
                .sorted(Comparator.comparingLong((Request row) -> row.creationOrder().ordinal()).reversed())
                .map(row -> new Candidate(row.creationOrder(), row.ref())).limit(limit).toList(), release,
                new BrowseCursorCodec(new BrowseCursorCodec.Keys(1, Map.of(1, secret(4))), secrets,
                        Optional.of(observation)), () -> clock);
        var settings = new SyntheticEffectiveAccessConfiguration.Settings(
                new SyntheticCreateConfiguration.Settings(unusedDataSource(), unusedDataSource(), policy,
                        WRITER, actors, () -> clock, secrets),
                unusedDataSource(), unusedDataSource(), unusedDataSource(), unusedDataSource(), Map.of());
        var components = new SyntheticEffectiveAccessConfiguration.Components(access, creates, null, null,
                Optional.of(contexts), Optional.of(actions), Optional.empty(), new RequestDetailQuery(access, stores, release),
                new RequestHistoryQuery(access, stores, release), Optional.of(browse));
        seedRows();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("syntheticSettings", settings);
            factory.registerSingleton("effectiveScopeComponents", components);
        });
        context.register(SyntheticEffectiveAccessConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    AnnotationConfigWebApplicationContext context() {
        return context;
    }

    String observationBeforeCommit(Action action) throws Exception {
        String actionName = action.name().toLowerCase(Locale.ROOT);
        var ref = new RequestRef(actionName + "-ref");
        var committed = stores.rows.get(ref);
        var previous = switch (action) {
            case EDIT, VERIFY, DELETE -> RequestState.CREATED;
            case ACCEPT, REJECT -> RequestState.VERIFIED;
            case PUBLISH -> RequestState.ACCEPTED;
            case CREATE -> throw new IllegalArgumentException("Create has no observation");
        };
        long previousSequence = action == Action.EDIT ? committed.lastSequence() : committed.lastSequence() - 1;
        stores.rows.put(ref, new Request(ref, committed.type(), ORG, committed.name(), previous,
                committed.revision() - 1, previousSequence, committed.creationOrder(), ORIGINATOR, Optional.empty()));
        try {
            var response = mvc.perform(get("/requests/{ref}/action-context", ref.value())
                    .queryParam("action", actionName).header("Authorization", FULL_BEARER)).andReturn().getResponse();
            assertEquals(200, response.getStatus(), "An exact replay starts with an issued observation");
            return OpenApiWire.JSON.readTree(response.getContentAsByteArray()).required("expectedRevision").asString();
        } finally {
            stores.rows.put(ref, committed);
        }
    }

    void seedCommittedReplay(Action action, String signedKey, String observation) {
        String actionName = action.name().toLowerCase(Locale.ROOT);
        var ref = new RequestRef(actionName + "-ref");
        IntentDigest.Intent intent = switch (action) {
            case EDIT -> new IntentDigest.Edit(ref, ORIGINAL_BODY, observation);
            case DELETE, REJECT -> new IntentDigest.Transition(action, ref, Optional.of(REASON), observation);
            default -> new IntentDigest.Transition(action, ref, Optional.empty(), observation);
        };
        var verified = keys.verify(signedKey, actor("full", ASSIGNEE), admission);
        var row = stores.rows.get(ref);
        var receipt = new OperationReceipt(verified.receiptKey(), digests.calculate(intent), verified.generation(),
                verified.expiresAt(), action, ref, row.revision(), row.state(), row.publicationId(), 200);
        if (action == Action.PUBLISH) {
            publicationKey = verified.receiptKey();
        }
        stores.receipts.put(receipt.key(), receipt);
    }

    void expireKey() {
        clock = NOW.plus(Duration.ofHours(24));
    }

    void removeAuthority() {
        authorityAvailable = false;
    }

    void removePublicationConfirmation() {
        publicationConfirmed = false;
    }

    void grantMaskedReaderPublicationId() {
        maskedReaderMayReadPublication = true;
    }

    private void seedRows() {
        putRow("edit-ref", RequestState.CREATED, 1, 2);
        putRow("verify-ref", RequestState.VERIFIED, 2, 3);
        putRow("accept-ref", RequestState.ACCEPTED, 3, 4);
        putRow("reject-ref", RequestState.REJECTED, 3, 5);
        putRow("delete-ref", RequestState.DELETED, 2, 6);
        putRow("publish-ref", RequestState.PUBLISHED, 4, 7);
    }

    private void putRow(String id, RequestState state, long sequence, long ordinal) {
        var ref = new RequestRef(id);
        var publication = state == RequestState.PUBLISHED ? Optional.of(PUBLICATION) : Optional.<PublicationId>empty();
        stores.rows.put(ref, new Request(ref, TYPE, ORG, ORIGINAL_NAME, state, PRECISE_REVISION, sequence,
                new CreationOrder(1, ordinal), ORIGINATOR, publication));
        stores.bodies.put(ref, new RequestBody(ORIGINAL_BODY));
    }

    @Override
    public void close() {
        context.close();
    }

    private static AuthenticatedActor actor(String principal, PersonId person) {
        return new AuthenticatedActor("contract-issuer", ORG, principal, AuthenticatedActor.Kind.HUMAN,
                Optional.of(person));
    }

    private static byte[] secret(int purpose) {
        // Deterministic synthetic secrets. The issued tokens remain generated by the real codecs.
        var bytes = new byte[32];
        bytes[0] = (byte) purpose;
        return bytes;
    }

    private static DataSource unusedDataSource() {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("OpenAPI MVC fixture must not open JDBC connections");
            }

            @Override
            public Connection getConnection(String username, String password) {
                return getConnection();
            }
        };
    }

    private final class Stores implements TransactionRunner, RequestStore, PolicyGuardStore, EffectiveScopeStore,
            CreateStore, ReceiptStore, PublicationJournal, PublicationConfirmationStore {
        private final PolicyGuard guard = new PolicyGuard(ORG, 1);
        private final Map<RequestRef, Request> rows = new HashMap<>();
        private final Map<RequestRef, RequestBody> bodies = new HashMap<>();
        private final Map<ReceiptKey, OperationReceipt> receipts = new HashMap<>();

        @Override
        public <T> T run(Supplier<T> work) {
            transactionEntries++;
            return work.get();
        }

        @Override
        public Optional<PolicyGuard> find(OrganizationId organization) {
            return Optional.of(guard).filter(value -> value.organization().equals(organization));
        }

        @Override
        public Optional<PolicyGuard> lockShared(OrganizationId organization) {
            return find(organization);
        }

        @Override
        public Optional<PolicyGuard> lockExclusive(OrganizationId organization) {
            return find(organization);
        }

        @Override
        public Optional<Request> find(OrganizationId organization, RequestRef ref) {
            return Optional.ofNullable(rows.get(ref)).filter(value -> value.organization().equals(organization));
        }

        @Override
        public Optional<RequestBody> findRetainedBody(OrganizationId organization, RequestRef ref) {
            return find(organization, ref).map(row -> bodies.get(row.ref()));
        }

        @Override
        public List<HistoryFact> findHistory(PolicyGuard selected, RequestRef ref) {
            if (!ref.equals(new RequestRef("edit-ref"))) {
                throw unexpected();
            }
            return List.of(new HistoryFact(1, 1, Action.CREATE, null, RequestState.CREATED));
        }

        @Override
        public void requireProjection(PolicyGuard selected, ScopeCheckpoint checkpoint) {
            checkpoint.requireGuard(selected);
        }

        @Override
        public Optional<GrantOverride> grantOverride(PolicyGuard selected, GrantKey key) {
            if (key.actor().principal().equals("masked")) {
                boolean allowed = key.grant() == Grant.ROW_READ
                        || (maskedReaderMayReadPublication && key.grant() == Grant.FIELD_READ_PUBLICATION_ID);
                return Optional.of(new GrantOverride(allowed, 1));
            }
            return Optional.of(new GrantOverride(true, 1));
        }

        @Override
        public Optional<Assignment> assignment(PolicyGuard selected, RequestRef ref) {
            return Optional.of(new Assignment(ASSIGNEE, 1));
        }

        @Override
        public Visibility visibility(PolicyGuard selected, RequestRef ref) {
            return Visibility.INITIAL;
        }

        @Override
        public Optional<OperationReceipt> find(ReceiptKey key) {
            return Optional.ofNullable(receipts.get(key));
        }

        @Override
        public boolean claim(ReceiptKey key, ReceiptIntentDigest digest, long generation, Instant expiry) {
            return !receipts.containsKey(key);
        }

        @Override
        public void finalizeReceipt(OperationReceipt receipt) {
            receipts.put(receipt.key(), receipt);
        }

        @Override
        public void insert(Request request) {
            rows.put(request.ref(), request);
            createEffects++;
        }

        @Override
        public void insertBody(OrganizationId organization, RequestRef ref, RequestBody body) {
            bodies.put(ref, body);
        }

        @Override
        public void insertInitialAssignment(OrganizationId organization, RequestRef ref, PersonId assignee) {
            if (!assignee.equals(ASSIGNEE) || !organization.equals(ORG)) {
                throw unexpected();
            }
        }

        @Override
        public void appendFact(OrganizationId organization, TransitionFact fact) {
            if (fact.action() != Action.CREATE || !organization.equals(ORG)) {
                throw unexpected();
            }
        }

        @Override
        public Optional<Entry> activeBinding(WriterEpoch writer, OrganizationId organization, RequestType type,
                                             RequestRef ref) {
            return find(organization, ref).filter(row -> row.publicationId().isPresent()).map(row -> {
                String identity = publicationKey == null ? "0".repeat(64) : PublicationJournal.receiptIdentity(publicationKey);
                var attempt = new Attempt(WRITER_ID, writer, organization, type, ref, 1, "1", identity, row.revision());
                return new Entry(attempt, row.publicationId(), State.COMMITTED);
            });
        }

        @Override
        public boolean contains(PolicyGuard selected, Entry committed) {
            return publicationConfirmed;
        }

        @Override
        public void record(PolicyGuard selected, Entry committed) {
            throw unexpected();
        }

        @Override
        public void insert(OperationReceipt receipt) {
            throw unexpected();
        }

        @Override
        public void insert(PolicyGuard newGuard) {
            throw unexpected();
        }

        @Override
        public List<TransitionFact> findFacts(OrganizationId organization, RequestRef ref) {
            throw unexpected();
        }

        @Override
        public void apply(PolicyGuard selected, ScopeAuthorityChange.Entry prepared, EffectiveScopeChange change) {
            throw unexpected();
        }

        @Override
        public Entry register(Attempt attempt) {
            throw unexpected();
        }

        @Override
        public Entry reserve(Attempt attempt) {
            throw unexpected();
        }

        @Override
        public Optional<Entry> find(Attempt attempt) {
            throw unexpected();
        }

        @Override
        public Entry confirm(Attempt attempt) {
            throw unexpected();
        }

        @Override
        public Entry abort(Attempt attempt) {
            throw unexpected();
        }

        private AssertionError unexpected() {
            return new AssertionError("Unexpected stateful call in the OpenAPI example fixture");
        }
    }
}
