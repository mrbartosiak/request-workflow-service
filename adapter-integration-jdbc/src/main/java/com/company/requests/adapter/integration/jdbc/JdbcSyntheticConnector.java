package com.company.requests.adapter.integration.jdbc;

import com.company.requests.integration.DispatchStore.Invocation;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Contract;
import com.company.requests.integration.SyntheticDelivery.Coverage;
import com.company.requests.integration.SyntheticDelivery.Effect;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.Exclusion;
import com.company.requests.integration.SyntheticDelivery.NoApplication;
import com.company.requests.integration.SyntheticDelivery.Observation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local synthetic endpoint. Each key serializes arrival, async completion, fencing and lookup. */
public final class JdbcSyntheticConnector implements Connector {
    public enum Send { APPLY, LOSE_REPLY_AFTER_APPLY, LOSE_REPLY_BEFORE_APPLY, ACCEPT, THROTTLE, REJECT }
    public enum View { AUTHORITATIVE, STALE, PARTIAL }

    private enum State { PENDING, APPLIED, DEDUPED, FENCED, TRANSIENT, PERMANENT }

    private static final String KEY = "destination = :destination AND tenant = :tenant AND effect_id = :effect";

    private final DataSource remote;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String, Contract> contracts;
    private final UUID endpoint;

    public JdbcSyntheticConnector(DataSource remote, Map<String, Contract> contracts) {
        this.remote = Objects.requireNonNull(remote);
        jdbc = new NamedParameterJdbcTemplate(remote);
        transactions = new TransactionTemplate(new JdbcTransactionManager(remote));
        this.contracts = Map.copyOf(contracts);
        endpoint = transaction(this::currentEndpoint);
    }

    public UUID endpoint() {
        return endpoint;
    }

    @Override
    public Optional<Evidence> send(Invocation invocation) {
        return send(invocation, Send.APPLY);
    }

    public Optional<Evidence> send(Invocation invocation, Send scenario) {
        Objects.requireNonNull(scenario);
        return transaction(() -> {
            var key = lock(invocation);
            if (scenario == Send.LOSE_REPLY_BEFORE_APPLY) {
                return Optional.empty();
            }
            if (state(key).isEmpty()) {
                var next = switch (scenario) {
                    case ACCEPT -> State.PENDING;
                    case THROTTLE -> State.TRANSIENT;
                    case REJECT -> State.PERMANENT;
                    default -> State.APPLIED;
                };
                insert(key, key.fencedThrough() >= invocation.id().number() ? State.FENCED : next);
            }
            if (scenario == Send.LOSE_REPLY_AFTER_APPLY) {
                return Optional.empty();
            }
            return observe(invocation, key);
        });
    }

    /** Completes a persisted async acceptance, honoring the same fence and dedupe as a new arrival. */
    public void complete(Invocation invocation) {
        transaction(() -> {
            var key = lock(invocation);
            if (state(key).filter(State.PENDING::equals).isPresent()) {
                var next = applicationState(key);
                key.params().addValue("state", next.name())
                        .addValue("reference", next == State.APPLIED ? UUID.randomUUID() : null);
                jdbc.update("""
                        UPDATE synthetic_delivery.delivery_attempt SET state = :state, effect_ref = :reference
                        WHERE attempt_no = :attempt AND
                        """ + KEY, key.params());
            }
            return null;
        });
    }

    @Override
    public Optional<Evidence> lookup(Invocation invocation, boolean fence) {
        return lookup(invocation, fence, View.AUTHORITATIVE);
    }

    public Optional<Evidence> lookup(Invocation invocation, boolean fence, View view) {
        Objects.requireNonNull(view);
        return transaction(() -> {
            var key = lock(invocation);
            if (view == View.STALE) {
                return previous(invocation, key);
            }
            if (view == View.AUTHORITATIVE && fence && key.contract() == Contract.FENCEABLE
                    && key.fencedThrough() < invocation.id().number()) {
                jdbc.update("UPDATE synthetic_delivery.delivery_key SET fenced_through = :attempt WHERE " + KEY,
                        key.params());
                jdbc.update("""
                        UPDATE synthetic_delivery.delivery_attempt SET state = 'FENCED'
                        WHERE state = 'PENDING' AND attempt_no <= :attempt AND
                        """ + KEY, key.params());
                key = new Key(key.params(), key.contract(), invocation.id().number());
            }
            return observe(invocation, key).map(value -> view == View.PARTIAL
                    ? new Evidence(invocation, endpoint, value.reference(), value.observation(), value.exclusion(),
                            value.effect(), Coverage.PARTIAL, value.noApplication())
                    : value);
        });
    }

    private Optional<Evidence> previous(Invocation invocation, Key key) {
        return jdbc.query("""
                SELECT proof_id, observation, exclusion, effect_ref, applied_attempt, fenced_through, terminal_attempts
                FROM synthetic_delivery.delivery_observation WHERE attempt_no = :attempt AND
                """ + KEY + " ORDER BY recorded_at DESC, proof_id LIMIT 1", key.params(), (row, index) -> {
            var reference = row.getObject("effect_ref", UUID.class);
            var applied = row.getLong("applied_attempt");
            var observation = Observation.valueOf(row.getString("observation"));
            return new Evidence(invocation, endpoint, row.getObject("proof_id", UUID.class),
                    observation, Exclusion.valueOf(row.getString("exclusion")),
                    reference == null ? Optional.empty() : Optional.of(new Effect(reference, applied)), Coverage.STALE,
                    terminal(observation) ? Optional.of(readNoApplication(row)) : Optional.empty());
        }).stream().findFirst();
    }

    private static NoApplication readNoApplication(ResultSet row) throws SQLException {
        var ordinals = row.getArray("terminal_attempts");
        try {
            return new NoApplication(row.getLong("fenced_through"), Set.of((Long[]) ordinals.getArray()));
        } finally {
            ordinals.free();
        }
    }

    private Key lock(Invocation invocation) {
        if (!endpoint.equals(currentEndpoint())) {
            throw new IllegalStateException("Synthetic remote database no longer matches the configured endpoint.");
        }
        var effect = invocation.id().effect();
        var contract = contracts.get(effect.destination());
        if (contract == null) {
            throw new IllegalArgumentException("Synthetic destination is outside the configured connector scope.");
        }
        var params = new MapSqlParameterSource("destination", effect.destination())
                .addValue("tenant", effect.tenant().value()).addValue("effect", effect.effectId())
                .addValue("attempt", invocation.id().number()).addValue("ref", invocation.ref().value())
                .addValue("revision", invocation.revision()).addValue("contract", contract.name());
        jdbc.update("""
                INSERT INTO synthetic_delivery.delivery_key
                    (destination, tenant, effect_id, request_ref, request_revision, contract)
                VALUES (:destination, :tenant, :effect, :ref, :revision, :contract)
                ON CONFLICT (destination, tenant, effect_id) DO NOTHING
                """, params);
        return jdbc.queryForObject("""
                SELECT request_ref, request_revision, contract, fenced_through
                FROM synthetic_delivery.delivery_key WHERE
                """ + KEY + " FOR UPDATE", params, (row, index) -> {
            if (!invocation.ref().value().equals(row.getString("request_ref"))
                    || invocation.revision() != row.getLong("request_revision")
                    || !contract.name().equals(row.getString("contract"))) {
                throw new IllegalStateException("Synthetic destination identity or serving contract changed.");
            }
            return new Key(params, contract, row.getLong("fenced_through"));
        });
    }

    private UUID currentEndpoint() {
        return Objects.requireNonNull(jdbc.getJdbcOperations().queryForObject(
                "SELECT identity FROM synthetic_delivery.endpoint WHERE singleton", UUID.class));
    }

    private void insert(Key key, State state) {
        var next = state == State.APPLIED ? applicationState(key) : state;
        key.params().addValue("state", next.name())
                .addValue("reference", next == State.APPLIED ? UUID.randomUUID() : null);
        jdbc.update("""
                INSERT INTO synthetic_delivery.delivery_attempt
                    (destination, tenant, effect_id, attempt_no, contract, state, effect_ref)
                VALUES (:destination, :tenant, :effect, :attempt, :contract, :state, :reference)
                """, key.params());
    }

    private State applicationState(Key key) {
        if (key.fencedThrough() >= (long) key.params().getValue("attempt")) {
            return State.FENCED;
        }
        return key.contract() == Contract.SINGLE_EFFECT && !applications(key).isEmpty()
                ? State.DEDUPED : State.APPLIED;
    }

    private Optional<State> state(Key key) {
        return jdbc.query("SELECT state FROM synthetic_delivery.delivery_attempt WHERE attempt_no = :attempt AND "
                + KEY, key.params(), (row, index) -> State.valueOf(row.getString("state"))).stream().findFirst();
    }

    private java.util.List<Effect> applications(Key key) {
        return jdbc.query("""
                SELECT effect_ref, attempt_no FROM synthetic_delivery.delivery_attempt WHERE effect_ref IS NOT NULL AND
                """ + KEY + " ORDER BY attempt_no LIMIT 2", key.params(),
                (row, index) -> new Effect(row.getObject("effect_ref", UUID.class), row.getLong("attempt_no")));
    }

    private Optional<Evidence> observe(Invocation invocation, Key key) {
        var applications = applications(key);
        if (applications.size() > 1 || applications.stream().anyMatch(item ->
                item.appliedAttempt() > invocation.id().number())) {
            return Optional.empty();
        }
        var application = applications.stream().findFirst();
        var state = state(key);
        var observation = application.isPresent() ? Observation.CONFIRMED : switch (state.orElse(State.FENCED)) {
            case PENDING -> Observation.ACCEPTED;
            case TRANSIENT -> Observation.TRANSIENT;
            case PERMANENT -> Observation.PERMANENT;
            default -> Observation.ABSENT;
        };
        var exclusion = switch (observation) {
            case CONFIRMED, ACCEPTED -> Exclusion.NONE;
            default -> exclusion(key, invocation.id().number());
        };
        var noApplication = terminal(observation) ? Optional.of(terminalNoApplications(key)) : Optional.<NoApplication>empty();
        var ordinals = noApplication.stream().flatMap(value -> value.terminalAttempts().stream()).sorted().toArray();
        var proof = UUID.randomUUID();
        key.params().addValue("proof", proof).addValue("observation", observation.name())
                .addValue("exclusion", exclusion.name()).addValue("fence", key.fencedThrough())
                .addValue("applied", application.map(Effect::appliedAttempt).orElse(null))
                .addValue("remoteEffect", application.map(Effect::reference).orElse(null))
                .addValue("closed", new SqlArrayValue("bigint", ordinals));
        jdbc.update("""
                INSERT INTO synthetic_delivery.delivery_observation
                    (proof_id, destination, tenant, effect_id, attempt_no, observation, exclusion,
                     effect_ref, applied_attempt, fenced_through, terminal_attempts)
                VALUES (:proof, :destination, :tenant, :effect, :attempt, :observation, :exclusion,
                    :remoteEffect, :applied, :fence, :closed)
                """, key.params());
        return Optional.of(new Evidence(invocation, endpoint, proof, observation, exclusion, application,
                Coverage.AUTHORITATIVE, noApplication));
    }

    private static Exclusion exclusion(Key key, long attempt) {
        if (key.fencedThrough() >= attempt) {
            return Exclusion.FENCED;
        }
        return key.contract() == Contract.SINGLE_EFFECT ? Exclusion.SINGLE_EFFECT : Exclusion.NONE;
    }

    private NoApplication terminalNoApplications(Key key) {
        var closed = jdbc.queryForList("""
                SELECT attempt_no FROM synthetic_delivery.delivery_attempt
                WHERE attempt_no > :fence AND attempt_no <= :attempt AND state IN ('TRANSIENT', 'PERMANENT') AND
                """ + KEY, key.params().addValue("fence", key.fencedThrough()), Long.class);
        return new NoApplication(key.fencedThrough(), Set.copyOf(closed));
    }

    private static boolean terminal(Observation observation) {
        return observation == Observation.TRANSIENT || observation == Observation.PERMANENT;
    }

    private <T> T transaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(remote)) {
            throw new IllegalStateException("Synthetic remote I/O requires a separate transaction boundary.");
        }
        return transactions.execute(status -> {
            jdbc.getJdbcOperations().execute("SET LOCAL synchronous_commit = on");
            return work.get();
        });
    }

    private record Key(MapSqlParameterSource params, Contract contract, long fencedThrough) { }
}
