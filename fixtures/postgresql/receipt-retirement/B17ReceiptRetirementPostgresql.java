import com.company.requests.adapter.authority.JdbcPublicationJournal;
import com.company.requests.adapter.jdbc.JdbcPublicationWitnessMigration;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeStore.Grant;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.bootstrap.SyntheticCreateConfiguration;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private v2 regression and v3 receipt retirement evidence on the ordinary B17 Publish profile. */
public final class B17ReceiptRetirementPostgresql {
    private static final RequestType TYPE = RequestType.BUG_REPORT;
    private static final Instant ISSUED = Instant.parse("2026-11-22T21:00:00.123456789Z");
    private static final Instant EXPIRES = ISSUED.plus(OperationKeyIssuer.VALIDITY);
    private static final Instant DUE = EXPIRES.plus(Duration.ofHours(24));
    private static final String EXPIRED = "urn:requests:problem:key_expired";

    private final AtomicReference<Instant> clock = new AtomicReference<>(ISSUED);
    private final AtomicInteger receiptClaims = new AtomicInteger();
    private final AtomicInteger receiptLookups = new AtomicInteger();
    private final B17Postgresql base;
    private final SyntheticEffectiveAccessConfiguration.Settings settings;
    private final JdbcTemplate workflow;
    private final JdbcTemplate observer;
    private final QualifyPostgresql.ScopeRestore restore;

    private B17ReceiptRetirementPostgresql(QualifyPostgresql.Connections workflow, QualifyPostgresql.Connections proof,
                                          QualifyPostgresql.SqlConnection projector, QualifyPostgresql.SqlConnection resolver,
                                          QualifyPostgresql.SqlConnection createProof, QualifyPostgresql.ReleaseLogins logins,
                                          QualifyPostgresql.ScopeRestore restore, B10FixtureSources sources) throws SQLException {
        base = new B17Postgresql(workflow, proof, projector, resolver, createProof, logins, sources, this::observe);
        this.workflow = base.sql;
        observer = new JdbcTemplate(B08MvcSession.dataSource(workflow.observer()));
        this.restore = restore;
        var prior = base.settings;
        var create = prior.create();
        var controlled = new SyntheticCreateConfiguration.Settings(create.command(), create.proof(), create.policy(),
                create.writer(), create.actors(), clock::get, create.secrets(), create.guardedReplay());
        settings = new SyntheticEffectiveAccessConfiguration.Settings(controlled, prior.preparation(),
                prior.originalObserver(), prior.projection(), prior.resolver(), prior.operators(),
                prior.observationKeys(), true);
    }

    public static void main(String[] args) {
        try {
            QualifyPostgresql.publishCommands(args, (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                try (var sources = new B10FixtureSources()) {
                    new B17ReceiptRetirementPostgresql(workflow, proof, projector, resolver, createProof, logins,
                            restore, sources).exercise(Boolean.getBoolean("requests.b17.retirement.red"));
                }
            });
        } catch (Exception | AssertionError failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                var detail = switch (cause) {
                    case SQLException sql -> " SQLSTATE " + sql.getSQLState();
                    case AssertionError assertion -> " " + assertion.getMessage();
                    default -> "";
                };
                System.err.println("B17 receipt retirement " + cause.getClass().getSimpleName() + detail);
                Arrays.stream(cause.getStackTrace()).limit(9).forEach(frame -> System.err.println("    at " + frame));
            }
            System.exit(1);
        }
    }

    private void exercise(boolean red) throws Exception {
        base.enable(B10Postgresql.OWNER, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.CONTEXT_VERIFY,
                Grant.EXECUTE_VERIFY, Grant.RESULT_READ_VERIFY);
        base.enable(B10Postgresql.ASSIGNEE, TYPE, Grant.ROW_READ, Grant.FIELD_READ_BODY, Grant.FIELD_READ_NAME,
                Grant.CONTEXT_ACCEPT, Grant.EXECUTE_ACCEPT, Grant.RESULT_READ_ACCEPT, Grant.CONTEXT_PUBLISH,
                Grant.EXECUTE_PUBLISH, Grant.RESULT_READ_PUBLISH, Grant.FIELD_READ_PUBLICATION_ID);
        Attempt first;
        Attempt pending;
        Attempt inexact;
        String original;
        try (var mvc = new B08MvcSession(settings)) {
            first = publish(mvc);
            base.confirm(first.entry());
            original = success(mvc, first);
            if (red) {
                clock.set(DUE);
                expired(mvc, first);
                expectSql("23503", () -> deleteReceipt(first));
                require(receipts(first) == 1 && evidence(observer, first) == 1, "v2 deletion changed the original proof");
                System.out.println("PASS observed v2 native red: due Publish receipt DELETE fails SQLSTATE 23503");
                return;
            }
            pending = publish(mvc);
            inexact = publish(mvc);
        }

        var originalWitnesses = witnesses();
        migrate();
        require(originalWitnesses.equals(witnesses()) && retirements() == 0
                        && evidence(observer, first) == 1 && evidence(observer, pending) == 1
                        && base.binding(TYPE, pending.ref()).state() == State.PENDING,
                "v3 migration changed a legacy witness, receipt or decision");
        checkSchema();
        System.out.println("PASS v3 reapplies on populated v2 with unchanged original witness versions and view grants");

        try (var restarted = new B08MvcSession(settings)) {
            var newTarget = base.accepted(restarted, TYPE);
            rejectUnsupportedWitness(first, newTarget);
            var fresh = publish(restarted, newTarget);
            require(evidence(observer, fresh) == 1, "a normal v3 command did not append exact live proof");
            originalWitnesses = witnesses();
            System.out.println("PASS v3 append accepts the exact command receipt and rejects mismatched linkage");

            clock.set(EXPIRES.minusNanos(1));
            require(success(restarted, first).equals(original), "last eligible replay after restart changed bytes");
            clock.set(EXPIRES);
            expired(restarted, first);
            clock.set(DUE.minusNanos(1));
            require(deleteReceipt(first) == 0, "fixture cleanup occurred before the trusted retention cutoff");
            clock.set(DUE);
            var effects = effects();
            rollbackDelete(first);
            require(deleteReceipt(first) == 1 && receipts(first) == 0 && retirements() == 1
                            && evidence(observer, first) == 1 && effects.equals(effects()),
                    "retirement lost exact original proof or changed a business effect");
            require(deleteReceipt(first) == 0 && retirements() == 1, "cleanup retry wrote a second attestation");
            expired(restarted, first);
            require(publishedId(first) == first.number(), "retirement changed existing dispatch admission");
            System.out.println("PASS lost-response replay, signed expiry and one committed retirement preserve the original result");

            conflictingLiveReceipt(first);
            require(workflow.update("DELETE FROM requests.publication_confirmation WHERE attempt_id = ?", first.id()) == 1,
                    "projection rebuild cut did not remove the local projection");
            require(publishedId(first) == 0, "dispatch admitted a missing projection");
            base.confirm(first.entry());
            require(publishedId(first) == first.number(), "certified projection rebuild after cleanup lost the ID");
            System.out.println("PASS independently COMMITTED association rebuilds its exact guarded projection after cleanup");

            require(deleteReceipt(pending) == 1 && evidence(observer, pending) == 1
                            && base.binding(TYPE, pending.ref()).state() == State.PENDING && publishedId(pending) == 0,
                    "cleanup acted as independent publication confirmation");
            fenceRestoredCopy(pending);
            base.confirm(pending.entry());
            require(publishedId(pending) == pending.number() && receipts(pending) == 0,
                    "certified original could not confirm a retired PENDING receipt");
            expired(restarted, pending);
            System.out.println("PASS PENDING survives cleanup; restored lineage is fenced and exact original confirmation admits it");

            rejectInexact(inexact);
            require(deleteReceipt(first.ref(), "VERIFY") == 1 && retirements() == 2
                            && evidence(observer, first) == 1,
                    "ordinary expired receipt cleanup created a publication attestation");
        }
        require(originalWitnesses.equals(witnesses()) && retirements() == 2,
                "retirement or rejection changed a legacy witness or added an attestation");
        System.out.println("PASS missing or mismatched proof cannot manufacture retirement evidence");
    }

    private Attempt publish(B08MvcSession mvc) throws Exception {
        return publish(mvc, base.accepted(mvc, TYPE));
    }

    private Attempt publish(B08MvcSession mvc, RequestRef ref) throws Exception {
        var key = B17Postgresql.issue(mvc, base.publisherBearer);
        var observation = B17Postgresql.observation(mvc, ref, "publish", base.publisherBearer);
        var call = new B08MvcSession.Call("/requests/" + ref.value() + "/actions/publish", base.publisherBearer, key,
                "{\"expectedRevision\":\"" + observation + "\"}");
        var reply = mvc.invoke(call);
        require(reply.status() == 503 && reply.json().path("type").asString().endsWith(":outcome_unknown"),
                "Publish did not commit and await independent proof");
        var entry = base.binding(TYPE, ref);
        require(entry.state() == State.PENDING, "Publish did not produce one original PENDING attempt");
        return new Attempt(ref, call, entry);
    }

    private void migrate() throws Exception {
        var migration = new JdbcPublicationWitnessMigration(workflow.getDataSource());
        // One private launcher compiles against and runs both exact v2 and v3 packages.
        var method = JdbcPublicationWitnessMigration.class.getMethod("migrateReceiptRetirement");
        try {
            method.invoke(migration);
            method.invoke(migration);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }

    private void checkSchema() {
        require(workflow.queryForList("SELECT version FROM requests.publication_schema_migration ORDER BY version",
                        Integer.class).equals(List.of(1, 2, 3)), "publication migration history is unexpected");
        require(Boolean.TRUE.equals(workflow.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'requests.publication_witness'::regclass
                      AND confrelid = 'requests.operation_receipt'::regclass AND contype = 'f')
                AND EXISTS (
                    SELECT 1 FROM pg_constraint WHERE conname = 'receipt_must_finalize'
                      AND conrelid = 'requests.operation_receipt'::regclass AND condeferrable)
                AND has_table_privilege('b04_observer', 'requests.publication_commit_evidence', 'SELECT')
                AND NOT has_table_privilege('b04_observer', 'requests.publication_live_receipt_evidence', 'SELECT')
                AND NOT has_table_privilege('b04_workflow', 'requests.publication_receipt_retirement', 'INSERT')
                AND NOT has_table_privilege('b11_projection', 'requests.publication_receipt_retirement', 'INSERT')
                """, Boolean.class)), "v3 changed the finalization constraint or crossed proof role boundaries");
    }

    private void rejectUnsupportedWitness(Attempt source, RequestRef ref) {
        expectSql("23514", () -> workflow.update("""
                INSERT INTO requests.publication_witness (
                    attempt_id, writer_id, generation, organization, request_type, ref, namespace, publication_id,
                    receipt_issuer, receipt_principal, receipt_token_id, result_revision, workflow_transaction
                )
                SELECT gen_random_uuid(), writer_id, generation, organization, request_type, ?, namespace, ?,
                    receipt_issuer, receipt_principal, receipt_token_id, result_revision, pg_current_xact_id()
                FROM requests.publication_witness WHERE attempt_id = ?
                """, ref.value(), Long.MAX_VALUE, source.id()));
    }

    private void rollbackDelete(Attempt first) {
        rollback(() -> {
            require(deleteReceipt(first) == 1 && receipts(first) == 0 && retirements() == 1
                            && evidence(workflow, first) == 1 && evidence(observer, first) == 1,
                    "reader saw a gap across staged cleanup or cleanup did not stage together");
        });
        require(receipts(first) == 1 && retirements() == 0 && evidence(observer, first) == 1,
                "cleanup rollback failed to restore the live proof source");
        System.out.println("PASS rollback clears the staged attestation and readers retain exact evidence");
    }

    private void conflictingLiveReceipt(Attempt first) {
        rollback(() -> {
            int inserted = workflow.update("""
                    INSERT INTO requests.operation_receipt (
                        issuer, organization, principal, token_id, intent_digest_version, intent_digest,
                        key_generation, expires_epoch_second, expires_nano, result_action, result_ref, result_revision,
                        result_state, publication_namespace, publication_id, original_status
                    )
                    SELECT receipt_issuer, organization, receipt_principal, receipt_token_id, 1, decode('01', 'hex'),
                        generation, ?, ?, 'PUBLISH', ref, result_revision + 1,
                        'PUBLISHED', namespace, publication_id, 200
                    FROM requests.publication_witness WHERE attempt_id = ?
                    """, EXPIRES.getEpochSecond(), EXPIRES.getNano(), first.id());
            require(inserted == 1 && evidence(workflow, first) == 0,
                    "retired receipt evidence masked a conflicting live key");
        });
        require(evidence(observer, first) == 1, "isolated conflicting receipt changed the certified proof");
    }

    private void fenceRestoredCopy(Attempt pending) throws Exception {
        DataSource restored = B08MvcSession.dataSource(restore.snapshot());
        require(evidence(new JdbcTemplate(restored), pending) == 1,
                "the restored lineage control did not contain the retired proof");
        var fromCopy = JdbcPublicationJournal.withAuditedResolution(settings.preparation(), settings.resolver(), restored);
        expectUnavailable(() -> fromCopy.confirm(pending.entry().attempt()));
        require(base.binding(TYPE, pending.ref()).state() == State.PENDING,
                "retired receipt evidence falsely certified the restored writer");
    }

    private void rejectInexact(Attempt attempt) {
        expectSql("23503", () -> workflow.update("""
                UPDATE requests.operation_receipt SET token_id = token_id || 'x'
                WHERE organization = ? AND result_ref = ? AND result_action = 'PUBLISH'
                """, B10Postgresql.ORG.value(), attempt.ref().value()));
        rollback(() -> {
            require(workflow.update("""
                    UPDATE requests.operation_receipt SET result_revision = result_revision + 1
                    WHERE organization = ? AND result_ref = ? AND result_action = 'PUBLISH'
                    """, B10Postgresql.ORG.value(), attempt.ref().value()) == 1, "inexact fixture cut missed the receipt");
            require(evidence(workflow, attempt) == 0, "inexact live row remained exact proof");
            expectSql("23514", () -> deleteReceipt(attempt));
        });
        require(receipts(attempt) == 1 && evidence(observer, attempt) == 1 && retirements() == 2,
                "inexact deletion left a partial proof write");
    }

    private String success(B08MvcSession mvc, Attempt attempt) throws Exception {
        var reply = mvc.invoke(attempt.call());
        require(reply.status() == 200 && reply.json().path("publicationId").asString()
                        .equals(Long.toString(attempt.number())), "Publish replay did not return the original decimal ID");
        return reply.body();
    }

    private void expired(B08MvcSession mvc, Attempt attempt) throws Exception {
        int claims = receiptClaims.get();
        int lookups = receiptLookups.get();
        var reply = mvc.invoke(attempt.call());
        require(reply.status() == 409 && reply.json().path("type").asString().equals(EXPIRED)
                        && reply.location().isEmpty() && reply.cache().equals("no-store")
                        && !reply.json().has("publicationId")
                        && receiptClaims.get() == claims && receiptLookups.get() == lookups,
                "signed expiry used receipt state, acquired a claim or released protected data");
    }

    private int deleteReceipt(Attempt attempt) {
        return deleteReceipt(attempt.ref(), "PUBLISH");
    }

    private int deleteReceipt(RequestRef ref, String action) {
        Instant cutoff = clock.get().minus(Duration.ofHours(24));
        return workflow.update("""
                DELETE FROM requests.operation_receipt
                WHERE organization = ? AND result_ref = ? AND result_action = ?
                  AND (expires_epoch_second, expires_nano) <= (?, ?)
                """, B10Postgresql.ORG.value(), ref.value(), action, cutoff.getEpochSecond(), cutoff.getNano());
    }

    private int receipts(Attempt attempt) {
        return workflow.queryForObject("""
                SELECT count(*) FROM requests.operation_receipt
                WHERE organization = ? AND result_ref = ? AND result_action = 'PUBLISH'
                """, Integer.class, B10Postgresql.ORG.value(), attempt.ref().value());
    }

    private int retirements() {
        return workflow.queryForObject("SELECT count(*) FROM requests.publication_receipt_retirement", Integer.class);
    }

    private long publishedId(Attempt attempt) {
        var source = settings.create().command();
        var guards = new JdbcWorkflowRecords(source);
        return JdbcTransactionRunner.forCreate(source).run(() -> {
            var guard = guards.lockShared(B10Postgresql.ORG).orElseThrow();
            return base.profile.access().confirmedPublication(guard, attempt.ref()).map(id -> id.value()).orElse(0L);
        });
    }

    private static int evidence(JdbcTemplate reader, Attempt attempt) {
        return reader.queryForObject("""
                SELECT count(*) FROM requests.publication_commit_evidence
                WHERE attempt_id = ? AND publication_id = ? AND workflow_transaction = CAST(? AS xid8)
                """, Integer.class, attempt.id(), attempt.number(), attempt.entry().attempt().workflowTransaction());
    }

    private List<Map<String, Object>> witnesses() {
        return workflow.queryForList("""
                SELECT attempt_id, ref, xmin::text AS original_tuple, workflow_transaction::text, publication_id
                FROM requests.publication_witness ORDER BY attempt_id
                """);
    }

    private Map<String, Object> effects() {
        return workflow.queryForMap("""
                SELECT (SELECT count(*) FROM requests.request) AS requests,
                       (SELECT count(*) FROM requests.transition_fact) AS facts,
                       (SELECT count(*) FROM requests.publication_witness) AS witnesses,
                       (SELECT count(*) FROM requests.publication_confirmation) AS confirmations
                """);
    }

    private void rollback(Runnable work) {
        try {
            new JdbcTransactionRunner(workflow.getDataSource()).run(() -> {
                work.run();
                throw new FixtureRollback();
            });
        } catch (FixtureRollback observed) {
            return;
        }
        throw new AssertionError("fixture rollback did not occur");
    }

    private Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                        if (sql.contains("INSERT INTO requests.operation_receipt") && sql.contains("ON CONFLICT")) {
                            receiptClaims.incrementAndGet();
                        } else if (sql.contains("FROM requests.operation_receipt") && sql.contains("intent_digest_version")) {
                            receiptLookups.incrementAndGet();
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static void expectSql(String state, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                    return;
                }
            }
            throw failure;
        }
        throw new AssertionError("expected SQLSTATE " + state);
    }

    private static void expectUnavailable(Runnable operation) {
        try {
            operation.run();
        } catch (AuthorityUnavailableException expected) {
            return;
        }
        throw new AssertionError("uncertified restored publication was confirmed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Attempt(RequestRef ref, B08MvcSession.Call call, Entry entry) {
        java.util.UUID id() {
            return entry.attempt().id();
        }

        long number() {
            return entry.allocation().orElseThrow().value();
        }
    }

    private static final class FixtureRollback extends RuntimeException { }
}
