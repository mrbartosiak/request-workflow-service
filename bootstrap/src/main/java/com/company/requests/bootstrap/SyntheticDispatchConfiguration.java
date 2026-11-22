package com.company.requests.bootstrap;

import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.integration.jdbc.JdbcDispatchStore;
import com.company.requests.adapter.integration.jdbc.JdbcSyntheticConnector;
import com.company.requests.adapter.integration.jdbc.DispatchWorkerConnections;
import com.company.requests.adapter.jdbc.JdbcEffectiveScopeStore;
import com.company.requests.adapter.jdbc.JdbcPublicationConfirmationStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeGate;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PublicationProof;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.DispatchCoordinator;
import com.company.requests.integration.DispatchCoordinator.SyntheticRecorder;
import com.company.requests.integration.DispatchScheduler;
import com.company.requests.integration.DispatchStore;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DeliveryReconciler;
import com.company.requests.integration.OutboundEvidenceRecorder;
import com.company.requests.integration.OutboundEvidenceRecorder.Destination;
import com.company.requests.integration.SyntheticDelivery;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.TimedSyntheticDelivery;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;

/** Explicit opt-in composition for the local synthetic worker. */
public final class SyntheticDispatchConfiguration {
    private SyntheticDispatchConfiguration() { }

    public record Route(String destination, AuthenticatedActor principal, RequestType type) {
        public Route {
            if (destination == null || !destination.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("Invalid configured dispatch destination.");
            }
            Objects.requireNonNull(principal);
            Objects.requireNonNull(type);
        }
    }

    public record Components(DispatchStore store, DispatchCoordinator coordinator) { }

    public record DeliveryComponents(SyntheticDelivery.Store store, DispatchCoordinator coordinator,
                                     DeliveryReconciler reconciler, JdbcSyntheticConnector connector) { }

    public static final class ScheduledWorker implements AutoCloseable {
        private final DispatchWorkerConnections connections;
        private final JdbcDispatchStore store;
        private final com.company.requests.application.WriterEpoch writer;
        private final Connector connector;
        private final DispatchScheduler scheduler;
        private final OutboundEvidenceRecorder evidence;
        private final Map<String, Destination> aliases;

        private ScheduledWorker(DispatchWorkerConnections connections, JdbcDispatchStore store,
                                com.company.requests.application.WriterEpoch writer, Connector connector,
                                ProtectedEvidenceSpool spool, Map<String, Destination> aliases) {
            this.connections = connections;
            this.store = store;
            this.writer = writer;
            this.connector = connector;
            this.aliases = aliases;
            if (spool == null) {
                evidence = null;
                scheduler = new DispatchScheduler(() -> store.claimScheduledBatch(writer),
                        claim -> store.dispatchScheduled(claim, writer, connector));
            } else {
                evidence = new OutboundEvidenceRecorder(new RequestEvidenceRecorder(Clock.systemUTC(), spool));
                scheduler = new DispatchScheduler(() -> {
                    int limit = spool.workerBatchLimit(DispatchScheduler.BATCH_SIZE);
                    return limit == 0 ? List.of() : store.claimScheduledBatch(writer, limit);
                }, claim -> store.dispatchScheduled(claim, writer, connector, evidence, aliases));
            }
        }

        public DispatchScheduler.Snapshot snapshot() {
            return scheduler.snapshot();
        }

        public DeliveryReconciler.Result recover(AuthenticatedActor operator, AttemptId attempt,
                                                  SyntheticDelivery.Action action) {
            if (evidence == null) {
                return recover(operator, attempt, action, null);
            }
            var alias = aliases.get(attempt.effect().destination());
            try (var reservation = alias == null ? null : evidence.reserveLookup(alias).orElse(null)) {
                return reservation == null ? DeliveryReconciler.Result.WITHHELD
                        : recover(operator, attempt, action, reservation);
            }
        }

        private DeliveryReconciler.Result recover(AuthenticatedActor operator, AttemptId attempt,
                                                   SyntheticDelivery.Action action,
                                                   OutboundEvidenceRecorder.Reservation reservation) {
            return store.scheduledSession(() -> {
                try (var timed = new TimedSyntheticDelivery(connector, reservation)) {
                    return new DeliveryReconciler(store, writer, timed).recover(operator, attempt, action);
                }
            });
        }

        @Override
        public void close() {
            scheduler.close();
            connections.close();
        }
    }

    public static Components worker(SyntheticEffectiveAccessConfiguration.Settings settings, DataSource workerDataSource,
                                    DataSource authorityReader, Collection<Route> routes, Duration lease, Duration defer,
                                    SyntheticRecorder recorder) {
        var store = configuredStore(settings, workerDataSource, authorityReader, routes, lease, defer, null, null);
        return new Components(store, new DispatchCoordinator(store, settings.create().writer(), recorder));
    }

    public static DeliveryComponents syntheticWorker(SyntheticEffectiveAccessConfiguration.Settings settings,
                                                     DataSource workerDataSource, DataSource authorityReader,
                                                     DataSource remote, Collection<Route> routes, Duration lease,
                                                     Duration defer, Map<String, SyntheticDelivery.Contract> contracts) {
        checkRemote(settings, workerDataSource, authorityReader, remote, routes, contracts);
        var writer = settings.create().writer();
        var connector = new JdbcSyntheticConnector(remote, contracts);
        var store = configuredStore(settings, workerDataSource, authorityReader, routes, lease, defer,
                connector.endpoint(), null);
        return new DeliveryComponents(store, new DispatchCoordinator(store, writer, connector),
                new DeliveryReconciler(store, writer, connector), connector);
    }

    public static ScheduledWorker scheduledWorker(SyntheticEffectiveAccessConfiguration.Settings settings,
                                                  ConnectionPoolDataSource worker, DataSource authorityReader,
                                                  DataSource remote, Collection<Route> routes,
                                                  Map<String, SyntheticDelivery.Contract> contracts) {
        return scheduledWorker(settings, worker, authorityReader, remote, routes, contracts, connector -> connector);
    }

    static ScheduledWorker scheduledWorker(SyntheticEffectiveAccessConfiguration.Settings settings,
                                           ConnectionPoolDataSource worker, DataSource authorityReader,
                                           DataSource remote, Collection<Route> routes,
                                           Map<String, SyntheticDelivery.Contract> contracts,
                                           Function<JdbcSyntheticConnector, Connector> fixtureControl) {
        return scheduledWorker(settings, worker, authorityReader, remote, routes, contracts, fixtureControl,
                null, Map.of());
    }

    public static ScheduledWorker protectedScheduledWorker(SyntheticEffectiveAccessConfiguration.Settings settings,
                                                           ConnectionPoolDataSource worker, DataSource authorityReader,
                                                           DataSource remote, Collection<Route> routes,
                                                           Map<String, SyntheticDelivery.Contract> contracts,
                                                           ProtectedEvidenceSpool spool,
                                                           Map<String, Destination> aliases) {
        return scheduledWorker(settings, worker, authorityReader, remote, routes, contracts, connector -> connector,
                Objects.requireNonNull(spool), aliases);
    }

    static ScheduledWorker scheduledWorker(SyntheticEffectiveAccessConfiguration.Settings settings,
                                           ConnectionPoolDataSource worker, DataSource authorityReader,
                                           DataSource remote, Collection<Route> routes,
                                           Map<String, SyntheticDelivery.Contract> contracts,
                                           Function<JdbcSyntheticConnector, Connector> fixtureControl,
                                           ProtectedEvidenceSpool spool, Map<String, Destination> aliases) {
        if (worker == authorityReader || worker == settings.create().command()) {
            throw new IllegalArgumentException("Scheduled delivery requires a dedicated worker connection pool.");
        }
        checkRemote(settings, worker, authorityReader, remote, routes, contracts);
        var safeAliases = Map.copyOf(aliases);
        if (spool != null && (!safeAliases.keySet().equals(contracts.keySet())
                || Set.copyOf(safeAliases.values()).size() != safeAliases.size())) {
            throw new IllegalArgumentException("Protected scheduled delivery requires a distinct safe alias per route.");
        }
        var connector = new JdbcSyntheticConnector(remote, contracts, DispatchScheduler.IO_TIMEOUT);
        var connections = new DispatchWorkerConnections(worker);
        try {
            var store = configuredStore(settings, connections, authorityReader, routes, DispatchScheduler.LEASE,
                    DispatchScheduler.MIN_JITTER, connector.endpoint(), settings.effectivePolicy().genesis().organization());
            store.enrollScheduledRoutes();
            return new ScheduledWorker(connections, store, settings.create().writer(),
                    Objects.requireNonNull(fixtureControl.apply(connector)), spool, safeAliases);
        } catch (RuntimeException failure) {
            connections.close();
            throw failure;
        }
    }

    private static void checkRemote(SyntheticEffectiveAccessConfiguration.Settings settings, Object worker,
                                    DataSource authorityReader, DataSource remote, Collection<Route> routes,
                                    Map<String, SyntheticDelivery.Contract> contracts) {
        if (remote == worker || remote == authorityReader || remote == settings.create().command()
                || contracts.isEmpty() || !contracts.keySet().equals(routes.stream().map(Route::destination)
                        .collect(Collectors.toUnmodifiableSet()))) {
            throw new IllegalArgumentException("Synthetic delivery requires its own remote source and exact route contracts.");
        }
    }

    private static JdbcDispatchStore configuredStore(SyntheticEffectiveAccessConfiguration.Settings settings,
                                                     DataSource workerDataSource, DataSource authorityReader,
                                                     Collection<Route> routes, Duration lease, Duration defer,
                                                     UUID endpoint, OrganizationId scheduledTenant) {
        if (!settings.publicationEnabled() || workerDataSource == settings.create().command()
                || authorityReader == workerDataSource || authorityReader == settings.create().command()) {
            throw new IllegalArgumentException("Dispatch requires an enabled publication profile and separate data sources.");
        }
        var policy = settings.effectivePolicy();
        var writer = settings.create().writer();
        var byDestination = new HashMap<String, Route>();
        for (var route : routes) {
            if (!policy.genesis().organization().equals(route.principal().organization())
                    || byDestination.putIfAbsent(route.destination(), route) != null) {
                throw new IllegalArgumentException("Dispatch routes must identify unique destinations in the configured scope.");
            }
        }
        var transactions = JdbcTransactionRunner.forWorker(workerDataSource);
        var records = new JdbcWorkflowRecords(workerDataSource);
        var scopeStore = new JdbcEffectiveScopeStore(workerDataSource);
        var authority = JdbcScopeAuthorityJournal.withConfirmedProjection(authorityReader, settings.originalObserver());
        var writerProof = new JdbcCreateProof(authorityReader);
        var workflow = new JdbcWorkflowIdentity(workerDataSource);
        var gate = new EffectiveScopeGate(authority.confirmedAuthority(),
                admission -> writerProof.requireWriter(admission, workflow.current()), scopeStore);
        var journal = new JdbcPublicationJournal(authorityReader, authorityReader, settings.originalObserver());
        var access = CurrentAccess.withEffectiveScope(writer, List.of(policy), transactions, records, records,
                scopeStore, gate, Optional.of(new PublicationProof(journal,
                        new JdbcPublicationConfirmationStore(workerDataSource))));
        return new JdbcDispatchStore(workerDataSource, transactions, new JdbcDispatchStore.GuardedAccess() {
            @Override
            public Optional<PolicyGuard> lock(OrganizationId tenant) {
                return records.lockDispatchGuard(tenant);
            }

            @Override
            public Admission current(PolicyGuard guard) {
                return access.requireDispatchScope(guard);
            }

            @Override
            public Optional<UUID> originalPublication(PolicyGuard guard, Admission admission,
                                                      OutboundObligation obligation) {
                return Optional.ofNullable(byDestination.get(obligation.destination())).flatMap(route ->
                        access.confirmedDispatchPublication(guard, admission, route.principal(), route.type(),
                                obligation));
            }

            @Override
            public Optional<UUID> operatorPublication(PolicyGuard guard, Admission admission,
                                                      AuthenticatedActor operator, OutboundObligation obligation) {
                return Optional.ofNullable(byDestination.get(obligation.destination())).flatMap(route ->
                        access.confirmedDispatchPublication(guard, admission, operator, route.type(), obligation));
            }
        }, lease, defer, endpoint == null ? Set.of() : byDestination.keySet(), endpoint, scheduledTenant);
    }
}
