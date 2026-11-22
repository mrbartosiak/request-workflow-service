package com.company.requests.bootstrap;

import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityResolver;
import com.company.requests.adapter.http.RestInputValidator;
import com.company.requests.adapter.http.SyntheticActionContextController;
import com.company.requests.adapter.http.SyntheticCreateController;
import com.company.requests.adapter.http.SyntheticRequestActionController;
import com.company.requests.adapter.http.SyntheticRequestDetailController;
import com.company.requests.adapter.http.SyntheticRequestHistoryController;
import com.company.requests.adapter.jdbc.JdbcEffectiveScopeStore;
import com.company.requests.adapter.jdbc.JdbcPublicationConfirmationStore;
import com.company.requests.adapter.jdbc.JdbcPublishGate;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcScopeAuthorityWitnesses;
import com.company.requests.adapter.jdbc.JdbcScopeProjectionStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.ActionContextService;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeGate;
import com.company.requests.application.EffectiveScopeUpdates;
import com.company.requests.application.GuardedRelease;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.ObservationCodec;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.PublicationProof;
import com.company.requests.application.PublicationProofResolver;
import com.company.requests.application.RequestActionCommand;
import com.company.requests.application.RequestDetailQuery;
import com.company.requests.application.RequestHistoryQuery;
import com.company.requests.application.ScopeAuthorityMutation;
import com.company.requests.application.ScopeProofResolver;
import com.company.requests.application.SyntheticScopePolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Opt-in effective access profile for an explicitly migrated and certified synthetic scope. */
@Configuration(proxyBeanMethods = false)
@Import(RequestsConfiguration.class)
public class SyntheticEffectiveAccessConfiguration {
    public record Settings(SyntheticCreateConfiguration.Settings create, DataSource preparation,
                           DataSource originalObserver, DataSource projection, DataSource resolver,
                           Map<AuthenticatedActor, Set<EffectiveScopeUpdates.Permission>> operators,
                           Optional<ObservationCodec.Keys> observationKeys, boolean publicationEnabled) {
        public Settings(SyntheticCreateConfiguration.Settings create, DataSource preparation,
                        DataSource originalObserver, DataSource projection, DataSource resolver,
                        Map<AuthenticatedActor, Set<EffectiveScopeUpdates.Permission>> operators) {
            this(create, preparation, originalObserver, projection, resolver, operators, Optional.empty(), false);
        }

        public Settings(SyntheticCreateConfiguration.Settings create, DataSource preparation,
                        DataSource originalObserver, DataSource projection, DataSource resolver,
                        Map<AuthenticatedActor, Set<EffectiveScopeUpdates.Permission>> operators,
                        Optional<ObservationCodec.Keys> observationKeys) {
            this(create, preparation, originalObserver, projection, resolver, operators, observationKeys, false);
        }

        public Settings {
            Objects.requireNonNull(create);
            Objects.requireNonNull(observationKeys);
            if (publicationEnabled && (observationKeys.isEmpty() || create.guardedReplay().isEmpty())) {
                throw new IllegalArgumentException("Publish requires observation keys and guarded replay.");
            }
            var sources = List.of(create.command(), create.proof(), preparation, originalObserver, projection, resolver);
            Set<DataSource> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            distinct.addAll(sources);
            if (distinct.size() != sources.size()) {
                throw new IllegalArgumentException("Separate command, proof, preparation, observation, projection and resolver data sources are required.");
            }
            operators = operators.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }

        public SyntheticScopePolicy effectivePolicy() {
            return create.policy().withMutationOperators(operators);
        }

        @Override
        public String toString() {
            return "SyntheticEffectiveAccessSettings[protected]";
        }
    }

    public record Components(CurrentAccess access, CreateCommand create, EffectiveScopeUpdates updates,
                             ScopeProofResolver resolver, Optional<ActionContextService> actionContext,
                             Optional<RequestActionCommand> requestActions,
                             Optional<PublicationProofResolver> publicationResolver, RequestDetailQuery details,
                             RequestHistoryQuery history) { }

    @Bean
    public static Components effectiveScopeComponents(Settings settings) {
        var create = settings.create();
        var command = create.command();
        var policy = settings.effectivePolicy();
        var transactions = JdbcTransactionRunner.forCreate(command);
        var records = new JdbcWorkflowRecords(command);
        var store = new JdbcEffectiveScopeStore(command);
        var journal = JdbcScopeAuthorityJournal.withConfirmedProjection(settings.preparation(), settings.originalObserver());
        var proof = new JdbcCreateProof(create.proof());
        var workflow = new JdbcWorkflowIdentity(command);
        var publisher = settings.publicationEnabled() ? Optional.of(new RequestActionCommand.PublishExecution(
                new JdbcPublishGate(command),
                new JdbcPublicationJournal(settings.preparation(), settings.preparation(), settings.originalObserver())))
                : Optional.<RequestActionCommand.PublishExecution>empty();
        var gate = new EffectiveScopeGate(journal.confirmedAuthority(),
                admission -> proof.requireWriter(admission, workflow.current()), store);
        var access = CurrentAccess.withEffectiveScope(create.writer(), List.of(policy),
                transactions, records, records, store, gate,
                publisher.map(value -> new PublicationProof(value.journal(), new JdbcPublicationConfirmationStore(command))));
        var mutation = new ScopeAuthorityMutation(transactions, records, journal, new JdbcScopeAuthorityWitnesses(command));
        var updates = new EffectiveScopeUpdates(policy, create.writer(), mutation, gate, store, records);
        var projector = settings.projection();
        var projectionTransactions = JdbcTransactionRunner.forCreate(projector);
        var projectionRecords = new JdbcWorkflowRecords(projector);
        var projectionIdentity = new JdbcWorkflowIdentity(projector);
        var resolver = new ScopeProofResolver(projectionTransactions, projectionRecords,
                new JdbcScopeAuthorityResolver(settings.resolver(), settings.originalObserver()),
                new JdbcScopeProjectionStore(projector));
        var publicationResolver = publisher.map(ignored -> new PublicationProofResolver(projectionTransactions,
                projectionRecords,
                JdbcPublicationJournal.withAuditedResolution(settings.preparation(), settings.resolver(),
                        settings.originalObserver()),
                new JdbcPublicationConfirmationStore(projector),
                admission -> proof.requireWriter(admission, projectionIdentity.current())));
        var readRelease = new GuardedRelease(transactions, records, records, access, Duration.ofMillis(2400));
        var contexts = settings.observationKeys().map(keys -> new ActionContextService(access, records, records,
                readRelease,
                new ObservationCodec(keys, create.secrets()), create.clock()));
        var actions = contexts.flatMap(context -> create.guardedReplay().map(replay -> {
            var receipts = new JdbcReceiptStore(command);
            var keys = new OperationKeyIssuer(access, create.clock(), create.secrets(),
                    OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
            var release = new GuardedRelease(transactions, records, records, access, Duration.ofMillis(2400),
                    replay.handoffs());
            return new RequestActionCommand(transactions, access, admission -> proof.requireWriter(admission, workflow.current()),
                    keys, new IntentDigest(create.secrets()), receipts, receipts, records, records, context, policy,
                    store, mutation, release, replay.preparation(), replay.originalReceipts(), publisher);
        }));
        return new Components(access, SyntheticCreateConfiguration.createCommand(create, transactions, records, access),
                updates, resolver, contexts, actions, publicationResolver,
                new RequestDetailQuery(access, records, readRelease), new RequestHistoryQuery(access, records, readRelease));
    }

    @Bean
    SyntheticCreateController syntheticCreateController(Settings settings, Components components, RestInputValidator inputs) {
        var create = settings.create();
        return new SyntheticCreateController(create.policy().genesis().organization(), create.actors(), inputs, components.create());
    }

    @Bean
    SyntheticActionContextController syntheticActionContextController(Settings settings, Components components,
                                                                      RestInputValidator inputs) {
        var create = settings.create();
        return new SyntheticActionContextController(create.policy().genesis().organization(), create.actors(),
                inputs, components.actionContext());
    }

    @Bean
    SyntheticRequestActionController syntheticRequestActionController(Settings settings, Components components,
                                                                      RestInputValidator inputs) {
        var create = settings.create();
        return new SyntheticRequestActionController(create.policy().genesis().organization(), create.actors(),
                inputs, components.requestActions());
    }

    @Bean
    SyntheticRequestDetailController syntheticRequestDetailController(Settings settings, Components components,
                                                                      RestInputValidator inputs) {
        var create = settings.create();
        return new SyntheticRequestDetailController(create.policy().genesis().organization(), create.actors(),
                inputs, components.details());
    }

    @Bean
    SyntheticRequestHistoryController syntheticRequestHistoryController(Settings settings, Components components,
                                                                         RestInputValidator inputs) {
        var create = settings.create();
        return new SyntheticRequestHistoryController(create.policy().genesis().organization(), create.actors(),
                inputs, components.history());
    }
}
