package com.company.requests.bootstrap;

import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.integration.jdbc.JdbcDispatchStore;
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
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.DispatchCoordinator;
import com.company.requests.integration.DispatchCoordinator.SyntheticRecorder;
import com.company.requests.integration.DispatchStore;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    public static Components worker(SyntheticEffectiveAccessConfiguration.Settings settings, DataSource workerDataSource,
                                    DataSource authorityReader, Collection<Route> routes, Duration lease, Duration defer,
                                    SyntheticRecorder recorder) {
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
        var store = new JdbcDispatchStore(workerDataSource, transactions, new JdbcDispatchStore.GuardedAccess() {
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
        }, lease, defer);
        return new Components(store, new DispatchCoordinator(store, writer, recorder));
    }
}
