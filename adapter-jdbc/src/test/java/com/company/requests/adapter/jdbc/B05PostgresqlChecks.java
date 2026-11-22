package com.company.requests.adapter.jdbc;

import com.company.requests.application.CommitOutcomeUnknownException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.application.ReceiptKey;
import com.company.requests.domain.Action;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.Lifecycle;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestBody;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import com.company.requests.domain.RequestType;
import com.company.requests.domain.TransitionFact;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Executed by the opt-in B05 launcher with real PostgreSQL roles. */
public final class B05PostgresqlChecks {
    private static final OrganizationId ORG = new OrganizationId("local");
    private static final OrganizationId OTHER = new OrganizationId("other");
    private static final OrganizationId FAULT_ORG = new OrganizationId("rollback");
    private static final String TEXT = "  Zażółć gęślą jaźń — 東京 — 😀 — e\u0301  \n";
    private static final Instant NOW = Instant.parse("2026-01-01T12:34:56.987654321Z");
    private static final TransitionFact.Occurrence OCCURRENCE = new TransitionFact.Occurrence(NOW, TEXT);

    private final JdbcTemplate admin;
    private final JdbcTemplate observer;
    private final TrackingDataSource commandDataSource;
    private final JdbcTemplate command;
    private final JdbcTransactionRunner runner;
    private final JdbcWorkflowRecords workflow;
    private final JdbcReceiptStore receipts;

    private B05PostgresqlChecks(DataSource migration, DataSource command, DataSource observer) {
        admin = new JdbcTemplate(migration);
        this.observer = new JdbcTemplate(observer);
        commandDataSource = new TrackingDataSource(command);
        this.command = new JdbcTemplate(commandDataSource);
        runner = new JdbcTransactionRunner(commandDataSource);
        workflow = new JdbcWorkflowRecords(commandDataSource);
        receipts = new JdbcReceiptStore(commandDataSource);
    }

    public static void verify(DataSource migration, DataSource command, DataSource observer, boolean prior) throws SQLException {
        var check = new B05PostgresqlChecks(migration, command, observer);
        check.migrate(new JdbcWorkflowMigrations(migration), prior);
        check.verifyOwnership(migration, observer);
        check.verifySecondaryConnection(command);
        check.verifyEffects(prior);
        check.verifyIntegrity();
    }

    private void migrate(JdbcWorkflowMigrations migrations, boolean prior) {
        admin.update("INSERT INTO fixture.marker VALUES (817, 'preserved predecessor row')");
        require(Boolean.TRUE.equals(admin.queryForObject("SELECT to_regnamespace('requests') IS NULL", Boolean.class)),
                "fresh fixture must have no workflow namespace");
        if (prior) {
            migrations.migrateThrough(1);
            admin.update("INSERT INTO requests.policy_guard VALUES ('previous-version', 7)");
            require(List.of(1).equals(versions()), "prior schema must be V001 only");
            require(Boolean.TRUE.equals(admin.queryForObject("SELECT to_regclass('requests.request') IS NULL", Boolean.class)),
                    "prior version must lack the V002 request table");
        }
        migrations.migrate();
        require(List.of(1, 2, 3).equals(versions()), "workflow migration history must be V001–V003");
        Map<String, Object> catalog = catalog();
        require(catalog.keySet().containsAll(List.of("guard_version_positive", "request_pkey", "request_scope_unique",
                "request_creation_order_unique", "request_counters", "request_publication_pair", "request_publication_unique",
                "body_request_scope", "body_payload_retention", "fact_request_scope", "fact_key", "fact_revision_unique",
                "fact_previous_state", "fact_counters", "fact_transition", "request_history_tail", "operation_receipt_key",
                "receipt_result_scope", "receipt_counters", "receipt_expiry", "receipt_result", "receipt_publication_pair")),
                "catalog did not contain all expected named constraints");
        require(Boolean.TRUE.equals(admin.queryForObject("""
                SELECT condeferrable AND condeferred FROM pg_constraint
                WHERE conrelid = 'requests.request'::regclass AND conname = 'request_history_tail'
                """, Boolean.class)), "request/history reference must be initially deferred");
        migrations.migrate();
        require(List.of(1, 2, 3).equals(versions()) && catalog.equals(catalog()), "migration reapply must be stable");
        if (prior) {
            require(Long.valueOf(7).equals(admin.queryForObject("""
                    SELECT policy_version FROM requests.policy_guard WHERE organization = 'previous-version'
                    """, Long.class)), "V001 guard must survive upgrade and reapply");
        }
        require("preserved predecessor row".equals(admin.queryForObject(
                "SELECT value FROM fixture.marker WHERE id = 817", String.class)),
                "predecessor fixture data must survive upgrade and reapply");
        admin.execute("GRANT USAGE ON SCHEMA requests TO b04_workflow, b04_observer");
        admin.execute("""
                GRANT SELECT, INSERT ON requests.policy_guard, requests.request, requests.request_body,
                    requests.transition_fact, requests.operation_receipt TO b04_workflow
                """);
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA requests TO b04_observer");
        System.out.printf("PASS %s migration V001/V002, reapply, predecessor data and %d catalog constraints%n",
                prior ? "populated V001" : "empty namespace", catalog.size());
    }

    private List<Integer> versions() {
        return admin.queryForList("SELECT version FROM requests.schema_migration ORDER BY version", Integer.class);
    }

    private Map<String, Object> catalog() {
        return admin.query("""
                SELECT conname, pg_get_constraintdef(oid)
                FROM pg_constraint WHERE connamespace = 'requests'::regnamespace ORDER BY conname
                """, rows -> {
            var constraints = new java.util.LinkedHashMap<String, Object>();
            while (rows.next()) {
                constraints.put(rows.getString(1), rows.getString(2));
            }
            return constraints;
        });
    }

    private void verifyOwnership(DataSource migration, DataSource observerSource) throws SQLException {
        require("b04_admin".equals(admin.queryForObject("SELECT current_user", String.class)), "migration identity");
        require("b04_observer".equals(observer.queryForObject("SELECT current_user", String.class)), "observer identity");
        require(Boolean.TRUE.equals(admin.queryForObject("""
                SELECT bool_and(tableowner = 'b04_admin') FROM pg_tables WHERE schemaname = 'requests'
                """, Boolean.class)), "only the migration identity owns the tables");
        expectSql("42501", null, () -> command.execute("CREATE TABLE requests.forbidden (id integer)"));
        expectSql("42501", null, () -> command.execute("SELECT * FROM requests.schema_migration"));
        expectSql("42501", null, () -> command.execute("UPDATE requests.request SET name = 'forbidden'"));
        expectSql("42501", null, () -> command.execute("DELETE FROM requests.operation_receipt"));
        expectSql("42501", null, () -> observer.execute("INSERT INTO requests.policy_guard VALUES ('forbidden', 1)"));
        mustReject(() -> workflow.find(ORG));
        mustReject(() -> new JdbcTransactionRunner(migration).run(() -> runner.run(() -> null)));
        runner.run(() -> {
            mustReject(() -> runner.run(() -> null));
            mustReject(() -> new JdbcWorkflowRecords(observerSource).find(ORG));
            return null;
        });
        try (Connection existing = commandDataSource.getConnection()) {
            TransactionSynchronizationManager.bindResource(commandDataSource, new ConnectionHolder(existing));
            try {
                mustReject(() -> runner.run(() -> null));
            } finally {
                TransactionSynchronizationManager.unbindResource(commandDataSource);
            }
        }
        System.out.println("PASS distinct migration/command/observer rights, no ambient or nested command transaction");
    }

    private void verifySecondaryConnection(DataSource commandSource) {
        var secondaryDataSource = new TrackingDataSource(commandSource);
        var secondary = new JdbcTemplate(secondaryDataSource);
        var secondaryRecords = new JdbcWorkflowRecords(secondaryDataSource);
        var secondaryReceipts = new JdbcReceiptStore(secondaryDataSource);
        var ownerOrg = new OrganizationId("ownership-rollback");
        var secondaryOrg = new OrganizationId("ownership-secondary");
        boolean[] rejected = {false};
        Fault injected = new Fault();
        try {
            runner.run(() -> {
                workflow.insert(new PolicyGuard(ownerOrg, 1));
                Integer backend = secondary.queryForObject("SELECT pg_backend_pid()", Integer.class);
                boolean autocommit = Boolean.TRUE.equals(secondary.execute((ConnectionCallback<Boolean>) Connection::getAutoCommit));
                require(backend != null && backend != identity().backend() && autocommit
                                && TransactionSynchronizationManager.hasResource(secondaryDataSource),
                        "a distinct autocommit connection must be synchronized by the read");
                System.out.printf("SECONDARY read bound backend=%d autocommit=%s%n", backend, autocommit);
                try {
                    secondaryRecords.insert(new PolicyGuard(secondaryOrg, 1));
                } catch (IllegalStateException expected) {
                    rejected[0] = true;
                }
                if (rejected[0]) {
                    mustReject(() -> secondaryRecords.find(secondaryOrg));
                    mustReject(() -> secondaryReceipts.find(new ReceiptKey("issuer", secondaryOrg, "principal", "token")));
                }
                throw injected;
            });
            throw new AssertionError("ownership probe returned success after injected rollback");
        } catch (Fault actual) {
            require(actual == injected, "ownership probe must roll back through the runner");
        }
        List<String> durable = observer.queryForList("""
                SELECT organization FROM requests.policy_guard WHERE organization IN (?, ?) ORDER BY organization
                """, String.class, ownerOrg.value(), secondaryOrg.value());
        System.out.printf("SECONDARY mapper rejected=%s authoritative post-rollback rows=%s%n", rejected[0], durable);
        require(rejected[0] && durable.isEmpty(), "a synchronized secondary data source escaped ownership or rollback");
        new JdbcTransactionRunner(secondaryDataSource).run(() -> {
            require(secondaryRecords.find(secondaryOrg).isEmpty(), "new owner must work after the earlier rollback");
            return null;
        });
        System.out.println("PASS bound secondary mapper rejected, no durable side effect, and next owner admitted");
    }

    private void verifyEffects(boolean prior) {
        String before = snapshot();
        var operation = creation(1, ORG);
        var receipt = createReceipt(ORG, "token-1", operation.request().ref());
        int opens = commandDataSource.opens;
        TxIdentity tx = runner.run(() -> {
            TxIdentity identity = identity();
            workflow.insert(new PolicyGuard(ORG, 3));
            workflow.insert(new PolicyGuard(OTHER, 4));
            persistCreate(operation, receipt, identity);
            require(workflow.find(ORG).orElseThrow().policyVersion() == 3, "guard mapping");
            require(workflow.find(ORG, operation.request().ref()).orElseThrow().equals(operation.request()), "request mapping");
            require(workflow.findRetainedBody(ORG, operation.request().ref()).orElseThrow().equals(operation.body()), "body mapping");
            require(workflow.findFacts(ORG, operation.request().ref()).equals(List.of(operation.transition())), "fact mapping");
            require(receipts.find(receipt.key()).orElseThrow().equals(receipt), "receipt mapping");
            require(before.equals(snapshot()), "uncommitted rows visible to observer");
            return identity;
        });
        require(commandDataSource.opens == opens + 1, "successful writes must share exactly one open command connection");
        require(observer.queryForObject("SELECT pg_backend_pid()", Integer.class) != tx.backend(), "observer must use another backend");
        String after = snapshot();
        require(!before.equals(after), "observer snapshot did not change after confirmed commit");
        List<Long> expected = List.of(prior ? 3L : 2L, 1L, 1L, 1L, 1L);
        require(expected.equals(counts()), "commit added wrong number of guard/request/body/fact/receipt rows");
        require(Boolean.TRUE.equals(observer.queryForObject("""
                SELECT r.state = f.to_state AND r.last_sequence = f.sequence AND r.revision = f.revision
                FROM requests.request r JOIN requests.transition_fact f ON f.ref = r.ref
                """, Boolean.class)), "committed request and creation fact must agree");
        byte[][] exact = observer.queryForObject("""
                SELECT convert_to(r.name, 'UTF8'), convert_to(b.body, 'UTF8'), convert_to(f.actor_ref, 'UTF8')
                FROM requests.request r JOIN requests.request_body b USING (ref) JOIN requests.transition_fact f USING (ref)
                """, (row, index) -> new byte[][] {row.getBytes(1), row.getBytes(2), row.getBytes(3)});
        byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);
        require(exact != null && Arrays.stream(exact).allMatch(value -> Arrays.equals(utf8, value)), "multilingual UTF-8 bytes changed");
        System.out.printf("PASS committed %s counts=%s exact UTF8=%d bytes in name/body/actor%n", tx, counts(), utf8.length);

        var faulted = creation(2, FAULT_ORG);
        var faultReceipt = createReceipt(FAULT_ORG, "token-2", faulted.request().ref());
        opens = commandDataSource.opens;
        Fault injected = new Fault();
        try {
            runner.run(() -> {
                TxIdentity identity = identity();
                workflow.insert(new PolicyGuard(FAULT_ORG, 1));
                persistCreate(faulted, faultReceipt, identity);
                require(receipts.find(faultReceipt.key()).isPresent() && workflow.find(FAULT_ORG).isPresent(),
                        "fault must occur after real writes");
                require(after.equals(snapshot()), "observer saw faulted work before transaction end");
                System.out.println("FAULT precommit after guard/request/body/fact/receipt " + identity);
                throw injected;
            });
            throw new AssertionError("faulted transaction returned success");
        } catch (Fault actual) {
            require(actual == injected, "original callback failure must escape");
        }
        require(commandDataSource.opens == opens + 1, "faulted writes must share exactly one open command connection");
        require(after.equals(snapshot()) && expected.equals(counts()), "forced rollback changed authoritative rows");
        System.out.printf("PASS rollback left exact authoritative rows unchanged counts=%s%n", counts());

        var unpaired = creation(3, ORG);
        try {
            runner.run(() -> {
                workflow.insert(unpaired.request());
                return null;
            });
            throw new AssertionError("commit without a matching creation fact unexpectedly succeeded");
        } catch (CommitOutcomeUnknownException failure) {
            verifySql(failure, "23503", "request_history_tail");
        }
        require(after.equals(snapshot()), "rejected commit left rows");

        expectSql("25P02", null, () -> runner.run(() -> {
            workflow.insert(new PolicyGuard(FAULT_ORG, 1));
            // PostgreSQL aborts the transaction even though the callback handles the SQL exception.
            expectSql("23505", "policy_guard_pkey", () -> workflow.insert(new PolicyGuard(ORG, 1)));
            return null;
        }));
        require(after.equals(snapshot()), "caught SQL error changed authoritative rows");
        System.out.println("PASS transaction aborted by a caught SQL error could not return success");

        runner.run(() -> {
            require(workflow.find(OTHER, operation.request().ref()).isEmpty()
                            && workflow.findRetainedBody(OTHER, operation.request().ref()).isEmpty()
                            && workflow.findFacts(OTHER, operation.request().ref()).isEmpty()
                            && receipts.find(new ReceiptKey("issuer", OTHER, "principal", "token-1")).isEmpty(),
                    "storage lookup crossed organization");
            return null;
        });
    }

    private void persistCreate(Lifecycle.Created created, OperationReceipt receipt, TxIdentity identity) {
        workflow.insert(created.request());
        require(identity.equals(identity()), "request changed command connection or transaction");
        workflow.insertBody(created.request().organization(), created.request().ref(), created.body());
        require(identity.equals(identity()), "body changed command connection or transaction");
        workflow.appendFact(created.request().organization(), created.transition());
        require(identity.equals(identity()), "fact changed command connection or transaction");
        receipts.insert(receipt);
        require(identity.equals(identity()), "receipt changed command connection or transaction");
    }

    private void verifyIntegrity() {
        String before = snapshot();
        var fresh = creation(3, ORG);
        var existing = creation(1, ORG);
        deny("23505", "request_pkey", () -> rawRequest(1, 9, 1, 1, "CREATED", null, null));
        deny("23505", "request_creation_order_unique", () -> rawRequest(3, 1, 1, 1, "CREATED", null, null));
        deny("23505", "operation_receipt_key", () -> receipts.insert(createReceipt(ORG, "token-1", existing.request().ref())));
        deny("23503", "body_request_scope", () -> {
            workflow.insert(fresh.request());
            workflow.insertBody(OTHER, fresh.request().ref(), fresh.body());
        });
        deny("23503", "fact_request_scope", () -> {
            workflow.insert(fresh.request());
            workflow.appendFact(OTHER, fresh.transition());
        });
        deny("23503", "receipt_result_scope", () -> receipts.insert(createReceipt(OTHER, "token-3", existing.request().ref())));
        deny("23514", "guard_version_positive", () -> command.update("INSERT INTO requests.policy_guard VALUES ('bad', 0)"));
        deny("23514", "request_creation_order_positive", () -> rawRequest(3, 0, 1, 1, "CREATED", null, null));
        deny("23514", "request_counters", () -> rawRequest(3, 3, 0, 1, "CREATED", null, null));
        deny("23514", "request_counters", () -> rawRequest(3, 3, 1, 2, "VERIFIED", null, null));
        deny("23514", "fact_counters", () -> rawFact(existing.request().ref(), 2, 1, "VERIFY", "CREATED", "VERIFIED"));
        deny("23505", "fact_revision_unique", () -> {
            rawFact(existing.request().ref(), 2, 3, "VERIFY", "CREATED", "VERIFIED");
            rawFact(existing.request().ref(), 3, 3, "ACCEPT", "VERIFIED", "ACCEPTED");
        });
        deny("23503", "fact_previous_state", () -> rawFact(existing.request().ref(), 3, 3, "ACCEPT", "VERIFIED", "ACCEPTED"));
        deny("23514", "receipt_counters", () -> command.update("""
                INSERT INTO requests.operation_receipt
                SELECT issuer, organization, principal, 'invalid-count', intent_digest_version, intent_digest, 0,
                    expires_epoch_second, expires_nano, result_action, result_ref, result_revision, result_state,
                    publication_namespace, publication_id, original_status
                FROM requests.operation_receipt
                """));
        deny("23514", "request_publication_pair", () -> rawRequest(3, 3, 4, 4, "PUBLISHED", null, 9L));
        deny("23514", "request_publication_pair", () -> rawRequest(3, 3, 4, 4, "PUBLISHED", "initial-requests", null));
        deny("23514", "request_publication_pair", () -> rawRequest(3, 3, 4, 4, "PUBLISHED", "initial-requests", 0L));
        deny("23514", "request_publication_pair", () -> rawRequest(3, 3, 1, 1, "CREATED", "initial-requests", 9L));
        deny("23514", "receipt_publication_pair", () -> command.update("""
                INSERT INTO requests.operation_receipt
                SELECT issuer, organization, principal, 'invalid-pair', intent_digest_version, intent_digest, key_generation,
                    expires_epoch_second, expires_nano, result_action, result_ref, result_revision, result_state,
                    'initial-requests', 9, original_status
                FROM requests.operation_receipt
                """));
        require(before.equals(snapshot()), "negative constraint probes must leave the exact prior rows");
        System.out.println("PASS SQLSTATE 23503 scope/sequence, 23505 uniqueness and 23514 counters/publication checks");

        var id = new PublicationId(Long.MAX_VALUE);
        var verified = Lifecycle.transition(fresh.request(), new Lifecycle.Verify(), OCCURRENCE);
        var accepted = Lifecycle.transition(verified.request(), new Lifecycle.Accept(), OCCURRENCE);
        var published = Lifecycle.transition(accepted.request(), new Lifecycle.Publish(id), OCCURRENCE);
        Request request = published.request();
        var result = new OperationReceipt(new ReceiptKey("issuer", ORG, "principal", "publish-token"),
                new ReceiptIntentDigest(7, "00ff802b"), 4, NOW.plusSeconds(60), Action.PUBLISH,
                request.ref(), request.revision(), request.state(), Optional.of(id), 200);
        runner.run(() -> {
            workflow.insert(request);
            workflow.insertBody(ORG, request.ref(), fresh.body());
            for (TransitionFact fact : List.of(fresh.transition(), verified.transition(), accepted.transition(), published.transition())) {
                workflow.appendFact(ORG, fact);
            }
            receipts.insert(result);
            require(workflow.find(ORG, request.ref()).orElseThrow().equals(request), "publication request mapping");
            require(receipts.find(result.key()).orElseThrow().equals(result), "publication receipt mapping");
            return null;
        });
        deny("23505", "request_publication_unique", () -> command.update("""
                INSERT INTO requests.request (
                    ref, organization, request_type, originating_person_id, name, created_epoch, created_ordinal,
                    state, revision, last_sequence, publication_namespace, publication_id
                ) VALUES (?, 'other', 'BUG_REPORT', 'person', 'publication duplicate', 1, 4,
                    'PUBLISHED', 4, 4, 'initial-requests', ?)
                """, ref(4).value(), Long.MAX_VALUE));
        require(Long.valueOf(Long.MAX_VALUE).equals(observer.queryForObject("""
                SELECT publication_id FROM requests.request WHERE state = 'PUBLISHED'
                """, Long.class)), "observer must see exactly one full-width publication number");
        System.out.println("PASS PUBLISHED/request + original receipt preserve 9223372036854775807; namespace unique across organizations");
    }

    private void rawRequest(int refIndex, int ordinal, long revision, long sequence, String state, String namespace, Long id) {
        command.update("""
                INSERT INTO requests.request (
                    ref, organization, request_type, originating_person_id, name, created_epoch, created_ordinal,
                    revision, last_sequence, state, publication_namespace, publication_id
                ) VALUES (?, 'local', 'BUG_REPORT', 'person', 'negative probe', 1, ?, ?, ?, ?, ?, ?)
                """, ref(refIndex).value(), ordinal, revision, sequence, state, namespace, id);
    }

    private void rawFact(RequestRef ref, long sequence, long revision, String action, String from, String to) {
        command.update("""
                INSERT INTO requests.transition_fact (
                    ref, organization, sequence, revision, action, from_state, to_state, recorded_epoch_second,
                    recorded_nano, actor_ref
                ) VALUES (?, 'local', ?, ?, ?, ?, ?, 0, 0, 'actor')
                """, ref.value(), sequence, revision, action, from, to);
    }

    private TxIdentity identity() {
        return command.queryForObject("""
                SELECT pg_backend_pid(), pg_current_xact_id()::text, current_user, current_setting('transaction_isolation')
                """, (row, index) -> {
            require("b04_workflow".equals(row.getString(3)), "command must run as least-privilege command identity");
            require("read committed".equals(row.getString(4)), "command isolation must be READ COMMITTED");
            return new TxIdentity(row.getInt(1), row.getString(2));
        });
    }

    private String snapshot() {
        return observer.queryForObject("""
                SELECT jsonb_build_array(
                    (SELECT jsonb_agg(to_jsonb(g) ORDER BY organization) FROM requests.policy_guard g),
                    (SELECT jsonb_agg(to_jsonb(r) ORDER BY ref) FROM requests.request r),
                    (SELECT jsonb_agg(to_jsonb(b) ORDER BY ref) FROM requests.request_body b),
                    (SELECT jsonb_agg(to_jsonb(f) ORDER BY ref, sequence) FROM requests.transition_fact f),
                    (SELECT jsonb_agg(to_jsonb(o) ORDER BY issuer, organization, principal, token_id) FROM requests.operation_receipt o)
                )::text
                """, String.class);
    }

    private List<Long> counts() {
        return observer.queryForObject("""
                SELECT (SELECT count(*) FROM requests.policy_guard), (SELECT count(*) FROM requests.request),
                    (SELECT count(*) FROM requests.request_body), (SELECT count(*) FROM requests.transition_fact),
                    (SELECT count(*) FROM requests.operation_receipt)
                """, (row, index) -> List.of(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5)));
    }

    private void deny(String state, String constraint, Runnable write) {
        expectSql(state, constraint, () -> runner.run(() -> {
            write.run();
            return null;
        }));
    }

    private static void expectSql(String state, String constraint, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException failure) {
            verifySql(failure, state, constraint);
            return;
        }
        throw new AssertionError("SQL violation was accepted: " + (constraint == null ? state : constraint));
    }

    private static void verifySql(Throwable failure, String state, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                require(state.equals(sql.getSQLState()), "unexpected SQLSTATE for " + constraint + ": " + sql.getSQLState());
                require(constraint == null || sql.getMessage().contains("\"" + constraint + "\""), "wrong constraint: " + constraint);
                return;
            }
        }
        throw new AssertionError("missing SQLSTATE " + state);
    }

    private static void mustReject(Runnable call) {
        try {
            call.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("command transaction ownership violation was accepted");
    }

    private static Lifecycle.Created creation(int i, OrganizationId org) {
        return Lifecycle.create(new Lifecycle.Creation(ref(i), RequestType.BUG_REPORT, org, TEXT,
                new RequestBody(TEXT), new CreationOrder(1, i), new PersonId("originating-person")), OCCURRENCE);
    }

    private static RequestRef ref(int i) {
        return new RequestRef(String.format(java.util.Locale.ROOT, "%032d", i));
    }

    private static OperationReceipt createReceipt(OrganizationId org, String token, RequestRef ref) {
        return new OperationReceipt(new ReceiptKey("issuer", org, "principal", token),
                new ReceiptIntentDigest(7, "00ff802b"), 4, NOW.plusSeconds(60), Action.CREATE,
                ref, 1, RequestState.CREATED, Optional.empty(), 201);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record TxIdentity(int backend, String xid) { }

    private static final class Fault extends RuntimeException { }

    private static final class TrackingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private int opens;

        private TrackingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            opens++;
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("Fixture command role is preconfigured.");
        }
    }
}
