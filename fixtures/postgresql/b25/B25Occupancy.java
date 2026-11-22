import com.company.requests.adapter.integration.jdbc.DispatchWorkerConnections;
import com.company.requests.adapter.integration.jdbc.JdbcDispatchStore;
import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthority.Genesis;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.integration.DispatchCoordinator.Result;
import com.company.requests.integration.DispatchScheduler;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs the actual scheduler and dispatch entry point against scripted JDBC with no sockets. */
public final class B25Occupancy {
    private static final OrganizationId TENANT = new OrganizationId("b25-occupancy");
    private static final String PRESSURED = "b25-pressure", HEALTHY = "b25-healthy";
    private static final WriterEpoch WRITER = new WriterEpoch(UUID.randomUUID(), 5);
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final UUID PUBLICATION = UUID.randomUUID();

    public static void main(String[] args) throws Exception {
        try (var source = JdbcDispatchStore.class.getResourceAsStream("/db/integration/outbox_scheduling_v1.sql")) {
            require(source != null, "The scheduling migration is missing.");
            var schema = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
            require(schema.contains("CREATE UNIQUE INDEX outbox_scheduled_occupied_slot ON requests.outbox_attempt "
                    + "(destination, tenant, scheduling_slot) WHERE scheduling_invocation IS NOT NULL "
                    + "AND invocation_quiesced_at IS NULL"),
                    "An armed attempt needs a durable, unique destination slot even after session loss.");
            require(schema.contains("(scheduling_invocation IS NOT NULL) = "
                    + "(scheduling_slot IS NOT NULL AND armed_at IS NOT NULL)"),
                    "Pre-scheduling rows and unarmed claims must not manufacture occupied slots.");
        }
        lostSessionsRetainOccupancy();
        uncertainCommits();
        unrecordedCompletion();
        System.out.println("PASS B25 durable slot, exact-owner completion, loss, duplicate, and commit controls.");
    }

    private static void lostSessionsRetainOccupancy() throws Exception {
        var db = new Database();
        var first = db.claim(PRESSURED, 1, 1, 1);
        var second = db.claim(PRESSURED, 2, 1, 2);
        var entered = new CountDownLatch(2);
        var cancelled = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var connector = remote(invocation -> {
            entered.countDown();
            for (;;) {
                try {
                    require(release.await(8, TimeUnit.SECONDS), "Physical call gate was not released.");
                    return;
                } catch (InterruptedException timeout) {
                    cancelled.countDown();
                }
            }
        });
        try (var connections = new DispatchWorkerConnections(db.source());
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var store = db.store(connections);
            Future<Result> a = threads.submit(() -> store.dispatchScheduled(first, WRITER, connector));
            Future<Result> b = threads.submit(() -> store.dispatchScheduled(second, WRITER, connector));
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    if (a.isDone()) a.get();
                    if (b.isDone()) b.get();
                    throw new AssertionError("Both exact slots must be armed before entering I/O.");
                }
                require(db.open(first) && db.open(second), "No connector entry without a committed occupied slot.");
                db.loseAndExpire(first, "ARMED");
                db.loseAndExpire(second, "UNKNOWN");
                require(cancelled.await(5, TimeUnit.SECONDS), "The logical waits did not hit their deadlines.");
                db.staleClaimRead.set(first);
                var duplicateSends = new AtomicInteger();
                var doubt = store.dispatchScheduled(first, WRITER,
                        remote(invocation -> duplicateSends.incrementAndGet()));
                require(doubt == Result.STALE && duplicateSends.get() == 0 && db.open(first) && db.open(second),
                        "Duplicate finally cleared another invocation's slot.");
                var batch = store.claimScheduledBatch(WRITER);
                require(batch.size() == 1 && batch.getFirst().id().effect().destination().equals(HEALTHY),
                        "Lost sessions let a third invocation occupy the same route or blocked unrelated work.");
                require(!a.isDone() && !b.isDone(), "Timeout acknowledged before the connector actually stopped.");
                db.row(first).state = "UNKNOWN";
                db.activeWriter.set(new WriterEpoch(UUID.randomUUID(), WRITER.generation() + 1));
            } finally {
                release.countDown();
            }
            expectFailure(a);
            expectFailure(b);
            require(!db.open(first) && !db.open(second), "New sessions did not record actual quiescence.");
            require(db.row(first).state.equals("UNKNOWN") && db.row(second).state.equals("UNKNOWN"),
                    "Physical completion changed the unknown business outcome.");
            require(store.claimScheduledBatch(WRITER).stream()
                    .anyMatch(claim -> claim.id().effect().destination().equals(PRESSURED)),
                    "Proven physical completion did not reopen the destination.");
        }
        System.out.println("PASS expired ARMED and UNKNOWN calls survive session loss, with healthy route progress.");
    }

    private static void uncertainCommits() {
        var db = new Database();
        var first = db.claim(PRESSURED, 3, 1, 1);
        try (var connections = new DispatchWorkerConnections(db.source())) {
            var store = db.store(connections);
            db.ambiguous.set("ARM");
            var sends = new AtomicInteger();
            expectFailure(() -> store.dispatchScheduled(first, WRITER, remote(invocation -> sends.incrementAndGet())));
            require(sends.get() == 0 && db.row(first).completionWrites == 1 && !db.open(first),
                    "An exact unsent owner did not close its persisted arm.");

            var retry = db.claim(PRESSURED, 3, 2, 1);
            db.ambiguous.set("QUIESCENCE");
            expectFailure(() -> store.dispatchScheduled(retry, WRITER, remote(invocation -> {
                require(db.open(retry), "A repeat did not reserve its own exact attempt.");
                db.row(retry).state = "UNKNOWN";
            })));
            require(!db.open(retry) && db.row(retry).completionWrites == 1
                    && db.row(first).completionWrites == 1
                    && !db.row(first).owner.equals(db.row(retry).owner),
                    "A repeat or duplicate completion altered the exact prior arm.");
        }
        System.out.println("PASS uncertain ARM sends nothing and retried physical completion updates only its own attempt.");
    }

    private static Connector remote(java.util.function.Consumer<Invocation> send) {
        return new Connector() {
            @Override public Optional<Evidence> send(Invocation invocation) {
                send.accept(invocation);
                return Optional.empty();
            }
            @Override public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
                throw new AssertionError("Unexpected lookup.");
            }
        };
    }

    private static void unrecordedCompletion() {
        var db = new Database();
        var first = db.claim(PRESSURED, 4, 1, 1);
        try (var connections = new DispatchWorkerConnections(db.source())) {
            var store = db.store(connections);
            db.unavailableWrites.set(2);
            try {
                store.dispatchScheduled(first, WRITER, remote(invocation -> db.row(first).state = "UNKNOWN"));
                throw new AssertionError("Completion writes unexpectedly succeeded.");
            } catch (IllegalStateException unavailable) {
                require(unavailable.getMessage().equals("Simulated quiescence persistence outage.")
                        && unavailable.getSuppressed().length == 1, "The primary reporting failure was lost.");
            }
            require(db.open(first), "A failed acknowledgement discarded durable occupancy.");
        }
        System.out.println("PASS unavailable completion writes retain occupancy for separate oversight.");
    }

    private static void expectFailure(Future<Result> result) throws Exception {
        try {
            result.get(8, TimeUnit.SECONDS);
            throw new AssertionError("The dispatch using the lost session unexpectedly succeeded.");
        } catch (ExecutionException lost) {
            require(lost.getCause() instanceof IllegalStateException, "Unexpected simulated session failure.");
        }
    }

    private static void expectFailure(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("The injected commit uncertainty did not propagate.");
        } catch (CommitOutcomeUnknownException expected) {
            // The exact operation stays ambiguous to the caller.
        }
    }

    private static void require(boolean result, String message) {
        if (!result) {
            throw new AssertionError(message);
        }
    }

    private static final class Attempt {
        final Claim claim;
        final int slot;
        volatile String state = "CLAIMED";
        volatile UUID owner;
        volatile int backend;
        volatile boolean expired;
        volatile int completionWrites;

        Attempt(Claim claim, int slot) {
            this.claim = claim;
            this.slot = slot;
        }
    }

    private static final class Database {
        final AtomicReference<Claim> staleClaimRead = new AtomicReference<>();
        final AtomicReference<String> ambiguous = new AtomicReference<>();
        final AtomicReference<WriterEpoch> activeWriter = new AtomicReference<>(WRITER);
        final AtomicInteger unavailableWrites = new AtomicInteger();
        private final ThreadLocal<String> action = new ThreadLocal<>();
        private final AtomicInteger backends = new AtomicInteger();
        private final Map<AttemptId, Attempt> attempts = new ConcurrentHashMap<>();
        private final Set<Integer> lost = ConcurrentHashMap.newKeySet();
        private final Set<String> assigned = ConcurrentHashMap.newKeySet();

        Claim claim(String route, int effect, long number, int slot) {
            var key = new EffectKey(route, TENANT, "%064x".formatted(effect));
            var claim = new Claim(new AttemptId(key, number), UUID.randomUUID(), WRITER);
            attempts.put(claim.id(), new Attempt(claim, slot));
            return claim;
        }

        Attempt row(Claim claim) {
            return attempts.get(claim.id());
        }

        boolean open(Claim claim) {
            var row = row(claim);
            return row.owner != null && row.completionWrites == 0;
        }

        void loseAndExpire(Claim claim, String state) {
            var row = row(claim);
            lost.add(row.backend);
            row.expired = true;
            row.state = state;
        }

        JdbcDispatchStore store(DispatchWorkerConnections connections) {
            var access = new JdbcDispatchStore.GuardedAccess() {
                @Override public Optional<PolicyGuard> lock(OrganizationId tenant) {
                    return Optional.of(new PolicyGuard(tenant, 1));
                }
                @Override public Admission current(PolicyGuard guard) {
                    return new Admission(new Genesis(guard.organization(), 1, "a".repeat(64)), activeWriter.get());
                }
                @Override public Optional<UUID> originalPublication(PolicyGuard guard, Admission admission,
                                                                    OutboundObligation obligation) {
                    return Optional.of(PUBLICATION);
                }
            };
            var transactions = new TransactionRunner() {
                @Override public <T> T run(Supplier<T> work) {
                    try {
                        TransactionSynchronizationManager.bindResource(connections,
                                new ConnectionHolder(connections.getConnection()));
                    } catch (java.sql.SQLException unexpected) {
                        throw new AssertionError(unexpected);
                    }
                    TransactionSynchronizationManager.setActualTransactionActive(true);
                    try {
                        var result = work.get();
                        var step = action.get();
                        var fault = ambiguous.get();
                        if (fault != null && fault.equals(step) && ambiguous.compareAndSet(fault, null)) {
                            throw new CommitOutcomeUnknownException(new IllegalStateException("Simulated commit acknowledgement loss."));
                        }
                        return result;
                    } finally {
                        action.remove();
                        TransactionSynchronizationManager.unbindResource(connections);
                        TransactionSynchronizationManager.setActualTransactionActive(false);
                    }
                }
            };
            return new JdbcDispatchStore(connections, transactions, access, DispatchScheduler.LEASE,
                    DispatchScheduler.MIN_JITTER, Set.of(PRESSURED, HEALTHY), ENDPOINT, TENANT);
        }

        ConnectionPoolDataSource source() {
            return proxy(ConnectionPoolDataSource.class, (target, method, args) -> {
                if (method.getName().equals("getPooledConnection")) {
                    int backend = backends.incrementAndGet();
                    return proxy(PooledConnection.class, (pool, call, unused) ->
                            call.getName().equals("getConnection") ? connection(backend) : null);
                }
                return answer(target, method.getName(), method.getReturnType());
            });
        }

        private Connection connection(int backend) {
            return proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> true;
                case "getMetaData" -> proxy(DatabaseMetaData.class, (metadata, call, unused) -> switch (call.getName()) {
                    case "getDriverName" -> "PostgreSQL JDBC Driver";
                    case "getDatabaseProductName" -> "PostgreSQL";
                    default -> answer(metadata, call.getName(), call.getReturnType());
                });
                case "prepareStatement" -> statement((Connection) target, backend,
                        ((String) args[0]).replaceAll("\\s+", " ").strip());
                default -> answer(target, method.getName(), method.getReturnType());
            });
        }

        private PreparedStatement statement(Connection connection, int backend, String sql) {
            var binds = new HashMap<Integer, Object>();
            return proxy(PreparedStatement.class, (target, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    return connection;
                }
                if (method.getName().startsWith("set") && args != null && args.length >= 2) {
                    binds.put((int) args[0], args[1]);
                    return null;
                }
                if (method.getName().startsWith("execute")) {
                    if (lost.contains(backend)) {
                        throw new IllegalStateException("Simulated worker backend loss.");
                    }
                    return method.getName().equals("executeQuery") ? rows(query(sql, binds))
                            : update(sql, binds, backend);
                }
                return answer(target, method.getName(), method.getReturnType());
            });
        }

        private List<Map<?, ?>> query(String sql, Map<Integer, Object> binds) {
            if (sql.startsWith("SELECT l.lock_key, a.scheduling_slot")) {
                var row = attempt(binds, 1);
                var stale = staleClaimRead.get();
                if (row.state.equals("CLAIMED") || row.claim.equals(stale) && staleClaimRead.compareAndSet(stale, null)) {
                    return List.of(Map.of("lock_key", 1, "scheduling_slot", row.slot));
                }
                return List.of();
            }
            if (sql.startsWith("SELECT pg_") || sql.startsWith("SELECT resume_at")) {
                return List.of(Map.of(1, true));
            }
            if (sql.startsWith("SELECT o.*")) {
                var row = attempts.values().stream().filter(item -> item.claim.token().equals(findToken(binds)))
                        .findFirst().orElseThrow();
                var expected = binds.containsValue("CLAIMED") ? "CLAIMED" : "ARMED";
                return row.state.equals(expected) ? List.of(obligation(row)) : List.of();
            }
            if (sql.startsWith("SELECT a.destination")) {
                return List.of();
            }
            if (sql.startsWith("SELECT l.destination, l.lock_key")) {
                return List.of(Map.of("destination", PRESSURED, "lock_key", 1),
                        Map.of("destination", HEALTHY, "lock_key", 2));
            }
            if (sql.startsWith("SELECT NOT EXISTS")) {
                var durable = sql.contains("(a.scheduling_invocation IS NOT NULL AND a.invocation_quiesced_at IS NULL)");
                require(!durable || sql.contains("a.scheduling_slot IS NULL AND a.state = 'ARMED'"),
                        "Claim SQL omitted the pre-scheduling live-arm branch.");
                var route = (String) binds.get(1);
                int slot = (int) binds.get(3);
                boolean blocked = attempts.values().stream().anyMatch(row -> row.slot == slot
                        && route.equals(row.claim.id().effect().destination())
                        && (durable && open(row.claim) || !row.expired
                            && (row.state.equals("CLAIMED") || !durable && row.state.equals("ARMED"))));
                return List.of(Map.of(1, !blocked));
            }
            if (sql.startsWith("SELECT o.destination")) {
                var route = (String) binds.get(1);
                if (!assigned.add(route)) {
                    return List.of();
                }
                return List.of(Map.of("destination", route, "effect_id", "%064x".formatted(route.equals(HEALTHY) ? 9 : 8),
                        "last_attempt_no", 0L));
            }
            throw new AssertionError("Unexpected scheduling query: " + sql);
        }

        private int update(String sql, Map<Integer, Object> binds, int backend) {
            if (sql.startsWith("UPDATE requests.outbox_attempt SET state = 'ARMED'")) {
                require(sql.contains("armed_at = clock_timestamp() , scheduling_invocation = ?")
                        && sql.contains("WHERE state = 'CLAIMED' AND lease_expires_at > clock_timestamp()"),
                        "ARM must write exact ownership with the lease and state guard.");
                var row = attempt(binds, 4);
                row.owner = (UUID) binds.get(3);
                row.backend = backend;
                row.state = "ARMED";
                action.set("ARM");
                return 1;
            }
            if (sql.startsWith("UPDATE requests.outbox_attempt SET invocation_quiesced_at")) {
                require(sql.endsWith("AND scheduling_invocation = ? AND invocation_quiesced_at IS NULL"),
                        "Completion SQL must be exact-owner guarded and idempotent.");
                var row = attempt(binds, 1);
                action.set("QUIESCENCE");
                if (unavailableWrites.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                    throw new IllegalStateException("Simulated quiescence persistence outage.");
                }
                synchronized (row) {
                    if (binds.get(8).equals(row.owner) && row.completionWrites == 0) {
                        row.completionWrites++;
                        return 1;
                    }
                    return 0;
                }
            }
            if (sql.startsWith("INSERT INTO requests.outbox_attempt")
                    || sql.startsWith("UPDATE requests.outbox SET") || sql.startsWith("UPDATE requests.outbox_dispatch_lane")) {
                return 1;
            }
            throw new AssertionError("Unexpected scheduling update: " + sql);
        }

        private Attempt attempt(Map<Integer, Object> binds, int start) {
            var key = new EffectKey((String) binds.get(start), new OrganizationId((String) binds.get(start + 1)),
                    (String) binds.get(start + 2));
            var row = attempts.get(new AttemptId(key, ((Number) binds.get(start + 3)).longValue()));
            require(row != null && row.claim.token().equals(binds.get(start + 4))
                    && WRITER.writerId().equals(binds.get(start + 5))
                    && ((Number) binds.get(start + 6)).longValue() == WRITER.generation(),
                    "A statement changed the exact attempt, claim or writer identity.");
            return row;
        }

        private UUID findToken(Map<Integer, Object> binds) {
            return attempts.values().stream().map(row -> row.claim.token()).filter(binds::containsValue)
                    .findFirst().orElseThrow();
        }

        private Map<String, Object> obligation(Attempt row) {
            var effect = row.claim.id().effect();
            return Map.ofEntries(Map.entry("destination", effect.destination()), Map.entry("tenant", effect.tenant().value()),
                    Map.entry("effect_id", effect.effectId()), Map.entry("trigger_slot", OutboundObligation.PUBLISH_REFERENCE_SLOT),
                    Map.entry("request_ref", "occupancy-reference"), Map.entry("request_revision", 2L),
                    Map.entry("operation", "PUBLISH"), Map.entry("schema_version", 2),
                    Map.entry("projection_ref", "published-request-reference"), Map.entry("scheduled_epoch_second", 1L),
                    Map.entry("scheduled_nano", 0), Map.entry("predecessors_proved", true), Map.entry("cancelled", false),
                    Map.entry("retry_uncertain", false), Map.entry("publication_attempt", PUBLICATION));
        }
    }

    private static ResultSet rows(List<Map<?, ?>> input) {
        var index = new AtomicInteger(-1);
        return proxy(ResultSet.class, (target, method, args) -> switch (method.getName()) {
            case "next" -> index.incrementAndGet() < input.size();
            case "getMetaData" -> proxy(ResultSetMetaData.class, (metadata, call, columns) ->
                    call.getName().equals("getColumnCount") ? 1 : answer(metadata, call.getName(), call.getReturnType()));
            case "getBoolean", "getInt", "getLong", "getString", "getObject" -> input.get(index.get()).get(args[0]);
            default -> answer(target, method.getName(), method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler dispatch) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, dispatch));
    }

    private static Object answer(Object target, String method, Class<?> type) {
        return switch (method) {
            case "hashCode" -> System.identityHashCode(target);
            case "toString" -> "B25 scripted JDBC";
            default -> type == boolean.class ? false : type == int.class ? 0 : null;
        };
    }
}
