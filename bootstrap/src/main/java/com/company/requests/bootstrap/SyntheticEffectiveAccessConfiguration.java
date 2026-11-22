package com.company.requests.bootstrap;

import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityResolver;
import com.company.requests.adapter.http.RestInputValidator;
import com.company.requests.adapter.http.SyntheticCreateController;
import com.company.requests.adapter.jdbc.JdbcEffectiveScopeStore;
import com.company.requests.adapter.jdbc.JdbcScopeAuthorityWitnesses;
import com.company.requests.adapter.jdbc.JdbcScopeProjectionStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeGate;
import com.company.requests.application.EffectiveScopeUpdates;
import com.company.requests.application.ScopeAuthorityMutation;
import com.company.requests.application.ScopeProofResolver;
import com.company.requests.application.SyntheticScopePolicy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Opt-in alternate Create profile for an explicitly migrated and certified synthetic scope. */
@Configuration(proxyBeanMethods = false)
@Import(RequestsConfiguration.class)
public class SyntheticEffectiveAccessConfiguration {
    public record Settings(SyntheticCreateConfiguration.Settings create, DataSource preparation,
                           DataSource originalObserver, DataSource projection, DataSource resolver,
                           Map<AuthenticatedActor, Set<EffectiveScopeUpdates.Permission>> operators) {
        public Settings {
            Objects.requireNonNull(create);
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
                             ScopeProofResolver resolver) { }

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
        var gate = new EffectiveScopeGate(journal.confirmedAuthority(),
                admission -> proof.requireWriter(admission, workflow.current()), store);
        var access = CurrentAccess.withEffectiveScope(create.writer(), List.of(policy),
                transactions, records, records, store, gate);
        var updates = new EffectiveScopeUpdates(policy, create.writer(),
                new ScopeAuthorityMutation(transactions, records, journal, new JdbcScopeAuthorityWitnesses(command)),
                gate, store, records);
        var projector = settings.projection();
        var resolver = new ScopeProofResolver(JdbcTransactionRunner.forCreate(projector), new JdbcWorkflowRecords(projector),
                new JdbcScopeAuthorityResolver(settings.resolver(), settings.originalObserver()),
                new JdbcScopeProjectionStore(projector));
        return new Components(access, SyntheticCreateConfiguration.createCommand(create, transactions, records, access),
                updates, resolver);
    }

    @Bean
    SyntheticCreateController syntheticCreateController(Settings settings, Components components, RestInputValidator inputs) {
        var create = settings.create();
        return new SyntheticCreateController(create.policy().genesis().organization(), create.actors(), inputs, components.create());
    }
}
