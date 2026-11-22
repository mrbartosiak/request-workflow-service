package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.application.ExistingRequestStore;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.PolicyGuardStore;
import com.company.requests.application.RequestStore;
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
import com.company.requests.domain.TransitionReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcWorkflowRecords implements RequestStore, ExistingRequestStore, PolicyGuardStore {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public JdbcWorkflowRecords(DataSource commandDataSource) {
        dataSource = commandDataSource;
        jdbc = new JdbcTemplate(commandDataSource);
    }

    @Override
    public void insert(Request request) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        jdbc.update("""
                INSERT INTO requests.request (
                    ref, organization, request_type, originating_person_id, name, created_epoch, created_ordinal,
                    state, revision, last_sequence, publication_namespace, publication_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.ref().value(), request.organization().value(), request.type().name(),
                request.originatingPersonId().value(), request.name(), request.creationOrder().epoch(),
                request.creationOrder().ordinal(), request.state().name(), request.revision(), request.lastSequence(),
                request.publicationId().map(id -> PublicationId.INITIAL_NAMESPACE).orElse(null),
                request.publicationId().map(PublicationId::value).orElse(null));
    }

    @Override
    public void insertBody(OrganizationId organization, RequestRef ref, RequestBody body) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        jdbc.update("INSERT INTO requests.request_body (organization, ref, body) VALUES (?, ?, ?)",
                organization.value(), ref.value(), body.value());
    }

    @Override
    public void lockForChange(PolicyGuard guard, RequestRef ref, Field material) {
        JdbcTransactionRunner.requireExclusiveGuard(dataSource, guard);
        var organization = guard.organization().value();
        var locked = jdbc.queryForList("""
                SELECT 1 FROM requests.request
                WHERE organization = ? AND ref = ? FOR UPDATE
                """, Integer.class, organization, ref.value());
        if (locked.size() != 1) {
            throw new AuthorityUnavailableException();
        }
        if (material == Field.BODY && jdbc.queryForList("""
                SELECT 1 FROM requests.request_body
                WHERE organization = ? AND ref = ? AND retention_status = 'RETAINED' FOR UPDATE
                """, Integer.class, organization, ref.value()).size() != 1) {
            throw new AuthorityUnavailableException();
        }
    }

    @Override
    public void advance(PolicyGuard guard, Request before, Request after) {
        JdbcTransactionRunner.requireExclusiveGuard(dataSource, guard);
        if (!guard.organization().equals(before.organization()) || !before.organization().equals(after.organization())
                || !before.ref().equals(after.ref())
                || after.revision() != Math.incrementExact(before.revision())) {
            throw new IllegalArgumentException("The lifecycle update must advance the same scoped request.");
        }
        int updated;
        if (before.state() == RequestState.ACCEPTED && after.state() == RequestState.PUBLISHED) {
            JdbcPublishGate.requireHeld(dataSource, guard, before.ref(), after.revision());
            updated = jdbc.update("""
                    UPDATE requests.request SET state = ?, revision = ?, last_sequence = ?,
                        publication_namespace = ?, publication_id = ?
                    WHERE organization = ? AND ref = ? AND state = ? AND revision = ? AND last_sequence = ?
                      AND publication_id IS NULL
                    """, after.state().name(), after.revision(), after.lastSequence(), PublicationId.INITIAL_NAMESPACE,
                    after.publicationId().orElseThrow().value(), guard.organization().value(), before.ref().value(),
                    before.state().name(), before.revision(), before.lastSequence());
        } else {
            if (!before.publicationId().equals(after.publicationId())) {
                throw new IllegalArgumentException("Only Publish may assign a publication ID.");
            }
            updated = jdbc.update("""
                    UPDATE requests.request SET state = ?, revision = ?, last_sequence = ?
                    WHERE organization = ? AND ref = ? AND state = ? AND revision = ? AND last_sequence = ?
                    """, after.state().name(), after.revision(), after.lastSequence(), guard.organization().value(),
                    before.ref().value(), before.state().name(), before.revision(), before.lastSequence());
        }
        if (updated != 1) {
            throw new AuthorityUnavailableException();
        }
    }

    @Override
    public void replaceBody(PolicyGuard guard, RequestRef ref, RequestBody body) {
        JdbcTransactionRunner.requireExclusiveGuard(dataSource, guard);
        if (jdbc.update("""
                UPDATE requests.request_body SET body = ?
                WHERE organization = ? AND ref = ? AND retention_status = 'RETAINED'
                """, body.value(), guard.organization().value(), ref.value()) != 1) {
            throw new AuthorityUnavailableException();
        }
    }

    @Override
    public void appendFact(OrganizationId organization, TransitionFact fact) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        Instant recordedAt = fact.occurrence().recordedAt();
        jdbc.update("""
                INSERT INTO requests.transition_fact (
                    organization, ref, sequence, revision, action, from_state, to_state,
                    recorded_epoch_second, recorded_nano, actor_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organization.value(), fact.ref().value(), fact.sequence(), fact.revision(), fact.action().name(),
                fact.from() == null ? null : fact.from().name(), fact.to().name(), recordedAt.getEpochSecond(),
                recordedAt.getNano(), fact.occurrence().actorRef());
    }

    @Override
    public void appendReason(PolicyGuard guard, TransitionFact fact, TransitionReason reason) {
        JdbcTransactionRunner.requireExclusiveGuard(dataSource, guard);
        jdbc.update("""
                INSERT INTO requests.transition_reason (organization, ref, sequence, terminal_state, reason)
                VALUES (?, ?, ?, ?, ?)
                """, guard.organization().value(), fact.ref().value(), fact.sequence(), fact.to().name(),
                reason.value());
    }

    @Override
    public Optional<Request> find(OrganizationId organization, RequestRef ref) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        return jdbc.query("""
                SELECT request_type, originating_person_id, name, created_epoch, created_ordinal,
                       state, revision, last_sequence, publication_id
                FROM requests.request WHERE organization = ? AND ref = ?
                """, (row, index) -> new Request(ref, RequestType.valueOf(row.getString("request_type")), organization,
                row.getString("name"), RequestState.valueOf(row.getString("state")), row.getLong("revision"),
                row.getLong("last_sequence"), new CreationOrder(row.getLong("created_epoch"), row.getLong("created_ordinal")),
                new PersonId(row.getString("originating_person_id")), publicationId(row)),
                organization.value(), ref.value()).stream().findFirst();
    }

    @Override
    public Optional<PublishedTarget> findPublishedTarget(PolicyGuard guard, RequestRef ref) {
        JdbcTransactionRunner.requireGuard(dataSource, guard);
        return jdbc.query("""
                SELECT request_type, revision, publication_id FROM requests.request
                WHERE organization = ? AND ref = ? AND state = 'PUBLISHED' AND publication_namespace = ?
                """, (row, index) -> new PublishedTarget(guard.organization(), ref,
                RequestType.valueOf(row.getString("request_type")), row.getLong("revision"),
                new PublicationId(row.getLong("publication_id"))),
                guard.organization().value(), ref.value(), PublicationId.INITIAL_NAMESPACE).stream().findFirst();
    }

    @Override
    public Optional<RequestBody> findRetainedBody(OrganizationId organization, RequestRef ref) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        return jdbc.query("""
                SELECT body FROM requests.request_body
                WHERE organization = ? AND ref = ? AND retention_status = 'RETAINED'
                """, (row, index) -> new RequestBody(row.getString("body")),
                organization.value(), ref.value()).stream().findFirst();
    }

    @Override
    public List<TransitionFact> findFacts(OrganizationId organization, RequestRef ref) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        return List.copyOf(jdbc.query("""
                SELECT sequence, revision, action, from_state, to_state, recorded_epoch_second, recorded_nano, actor_ref
                FROM requests.transition_fact WHERE organization = ? AND ref = ? ORDER BY sequence
                """, (row, index) -> fact(ref, row), organization.value(), ref.value()));
    }

    @Override
    public List<HistoryFact> findHistory(PolicyGuard guard, RequestRef ref) {
        JdbcTransactionRunner.requireGuard(dataSource, guard);
        return List.copyOf(jdbc.query("""
                SELECT sequence, revision, action, from_state, to_state
                FROM requests.transition_fact WHERE organization = ? AND ref = ? ORDER BY sequence
                """, (row, index) -> historyFact(row), guard.organization().value(), ref.value()));
    }

    @Override
    public void insert(PolicyGuard guard) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        jdbc.update("INSERT INTO requests.policy_guard (organization, policy_version) VALUES (?, ?)",
                guard.organization().value(), guard.policyVersion());
    }

    @Override
    public Optional<PolicyGuard> find(OrganizationId organization) {
        return readGuard(organization, "");
    }

    @Override
    public Optional<PolicyGuard> lockShared(OrganizationId organization) {
        var locked = lockGuard(organization, " FOR SHARE");
        locked.ifPresent(guard -> JdbcTransactionRunner.rememberSharedGuard(dataSource, guard));
        return locked;
    }

    @Override
    public Optional<PolicyGuard> lockExclusive(OrganizationId organization) {
        var locked = lockGuard(organization, " FOR UPDATE");
        locked.ifPresent(guard -> JdbcTransactionRunner.rememberExclusiveGuard(dataSource, guard));
        return locked;
    }

    /** Share-lock a guard through the worker role's narrow lock function. */
    public Optional<PolicyGuard> lockDispatchGuard(OrganizationId organization) {
        JdbcTransactionRunner.requireWorkerConnection(dataSource);
        var locked = jdbc.query("SELECT policy_version FROM requests.lock_dispatch_guard(?)",
                (row, index) -> new PolicyGuard(organization, row.getLong("policy_version")),
                organization.value()).stream().findFirst();
        locked.ifPresent(guard -> JdbcTransactionRunner.rememberSharedGuard(dataSource, guard));
        return locked;
    }

    private Optional<PolicyGuard> lockGuard(OrganizationId organization, String lockClause) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        jdbc.execute("SET LOCAL lock_timeout = '200ms'");
        return readGuard(organization, lockClause);
    }

    private Optional<PolicyGuard> readGuard(OrganizationId organization, String lockClause) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        return jdbc.query("SELECT policy_version FROM requests.policy_guard WHERE organization = ?" + lockClause,
                (row, index) -> new PolicyGuard(organization, row.getLong("policy_version")),
                organization.value()).stream().findFirst();
    }

    private static TransitionFact fact(RequestRef ref, ResultSet row) throws SQLException {
        String from = row.getString("from_state");
        Instant recorded = Instant.ofEpochSecond(row.getLong("recorded_epoch_second"), row.getInt("recorded_nano"));
        return new TransitionFact(ref, row.getLong("sequence"), row.getLong("revision"),
                Action.valueOf(row.getString("action")), from == null ? null : RequestState.valueOf(from),
                RequestState.valueOf(row.getString("to_state")),
                new TransitionFact.Occurrence(recorded, row.getString("actor_ref")));
    }

    private static HistoryFact historyFact(ResultSet row) throws SQLException {
        var from = row.getString("from_state");
        try {
            return new HistoryFact(row.getLong("sequence"), row.getLong("revision"),
                    Action.valueOf(row.getString("action")), from == null ? null : RequestState.valueOf(from),
                    RequestState.valueOf(row.getString("to_state")));
        } catch (IllegalArgumentException invalidFact) {
            throw new AuthorityUnavailableException();
        }
    }

    static Optional<PublicationId> publicationId(ResultSet row) throws SQLException {
        return Optional.ofNullable(row.getObject("publication_id", Long.class)).map(PublicationId::new);
    }
}
