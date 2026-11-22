package com.company.requests.adapter.jdbc;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcWorkflowRecords implements RequestStore, PolicyGuardStore {
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

    static Optional<PublicationId> publicationId(ResultSet row) throws SQLException {
        return Optional.ofNullable(row.getObject("publication_id", Long.class)).map(PublicationId::new);
    }
}
