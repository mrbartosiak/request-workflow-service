package com.company.requests.bootstrap;

import com.company.requests.adapter.authority.JdbcCreateProof;
import com.company.requests.adapter.authority.JdbcSyntheticAuthority;
import com.company.requests.adapter.http.RestInputValidator;
import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.adapter.http.SyntheticCreateController;
import com.company.requests.adapter.jdbc.JdbcReceiptStore;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowIdentity;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.CreateCommand;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.GuardedRelease;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.OriginalReceiptProof;
import com.company.requests.application.ReleaseEvidence;
import com.company.requests.application.ReleasePreparation;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.TrustedTime;
import com.company.requests.application.WriterEpoch;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Opt-in first-slice wiring. Nothing provisions data or enables these routes from the default context. */
@Configuration(proxyBeanMethods = false)
@Import(RequestsConfiguration.class)
public class SyntheticCreateConfiguration {
    /** Private ports supplied by the upgraded synthetic profile. */
    public record GuardedReplay(OriginalReceiptProof originalReceipts, ReleasePreparation preparation,
                                ReleaseEvidence.Handoffs handoffs) {
        public GuardedReplay {
            Objects.requireNonNull(originalReceipts);
            Objects.requireNonNull(preparation);
            Objects.requireNonNull(handoffs);
        }
    }

    public record Settings(DataSource command, DataSource proof, SyntheticScopePolicy policy, WriterEpoch writer,
                           SyntheticActorResolver actors, TrustedTime clock, OperationSecrets secrets,
                           Optional<GuardedReplay> guardedReplay) {
        public Settings(DataSource command, DataSource proof, SyntheticScopePolicy policy, WriterEpoch writer,
                        SyntheticActorResolver actors, TrustedTime clock, OperationSecrets secrets) {
            this(command, proof, policy, writer, actors, clock, secrets, Optional.empty());
        }

        public Settings {
            Objects.requireNonNull(command);
            Objects.requireNonNull(proof);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(writer);
            Objects.requireNonNull(actors);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(secrets);
            Objects.requireNonNull(guardedReplay);
            if (command == proof) {
                throw new IllegalArgumentException("Separate workflow and proof data sources are required.");
            }
        }

        @Override
        public String toString() {
            return "SyntheticCreateSettings[protected]";
        }
    }

    @Bean
    SyntheticCreateController syntheticCreateController(Settings settings, RestInputValidator inputs) {
        var transactions = JdbcTransactionRunner.forCreate(settings.command());
        var requests = new JdbcWorkflowRecords(settings.command());
        var authority = new JdbcSyntheticAuthority(settings.proof());
        var access = new CurrentAccess(authority, settings.writer(), List.of(settings.policy()));
        return new SyntheticCreateController(settings.policy().genesis().organization(), settings.actors(), inputs,
                createCommand(settings, transactions, requests, access));
    }

    static CreateCommand createCommand(Settings settings, JdbcTransactionRunner transactions,
                                      JdbcWorkflowRecords requests, CurrentAccess access) {
        var receipts = new JdbcReceiptStore(settings.command());
        var workflow = new JdbcWorkflowIdentity(settings.command());
        var authority = new JdbcSyntheticAuthority(settings.proof());
        var proof = new JdbcCreateProof(settings.proof());
        var guardedReplay = settings.guardedReplay().orElseGet(() ->
                new GuardedReplay(OriginalReceiptProof.unavailable(), proof, ignored -> { }));
        var keys = new OperationKeyIssuer(access, settings.clock(), settings.secrets(),
                OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        var release = new GuardedRelease(transactions, requests, requests, access, Duration.ofMillis(2400),
                guardedReplay.handoffs());
        return new CreateCommand(transactions, requests, requests, receipts, receipts, access,
                admission -> proof.requireWriter(admission, workflow.current()), authority, keys,
                new IntentDigest(settings.secrets()), guardedReplay.preparation(), release, guardedReplay.originalReceipts());
    }
}
