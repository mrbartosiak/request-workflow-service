import com.company.requests.adapter.authority.JdbcAuthorityProvisioning;
import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.authority.JdbcScopeAuthorityJournal;
import com.company.requests.adapter.jdbc.JdbcOutboxMigration;
import com.company.requests.adapter.jdbc.JdbcPublicationWitnessMigration;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.PublicationJournal;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityResolution.Operation;
import com.company.requests.application.ScopeAuthorityResolution.OperatorCredential;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.RequestRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.company.requests.bootstrap.B32Database.scalar;

final class B34State {
    static final Instant CLOCK = Instant.parse("2026-11-22T21:00:00Z");
    static final Duration RECEIPT_RETENTION = Duration.ofHours(24);
    static final AuthenticatedActor OWNER = B10Postgresql.OWNER;
    static final AuthenticatedActor PUBLISHER = B10Postgresql.ASSIGNEE;
    private static final List<String> BUSINESS = List.of("request", "request_body", "transition_fact",
            "request_assignment", "scope_authority_witness", "scope_grant_override", "request_visibility_fact",
            "transition_reason", "publication_witness", "publication_commit_evidence", "publication_confirmation",
            "scope_authority_projection", "policy_guard", "outbox");
    private static final List<String> PUBLICATION_PROOF = List.of("publication_attempt", "publication_namespace",
            "publication_resolution_audit", "scope_journal_attempt", "scope_journal_confirmation");

    final B17Postgresql base;
    final B33Profile.Child child;
    private final QualifyPostgresql.Connections workflow;
    private final QualifyPostgresql.Connections proof;
    private final Admission admission;
    private final JdbcPublicationJournal journal;
    private final JdbcScopeAuthorityJournal scopeJournal;
    private final SyntheticEffectiveAccessConfiguration.Components profile;
    private final DataSource command;
    private final OperatorCredential scopeOperator = new OperatorCredential(B32Fixture.token());

    B34State(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof, B17Postgresql base) {
        this.workflow = workflow;
        this.proof = proof;
        this.base = base;
        admission = new Admission(base.settings.effectivePolicy().genesis(), base.settings.create().writer());
        var work = base.sql;
        var authority = new JdbcTemplate(B08MvcSession.dataSource(proof.migration()));
        var provisioning = new JdbcAuthorityProvisioning(authority.getDataSource());
        UUID operator = UUID.randomUUID();
        provisioning.authorizeScopeOperator(operator, scopeOperator, OWNER.organization(), Operation.CONFIRM);
        provisioning.authorizeScopeOperator(operator, scopeOperator, OWNER.organization(), Operation.ABORT);
        new JdbcPublicationWitnessMigration(work.getDataSource()).migrateReceiptRetirement();
        B33Evidence.require(Boolean.TRUE.equals(work.queryForObject("""
                SELECT array_agg(version ORDER BY version) = ARRAY[1,2,3]
                FROM requests.publication_schema_migration
                """, Boolean.class)), "Expected explicit v3 receipt-retirement provisioning.");
        new JdbcOutboxMigration(work.getDataSource()).migrate();
        work.execute("""
                GRANT INSERT (destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano)
                    ON requests.outbox TO b04_workflow;
                GRANT SELECT ON requests.schema_migration TO b04_workflow;
                GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer;
                ALTER ROLE b04_observer SET default_transaction_read_only = on;
                """);
        authority.execute("""
                GRANT USAGE ON SCHEMA request_authority TO b04_observer;
                GRANT SELECT ON ALL TABLES IN SCHEMA request_authority TO b04_observer;
                GRANT SELECT ON request_authority.schema_migration TO b10_create_proof;
                ALTER ROLE b04_observer SET default_transaction_read_only = on;
                """);
        var passwords = new EnumMap<B33Profile.Role, String>(B33Profile.Role.class);
        passwords.put(B33Profile.Role.COMMAND, workflow.runtimeLogin().password());
        passwords.put(B33Profile.Role.JOURNAL, proof.runtimeLogin().password());
        for (var role : B33Profile.Role.values()) {
            if (!passwords.containsKey(role)) {
                String password = B32Fixture.token();
                (role.store == Store.WORKFLOW ? work : authority)
                        .execute("ALTER ROLE " + role.login + " PASSWORD '" + password + "'");
                passwords.put(role, password);
            }
        }
        child = new B33Profile.Child(Map.copyOf(passwords), admission.writer(), B32Fixture.token(), B32Fixture.token(),
                B32Fixture.token(), B32Fixture.token(), B32Fixture.token(), B32Fixture.token(), B32Fixture.token());
        String app = "b34-oracle";
        command = child.source(B33Profile.Role.COMMAND, app);
        var settings = B34Worker.settings(child, app, command, child.source(B33Profile.Role.CREATE, app),
                child.source(B33Profile.Role.JOURNAL, app), child.source(B33Profile.Role.ORIGINAL, app), () -> CLOCK);
        B33Evidence.require(admission.genesis().equals(settings.effectivePolicy().genesis()),
                "Native oracle does not share the original effective-policy genesis.");
        journal = new JdbcPublicationJournal(settings.preparation(), settings.resolver(), settings.originalObserver());
        scopeJournal = JdbcScopeAuthorityJournal.withConfirmedProjection(settings.preparation(), settings.originalObserver());
        profile = SyntheticEffectiveAccessConfiguration.effectiveScopeComponents(settings);
    }

    String scopeOperatorSecret() {
        return scopeOperator.secret();
    }

    Connection observe(Store store) throws Exception {
        Connection connection = store == Store.WORKFLOW
                ? child.source(B33Profile.Role.ORIGINAL, "b34-observer").getConnection() : proof.observer().open();
        connection.setSchema(store.schema);
        return connection;
    }

    OperationKeyIssuer.VerifiedKey verified(AuthenticatedActor actor, String signed) {
        var keys = new OperationKeyIssuer(profile.access(), () -> CLOCK, child.secrets(),
                OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN);
        return keys.verify(signed, actor, admission);
    }

    record Snapshot(B33Evidence.Counts counts, String business, String receipts, String publicationProof,
                    long outbox, long witness, long projection, long attempts, long watermark, long retirements) { }

    Snapshot snapshot(String label, Connection work, Connection proof) throws Exception {
        var snapshot = new Snapshot(B33Evidence.read(label, work, proof), hashRows(work, "requests", BUSINESS),
                hashRows(work, "requests", List.of("operation_receipt")),
                hashRows(proof, "request_authority", PUBLICATION_PROOF), count(work, "requests.outbox"),
                count(work, "requests.publication_witness"), count(work, "requests.publication_confirmation"),
                count(proof, "request_authority.publication_attempt"),
                Long.parseLong(scalar(proof, "SELECT last_id FROM request_authority.publication_namespace")),
                count(work, "requests.publication_receipt_retirement"));
        System.out.printf("B34_STATE %s outbox=%d publication-witness=%d projection=%d attempts=%d watermark=%d "
                        + "retirements=%d workflow-sha256=%s receipts-sha256=%s journal-sha256=%s%n",
                label, snapshot.outbox, snapshot.witness, snapshot.projection, snapshot.attempts, snapshot.watermark,
                snapshot.retirements, snapshot.business, snapshot.receipts, snapshot.publicationProof);
        return snapshot;
    }

    static void stableBusiness(Snapshot before, Snapshot after) {
        checkBusiness(before, after);
        B33Evidence.require(before.receipts.equals(after.receipts),
                "An operation receipt changed unexpectedly.");
    }

    private static void checkBusiness(Snapshot before, Snapshot after) {
        B33Evidence.require(before.business.equals(after.business) && before.outbox == after.outbox
                        && before.witness == after.witness,
                "An additional business effect or changed durable row was observed.");
    }

    static void stableEffect(Snapshot before, Snapshot after) {
        stableBusiness(before, after);
        checkPublicationProof(before, after);
        B33Evidence.require(before.retirements == after.retirements,
                "An unexpected publication retirement was recorded.");
    }

    static void cleanedReceipt(Snapshot before, Snapshot after, boolean published) {
        checkBusiness(before, after);
        checkPublicationProof(before, after);
        B33Evidence.require(!before.receipts.equals(after.receipts)
                && after.retirements == before.retirements + (published ? 1 : 0),
                "Receipt cleanup did not add the expected publication retirement evidence.");
    }

    private static void checkPublicationProof(Snapshot before, Snapshot after) {
        B33Evidence.require(before.publicationProof.equals(after.publicationProof) && before.attempts == after.attempts
                        && before.watermark == after.watermark && before.projection == after.projection
                        && before.counts.latestCreationOrdinal() == after.counts.latestCreationOrdinal(),
                "Replay changed a reservation, witness or confirmed projection.");
    }

    private static String hashRows(Connection connection, String schema, List<String> tables) throws Exception {
        var projections = tables.stream().map(table -> "(SELECT COALESCE(jsonb_agg(value ORDER BY value), '[]') "
                + "FROM (SELECT to_jsonb(t) AS value FROM " + schema + "." + table + " t) data)").toList();
        return B33Evidence.digest(scalar(connection, "SELECT jsonb_build_array(" + String.join(", ", projections) + ")::text"));
    }

    static long count(Connection conn, String table) throws SQLException {
        return Long.parseLong(scalar(conn, "SELECT count(*) FROM " + table));
    }

    static void transaction(Connection observer, B34App.Trace cut, String state) throws Exception {
        String actual = scalar(observer, "SELECT pg_xact_status(CAST(? AS xid8))::text", cut.xid());
        B33Evidence.require(actual.equals(state), "Unexpected native transaction status " + actual);
        System.out.printf("PASS JDBC %s backend=%d xid=%s pg_xact_status=%s%n",
                cut.phase(), cut.backend(), cut.xid(), actual);
    }

    void exactReceipt(Connection work, AuthenticatedActor actor, String signed, IntentDigest.Intent intent,
                      String ref, String action, String state, long revision, Long id, String xid) throws Exception {
        var key = verified(actor, signed);
        var identity = key.receiptKey();
        var digest = new IntentDigest(child.secrets()).calculate(intent);
        B33Evidence.require(scalar(work, """
                SELECT count(*) FROM requests.operation_receipt k
                JOIN requests.request r ON r.organization = k.organization AND r.ref = k.result_ref
                JOIN requests.transition_fact f ON f.organization = r.organization AND f.ref = r.ref
                    AND f.revision = k.result_revision
                WHERE k.issuer = ? AND k.organization = ? AND k.principal = ? AND k.token_id = ?
                  AND k.intent_digest_version = ? AND encode(k.intent_digest, 'hex') = ?
                  AND k.key_generation = ? AND k.expires_epoch_second = ? AND k.expires_nano = ?
                  AND k.result_action = ? AND k.result_ref = ? AND k.result_revision = ? AND k.result_state = ?
                  AND k.original_status = ? AND k.publication_id IS NOT DISTINCT FROM CAST(? AS bigint)
                  AND r.state = k.result_state AND r.revision = k.result_revision
                  AND r.publication_id IS NOT DISTINCT FROM k.publication_id
                  AND f.action = k.result_action AND f.to_state = k.result_state AND f.actor_ref = k.principal
                  AND f.recorded_epoch_second = ? AND f.recorded_nano = ?
                  AND r.xmin = k.xmin AND r.xmin = f.xmin
                  AND r.xmin::text::numeric = mod(CAST(? AS numeric), 4294967296)
                """, identity.issuer(), identity.organization().value(), identity.principal(), identity.tokenId(),
                digest.version(), digest.hex(), key.generation(), key.expiresAt().getEpochSecond(), key.expiresAt().getNano(),
                action, ref, revision, state, id == null ? 201 : 200, id, CLOCK.getEpochSecond(), CLOCK.getNano(), xid)
                .equals("1"), "Original request, immutable fact, exact finalized receipt or connection XID differ.");
    }

    Entry pending(RequestRef ref, B34App.Trace trace) {
        var entry = journal.activeBinding(admission.writer(), OWNER.organization(), B34Worker.TYPE, ref).orElseThrow();
        B33Evidence.require(entry.state() == State.PENDING && entry.allocation().isPresent()
                && entry.attempt().workflowTransaction().equals(trace.xid())
                && entry.attempt().backendPid() == trace.backend(),
                "Pending journal reservation differs from the command lease.");
        return entry;
    }

    void abort(Entry reservation) throws Exception {
        try (var read = proof.observer().open()) {
            var attempt = UUID.fromString(scalar(read, """
                    SELECT attempt_id FROM request_authority.scope_journal_attempt
                    WHERE state = 'PENDING' AND workflow_transaction = CAST(? AS xid8)
                    """, reservation.attempt().workflowTransaction()));
            B33Evidence.require(profile.resolver().resolve(scopeOperator, admission, attempt, Operation.ABORT)
                    == com.company.requests.application.ScopeAuthorityChange.State.ABORTED,
                    "Audited scope abort did not close the rolled-back attempt.");
        }
        profile.publicationResolver().orElseThrow().abort(admission, reservation.attempt());
        var burned = journal.find(reservation.attempt()).orElseThrow();
        B33Evidence.require(burned.state() == State.ABORTED && burned.allocation().equals(reservation.allocation()),
                "Audited reservation abort lost its burned allocation.");
        System.out.printf("PASS audited abort attempt=%s xid=%s burned-id=%s%n", burned.attempt().id(),
                burned.attempt().workflowTransaction(), burned.allocation().orElseThrow().value());
    }

    void confirmScope(RequestRef ref) {
        var witness = base.scopeWitness(ref);
        B33Evidence.require(profile.resolver().resolve(scopeOperator, admission, witness.attemptId(), Operation.CONFIRM)
                == com.company.requests.application.ScopeAuthorityChange.State.COMMITTED,
                "Audited scope operation was not confirmed.");
        var checkpoint = scopeJournal.confirmedAuthority().current(admission);
        B33Evidence.require(checkpoint.policyVersion() == witness.effectiveVersion()
                && checkpoint.sequence() == witness.sequence(), "Audited scope confirmation differs from original witness.");
    }

    void confirmPublication(Entry entry) {
        profile.publicationResolver().orElseThrow().confirm(admission, entry.attempt());
        var confirmed = journal.find(entry.attempt()).orElseThrow();
        B33Evidence.require(confirmed.state() == State.COMMITTED && confirmed.allocation().equals(entry.allocation()),
                "Audited publication confirmation changed original allocation.");
    }

    long admissionId(RequestRef ref) {
        var guards = new JdbcWorkflowRecords(command);
        try {
            return JdbcTransactionRunner.forCreate(command).run(() -> {
                var guard = guards.lockShared(OWNER.organization()).orElseThrow();
                return profile.access().confirmedPublication(guard, ref).map(id -> id.value()).orElse(0L);
            });
        } catch (AuthorityUnavailableException unready) {
            return 0;
        }
    }

    void exactPublication(Connection work, Entry entry, String key) throws Exception {
        var attempt = entry.attempt();
        String identity = PublicationJournal.receiptIdentity(verified(PUBLISHER, key).receiptKey());
        String effect = B33Evidence.digest("requests.outbound.effect.v1\n" + identity + "\npublish.reference");
        long id = entry.allocation().orElseThrow().value();
        B33Evidence.require(attempt.receiptIdentity().equals(identity), "Journal receipt identity differs.");
        B33Evidence.require(scalar(work, """
                SELECT count(*) FROM requests.publication_commit_evidence e
                JOIN requests.publication_witness w USING (attempt_id)
                JOIN requests.outbox o ON o.tenant = e.organization AND o.request_ref = e.ref
                    AND o.request_revision = e.result_revision
                JOIN requests.scope_authority_witness s ON s.organization = e.organization
                    AND s.workflow_transaction = e.workflow_transaction
                JOIN requests.request_visibility_fact v ON v.organization = e.organization AND v.ref = e.ref
                WHERE e.attempt_id = ? AND e.publication_id = ? AND e.workflow_transaction = CAST(? AS xid8)
                  AND o.destination = ? AND o.effect_id = ? AND o.status = 'READY' AND o.operation = 'PUBLISH'
                  AND o.schema_version = 2 AND o.trigger_slot = 'publish.reference'
                  AND o.projection_ref = 'published-request-reference'
                  AND o.scheduled_epoch_second = ? AND o.scheduled_nano = ?
                  AND v.assignee_only AND v.applied_version = s.after_version
                  AND o.xmin = w.xmin AND o.xmin = s.xmin AND o.xmin = v.xmin
                """, attempt.id(), id, attempt.workflowTransaction(), B34Worker.DESTINATION, effect,
                CLOCK.getEpochSecond(), CLOCK.getNano()).equals("1"),
                "Publication witness, B22 outbox envelope, scope witness or locally shared XID differs.");
        System.out.printf("PASS exact publication attempt=%s id=%d backend=%d xid=%s one immutable READY obligation%n",
                attempt.id(), id, attempt.backendPid(), attempt.workflowTransaction());
    }

    int purge(AuthenticatedActor actor, String signed, Instant trustedNow) throws Exception {
        var verified = verified(actor, signed);
        var key = verified.receiptKey();
        var cutoff = trustedNow.minus(RECEIPT_RETENTION);
        try (var owner = workflow.migration().open();
             var guard = owner.prepareStatement("SELECT policy_version FROM requests.policy_guard WHERE organization=? FOR UPDATE");
             var purge = owner.prepareStatement("""
                     DELETE FROM requests.operation_receipt WHERE issuer=? AND organization=? AND principal=? AND token_id=?
                        AND finalized AND (expires_epoch_second, expires_nano) <= (?, ?)
                     """)) {
            owner.setAutoCommit(false);
            try {
                guard.setString(1, key.organization().value());
                try (var row = guard.executeQuery()) {
                    B33Evidence.require(row.next(), "Receipt retention did not acquire the organization guard.");
                }
                purge.setString(1, key.issuer());
                purge.setString(2, key.organization().value());
                purge.setString(3, key.principal());
                purge.setString(4, key.tokenId());
                purge.setLong(5, cutoff.getEpochSecond());
                purge.setInt(6, cutoff.getNano());
                int removed = purge.executeUpdate();
                owner.commit();
                return removed;
            } catch (SQLException | AssertionError failure) {
                try {
                    owner.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    long liveReceiptCount(Connection work, AuthenticatedActor actor, String signed) throws Exception {
        var key = verified(actor, signed).receiptKey();
        return Long.parseLong(scalar(work, """
                SELECT count(*) FROM requests.operation_receipt
                WHERE issuer=? AND organization=? AND principal=? AND token_id=?
                """, key.issuer(), key.organization().value(), key.principal(), key.tokenId()));
    }

    void exactRetirement(Connection work, Entry original, String signed) throws Exception {
        var attempt = original.attempt();
        var current = journal.find(attempt).orElseThrow();
        long id = original.allocation().orElseThrow().value();
        B33Evidence.require(current.state() == State.COMMITTED && current.allocation().equals(original.allocation())
                && liveReceiptCount(work, PUBLISHER, signed) == 0 && admissionId(attempt.ref()) == id,
                "Receipt cleanup altered original publication or its guarded release proof.");
        B33Evidence.require(scalar(work, """
                SELECT count(*) FROM requests.publication_receipt_retirement retired
                JOIN requests.publication_witness w USING (attempt_id)
                JOIN requests.publication_commit_evidence e USING (attempt_id)
                JOIN requests.publication_confirmation c USING (attempt_id)
                WHERE retired.attempt_id = ? AND retired.workflow_transaction = CAST(? AS xid8)
                    AND retired.receipt_xmin = w.xmin AND retired.workflow_transaction = e.workflow_transaction
                    AND c.workflow_transaction = e.workflow_transaction AND c.publication_id = e.publication_id
                    AND e.publication_id = ? AND c.receipt_identity = ?
                    AND NOT EXISTS (SELECT 1 FROM requests.publication_live_receipt_evidence live
                        WHERE live.attempt_id = retired.attempt_id)
                """, attempt.id(), attempt.workflowTransaction(), id, attempt.receiptIdentity()).equals("1"),
                "The retained evidence does not prove the original publication association exactly.");
        exactPublication(work, original, signed);
        System.out.printf("PASS retired Publish retains exact commit view, guarded projection and admission "
                + "attempt=%s id=%d xid=%s%n", attempt.id(), id, attempt.workflowTransaction());
    }
}
