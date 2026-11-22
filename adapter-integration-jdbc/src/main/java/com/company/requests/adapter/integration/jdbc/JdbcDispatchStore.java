package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.SyntheticDelivery;
import com.company.requests.integration.SyntheticDelivery.Action;
import com.company.requests.integration.SyntheticDelivery.Disposition;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.Exclusion;
import com.company.requests.integration.SyntheticDelivery.Recovery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL worker transactions for one scoped claim, arm, or finalization at a time. */
public final class JdbcDispatchStore implements SyntheticDelivery.Store {
    private static final String KEY = "destination = :destination AND tenant = :tenant AND effect_id = :effect";
    private static final String ATTEMPT = KEY + """
             AND attempt_no = :number AND claim_token = :token AND writer_id = :writer AND generation = :generation
            """;
    private static final String ATTEMPT_JOIN = """
            JOIN requests.outbox_attempt a ON a.destination = o.destination AND a.tenant = o.tenant
                AND a.effect_id = o.effect_id AND a.attempt_no = o.last_attempt_no
            """;
    private static final String NO_BLOCKING_PREDECESSOR = """
            NOT EXISTS (
                SELECT 1 FROM requests.outbox p
                WHERE p.destination = o.destination AND p.tenant = o.tenant AND p.request_ref = o.request_ref
                  AND p.trigger_slot = o.trigger_slot AND p.request_revision < o.request_revision
                  AND (p.status <> 'CONFIRMED' OR NOT EXISTS (
                      SELECT 1 FROM requests.outbox_attempt proof
                      WHERE proof.destination = p.destination AND proof.tenant = p.tenant
                        AND proof.effect_id = p.effect_id
                        AND proof.state = 'CONFIRMED' AND proof.confirmation_ref IS NOT NULL
                ))
            )
            """;

    /** The shared organization guard and qualified proofs use this worker's owning connection. */
    public interface GuardedAccess {
        Optional<PolicyGuard> lock(OrganizationId tenant);

        Admission current(PolicyGuard guard);

        Optional<UUID> originalPublication(PolicyGuard guard, Admission admission, OutboundObligation obligation);

        default Optional<UUID> operatorPublication(PolicyGuard guard, Admission admission, AuthenticatedActor operator,
                                                   OutboundObligation obligation) {
            return Optional.empty();
        }
    }

    private enum State {
        READY, CLAIMED, ARMED, UNKNOWN, CONFIRMED, SUPPRESSED, DEFERRED, EXPIRED_UNSENT,
        ACCEPTED_PENDING, RETRYABLE, FAILED_PERMANENT, QUARANTINED
    }

    private final DataSource workerSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner transactions;
    private final GuardedAccess access;
    private final long leaseMillis;
    private final long deferMillis;
    private final Set<String> syntheticRoutes;
    private final UUID endpoint;
    private final String routes;

    public JdbcDispatchStore(DataSource workerSource, TransactionRunner transactions, GuardedAccess access,
                             Duration lease, Duration deferral) {
        this(workerSource, transactions, access, lease, deferral, Set.of(), null);
    }

    public JdbcDispatchStore(DataSource workerSource, TransactionRunner transactions, GuardedAccess access,
                             Duration lease, Duration deferral, Set<String> syntheticRoutes, UUID endpoint) {
        this.workerSource = Objects.requireNonNull(workerSource);
        jdbc = new NamedParameterJdbcTemplate(workerSource);
        this.transactions = Objects.requireNonNull(transactions);
        this.access = Objects.requireNonNull(access);
        leaseMillis = durationMillis(lease);
        deferMillis = durationMillis(deferral);
        this.syntheticRoutes = Set.copyOf(syntheticRoutes);
        if (this.syntheticRoutes.isEmpty() != (endpoint == null)) {
            throw new IllegalArgumentException("Synthetic dispatch routes require an exact endpoint identity.");
        }
        this.endpoint = endpoint;
        routes = this.syntheticRoutes.isEmpty() ? "" : """
                  AND o.destination IN (:routes) AND (o.delivery_endpoint IS NULL OR o.delivery_endpoint = :endpoint)
                """;
    }

    @Override
    public Optional<Claim> claim(WriterEpoch writer) {
        Objects.requireNonNull(writer);
        return transaction(() -> {
            recoverExpired();
            var statuses = syntheticRoutes.isEmpty() ? "('READY')" : "('READY', 'RETRYABLE')";
            return jdbc.query("""
                    SELECT o.destination, o.tenant, o.effect_id, o.last_attempt_no
                    FROM requests.outbox o
                    WHERE o.status IN
                    """ + statuses + routes + """
                      AND (o.deferred_until IS NULL OR o.deferred_until <= statement_timestamp())
                      AND (o.scheduled_epoch_second, o.scheduled_nano) <= (
                          floor(extract(epoch FROM statement_timestamp())),
                          (extract(epoch FROM statement_timestamp())
                              - floor(extract(epoch FROM statement_timestamp()))) * 1000000000)
                      AND
                    """ + NO_BLOCKING_PREDECESSOR + """
                    ORDER BY o.scheduled_epoch_second, o.scheduled_nano, o.destination, o.tenant, o.effect_id
                    LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                    """, routeParams(new MapSqlParameterSource()),
                    (row, index) -> new Claim(new AttemptId(effectKey(row),
                    Math.incrementExact(row.getLong("last_attempt_no"))), UUID.randomUUID(), writer))
                    .stream().findFirst().map(this::persistClaim);
        });
    }

    @Override
    public ArmResult arm(Claim claim) {
        return transaction(() -> {
            var guard = access.lock(claim.id().effect().tenant());
            return lockActive(claim, State.CLAIMED).map(effect -> guard.map(current -> arm(claim, current, effect))
                    .orElseGet(() -> withhold(claim, State.DEFERRED, ArmState.DEFERRED)))
                    .orElseGet(() -> withheld(ArmState.STALE));
        });
    }

    @Override
    public boolean finish(Claim claim, Optional<Receipt> receipt) {
        if (!syntheticRoutes.isEmpty()) {
            throw new IllegalStateException("Synthetic remote delivery requires durable remote evidence.");
        }
        if (receipt.filter(value -> !claim.id().equals(value.id())).isPresent()) {
            return false;
        }
        return transaction(() -> {
            var guard = access.lock(claim.id().effect().tenant());
            if (guard.isEmpty() || lockActive(claim, State.ARMED).isEmpty()) {
                return false;
            }
            try {
                if (!claim.writer().equals(access.current(guard.orElseThrow()).writer())) {
                    return false;
                }
            } catch (AuthorityUnavailableException unavailable) {
                return false;
            }
            var result = receipt.isPresent() ? State.CONFIRMED : State.UNKNOWN;
            return finishLocked(claim, State.ARMED, result, true, receipt.map(Receipt::proofId).orElse(null));
        });
    }

    @Override
    public Optional<Disposition> finishDelivery(Claim claim, Optional<Evidence> report) {
        requireSynthetic();
        return transaction(() -> {
            var guard = access.lock(claim.id().effect().tenant());
            var locked = lockActive(claim, State.ARMED);
            if (guard.isEmpty() || locked.isEmpty()) {
                return Optional.empty();
            }
            var effect = locked.orElseThrow();
            Admission admission;
            try {
                admission = access.current(guard.orElseThrow());
            } catch (AuthorityUnavailableException unavailable) {
                return Optional.empty();
            }
            if (!claim.writer().equals(admission.writer())) {
                return Optional.empty();
            }
            Optional<Evidence> evidence = Optional.empty();
            try {
                if (!effect.cancelled() && access.originalPublication(guard.orElseThrow(), admission, effect.obligation())
                        .filter(effect.publication()::equals).isPresent()) {
                    evidence = qualified(invocation(claim.id(), effect.obligation()), report);
                }
            } catch (AccessDeniedException | AuthorityUnavailableException revoked) {
                // The invocation was authorized at arm. There is no current authority to accept its outcome.
                evidence = Optional.empty();
            }
            var exclusion = verifiedExclusion(evidence);
            var result = SyntheticDelivery.sent(evidence, exclusion);
            var confirmation = evidence.filter(value -> result == Disposition.CONFIRMED)
                    .map(Evidence::reference).orElse(null);
            if (!finishLocked(claim, State.ARMED, State.valueOf(result.name()), true, confirmation)) {
                return Optional.empty();
            }
            writeObservation(attemptParams(claim), evidence);
            updateRetryRisk(claim.id().effect(), result, exclusion);
            return Optional.of(result);
        });
    }

    @Override
    public Optional<Recovery> startRecovery(WriterEpoch writer, AuthenticatedActor actor, AttemptId id, Action action) {
        requireSynthetic();
        Objects.requireNonNull(action);
        if (!actor.organization().equals(id.effect().tenant()) || !syntheticRoutes.contains(id.effect().destination())) {
            return Optional.empty();
        }
        return transaction(() -> {
            var guard = access.lock(id.effect().tenant());
            return guard.flatMap(value -> lockRecovery(id).filter(effect ->
                    canRecover(value, writer, actor, effect, action)).map(effect -> {
                if (effect.state() == State.ARMED
                        && !finishLocked(effect.claim(), State.ARMED, State.UNKNOWN, false, null)) {
                    throw new IllegalStateException("Expired recovery target changed while locked.");
                }
                var recovery = new Recovery(UUID.randomUUID(), writer, actor, invocation(id, effect.obligation()), action);
                var params = recoveryParams(recovery).addValue("policyVersion", value.policyVersion())
                        .addValue("publication", effect.publication());
                requireOne(jdbc.update("""
                        UPDATE requests.outbox_attempt SET recovery_token = :recovery
                        WHERE attempt_no = :number AND
                        """ + KEY, params));
                requireOne(jdbc.update("""
                        INSERT INTO requests.outbox_delivery_recovery
                            (recovery_id, destination, tenant, effect_id, attempt_no, operator_issuer, operator_principal,
                             writer_id, generation, policy_version, publication_attempt, action)
                        VALUES (:recovery, :destination, :tenant, :effect, :number, :issuer, :operator, :writer,
                             :generation, :policyVersion, :publication, :action)
                        """, params));
                return recovery;
            }));
        });
    }

    @Override
    public boolean finishRecovery(Recovery recovery, Optional<Evidence> report) {
        requireSynthetic();
        var id = recovery.invocation().id();
        var params = recoveryParams(recovery);
        return transaction(() -> {
            var guard = access.lock(id.effect().tenant());
            var locked = guard.flatMap(value -> lockRecovery(id).filter(effect ->
                    recovery.id().equals(effect.token()) && recovery.invocation().equals(invocation(id, effect.obligation()))
                    && canRecover(value, recovery.writer(), recovery.actor(), effect, recovery.action())
                    && recoveryStillAdmitted(params, value, effect.publication())));
            if (locked.isEmpty()) {
                closeRecovery(params, "WITHHELD", null);
                return false;
            }
            var target = locked.orElseThrow();
            var evidence = qualified(recovery.invocation(), report);
            if (evidence.isEmpty() && target.state() == State.ACCEPTED_PENDING) {
                closeRecovery(params, State.ACCEPTED_PENDING.name(), null);
                return true;
            }
            var exclusion = verifiedExclusion(evidence);
            var result = SyntheticDelivery.reconciled(recovery.action(), evidence, exclusion);
            var proof = evidence.map(Evidence::reference).orElse(null);
            var confirmation = result == Disposition.CONFIRMED ? proof : null;
            requireOne(jdbc.update("""
                    UPDATE requests.outbox_attempt SET state = :result, finished_at = clock_timestamp(),
                        confirmation_ref = :confirmation, recovery_token = NULL
                    WHERE attempt_no = :number AND recovery_token = :recovery AND
                    """ + KEY, params.addValue("result", result.name()).addValue("confirmation", confirmation)));
            writeObservation(params, evidence);
            updateLockedEffect(effectParams(id.effect()).addValue("number", target.lastAttempt()),
                    State.valueOf(result.name()), result == Disposition.RETRYABLE ? deferMillis : null);
            updateRetryRisk(id.effect(), result, exclusion);
            closeRecovery(params, result.name(), proof);
            return true;
        });
    }

    private boolean canRecover(PolicyGuard guard, WriterEpoch writer, AuthenticatedActor actor,
                               RecoveryTarget effect, Action action) {
        if (effect.cancelled() && (action == Action.RETRY || action == Action.FENCE_AND_RETRY)) {
            return false;
        }
        try {
            var admission = access.current(guard);
            return writer.equals(admission.writer())
                    && access.originalPublication(guard, admission, effect.obligation())
                        .filter(effect.publication()::equals).isPresent()
                    && access.operatorPublication(guard, admission, actor, effect.obligation())
                        .filter(effect.publication()::equals).isPresent();
        } catch (AccessDeniedException | AuthorityUnavailableException withheld) {
            return false;
        }
    }

    private Optional<RecoveryTarget> lockRecovery(AttemptId id) {
        return jdbc.query("""
                SELECT o.*, a.attempt_no, a.claim_token, a.writer_id, a.generation,
                       a.publication_attempt, a.recovery_token, a.state
                FROM requests.outbox o
                JOIN requests.outbox_attempt a ON a.destination = o.destination AND a.tenant = o.tenant
                    AND a.effect_id = o.effect_id
                WHERE o.destination = :destination AND o.tenant = :tenant AND o.effect_id = :effect
                """ + routes + """
                  AND o.delivery_endpoint = :endpoint AND a.attempt_no = :number AND a.armed_at IS NOT NULL
                  AND (o.status IN ('UNKNOWN', 'ACCEPTED_PENDING', 'QUARANTINED', 'RETRYABLE')
                      OR (o.status = 'ARMED' AND a.state = 'ARMED' AND a.lease_expires_at <= clock_timestamp()))
                  AND NOT EXISTS (
                      SELECT 1 FROM requests.outbox_attempt newer
                      WHERE newer.destination = a.destination AND newer.tenant = a.tenant
                        AND newer.effect_id = a.effect_id AND newer.attempt_no > a.attempt_no
                        AND newer.armed_at IS NOT NULL)
                FOR UPDATE OF o
                """, routeParams(effectParams(id.effect()).addValue("number", id.number())),
                (row, index) -> new RecoveryTarget(obligation(row), new Claim(id,
                        row.getObject("claim_token", UUID.class),
                        new WriterEpoch(row.getObject("writer_id", UUID.class), row.getLong("generation"))),
                        row.getLong("last_attempt_no"), State.valueOf(row.getString("state")),
                        row.getObject("publication_attempt", UUID.class),
                        row.getObject("recovery_token", UUID.class), row.getBoolean("cancelled"))).stream().findFirst();
    }

    private static final String OPEN_RECOVERY = """
            recovery_id = :recovery AND attempt_no = :number AND operator_issuer = :issuer
                AND operator_principal = :operator AND writer_id = :writer AND generation = :generation
                AND action = :action AND finished_at IS NULL AND
            """ + KEY;

    private boolean recoveryStillAdmitted(MapSqlParameterSource params, PolicyGuard guard, UUID publication) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM requests.outbox_delivery_recovery
                  WHERE policy_version = :policyVersion AND publication_attempt = :publication AND
                """ + OPEN_RECOVERY + ")", params.addValue("policyVersion", guard.policyVersion())
                .addValue("publication", publication), Boolean.class));
    }

    private void closeRecovery(MapSqlParameterSource params, String result, UUID proof) {
        jdbc.update("""
                UPDATE requests.outbox_delivery_recovery
                SET disposition = :result, observation_ref = :proof, finished_at = clock_timestamp()
                WHERE
                """ + OPEN_RECOVERY, params.addValue("result", result).addValue("proof", proof));
    }

    private void writeObservation(MapSqlParameterSource params, Optional<Evidence> evidence) {
        var remoteEffect = evidence.flatMap(Evidence::effect);
        requireOne(jdbc.update("""
                UPDATE requests.outbox_attempt SET delivery_observation = :proof, remote_effect = :remoteEffect,
                    remote_attempt_no = :remoteAttempt
                WHERE attempt_no = :number AND
                """ + KEY, params.addValue("proof", evidence.map(Evidence::reference).orElse(null))
                .addValue("remoteEffect", remoteEffect.map(SyntheticDelivery.Effect::reference).orElse(null))
                .addValue("remoteAttempt", remoteEffect.map(SyntheticDelivery.Effect::appliedAttempt).orElse(null))));
    }

    private Optional<Evidence> qualified(Invocation invocation, Optional<Evidence> evidence) {
        return SyntheticDelivery.exact(invocation, evidence).filter(value -> endpoint.equals(value.endpoint()));
    }

    /** Call with the outbox row locked. An attempt number alone does not authorize remote I/O. */
    private Exclusion verifiedExclusion(Optional<Evidence> evidence) {
        return evidence.map(proof -> proof.noApplication().filter(facts -> {
            var id = proof.invocation().id();
            var armed = jdbc.queryForList("""
                    SELECT attempt_no FROM requests.outbox_attempt
                    WHERE attempt_no <= :number AND armed_at IS NOT NULL AND
                    """ + KEY, effectParams(id.effect()).addValue("number", id.number()), Long.class);
            return facts.closesArmedLineage(id.number(), armed);
        }).map(facts -> Exclusion.CLOSED).orElse(proof.exclusion())).orElse(Exclusion.NONE);
    }

    private void updateRetryRisk(EffectKey key, Disposition result, Exclusion exclusion) {
        switch (result) {
            case RETRYABLE, CONFIRMED, FAILED_PERMANENT, SUPPRESSED -> {
                var uncertain = result == Disposition.RETRYABLE && exclusion == Exclusion.SINGLE_EFFECT;
                requireOne(jdbc.update("UPDATE requests.outbox SET retry_uncertain = :uncertain WHERE " + KEY,
                        effectParams(key).addValue("uncertain", uncertain)));
            }
            default -> { }
        }
    }

    private Claim persistClaim(Claim claim) {
        var params = attemptParams(claim).addValue("leaseMillis", leaseMillis);
        requireOne(jdbc.update("""
                INSERT INTO requests.outbox_attempt (destination, tenant, effect_id, attempt_no, claim_token,
                    writer_id, generation, claimed_at, lease_expires_at, state)
                VALUES (:destination, :tenant, :effect, :number, :token, :writer, :generation, statement_timestamp(),
                    statement_timestamp() + :leaseMillis * interval '1 millisecond', 'CLAIMED')
                """, params));
        var statuses = syntheticRoutes.isEmpty() ? "('READY')" : "('READY', 'RETRYABLE')";
        requireOne(jdbc.update("""
                UPDATE requests.outbox SET status = 'CLAIMED', last_attempt_no = :number, deferred_until = NULL
                WHERE status IN
                """ + statuses + " AND " + KEY, params));
        return claim;
    }

    private void recoverExpired() {
        var expired = jdbc.query("""
                SELECT a.destination, a.tenant, a.effect_id, a.attempt_no, a.claim_token, a.writer_id,
                       a.generation, a.state
                FROM requests.outbox o
                """ + ATTEMPT_JOIN + """
                WHERE a.state IN ('CLAIMED', 'ARMED') AND o.status = a.state
                  AND a.lease_expires_at <= clock_timestamp()
                """ + routes + """
                ORDER BY a.lease_expires_at, a.destination, a.tenant, a.effect_id
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, routeParams(new MapSqlParameterSource()), (row, index) -> new Expired(
                new Claim(new AttemptId(effectKey(row), row.getLong("attempt_no")),
                        row.getObject("claim_token", UUID.class),
                        new WriterEpoch(row.getObject("writer_id", UUID.class), row.getLong("generation"))),
                State.valueOf(row.getString("state"))));
        expired.forEach(value -> finishLocked(value.claim(), value.state(),
                value.state() == State.CLAIMED ? State.EXPIRED_UNSENT : State.UNKNOWN, false, null));
    }

    private Optional<LockedEffect> lockActive(Claim claim, State state) {
        return jdbc.query("""
                SELECT o.*, a.publication_attempt,
                """ + NO_BLOCKING_PREDECESSOR + """
                       AS predecessors_proved
                FROM requests.outbox o
                """ + ATTEMPT_JOIN + """
                WHERE o.destination = :destination AND o.tenant = :tenant AND o.effect_id = :effect
                """ + routes + """
                  AND a.attempt_no = :number AND a.claim_token = :token AND a.writer_id = :writer
                  AND a.generation = :generation AND o.status = :state AND a.state = :state
                  AND a.lease_expires_at > clock_timestamp()
                FOR UPDATE OF o
                """, routeParams(attemptParams(claim).addValue("state", state.name())),
                (row, index) -> new LockedEffect(obligation(row), row.getBoolean("cancelled"),
                        row.getBoolean("predecessors_proved"), row.getObject("publication_attempt", UUID.class),
                        !syntheticRoutes.isEmpty() && row.getBoolean("retry_uncertain"))).stream().findFirst();
    }

    private ArmResult arm(Claim claim, PolicyGuard guard, LockedEffect effect) {
        Optional<UUID> proof;
        try {
            var admission = access.current(guard);
            if (!claim.writer().equals(admission.writer()) || !effect.predecessorsProved()) {
                return withhold(claim, State.DEFERRED, ArmState.DEFERRED);
            }
            if (effect.cancelled()) {
                return denied(claim, effect);
            }
            proof = access.originalPublication(guard, admission, effect.obligation());
        } catch (AuthorityUnavailableException unavailable) {
            return withhold(claim, State.DEFERRED, ArmState.DEFERRED);
        } catch (AccessDeniedException denied) {
            return denied(claim, effect);
        }
        if (proof.isEmpty()) {
            return withhold(claim, State.DEFERRED, ArmState.DEFERRED);
        }
        var params = attemptParams(claim).addValue("version", guard.policyVersion())
                .addValue("publicationAttempt", proof.orElseThrow());
        if (jdbc.update("""
                UPDATE requests.outbox_attempt SET state = 'ARMED', policy_version = :version,
                    publication_attempt = :publicationAttempt, armed_at = clock_timestamp()
                WHERE state = 'CLAIMED' AND lease_expires_at > clock_timestamp() AND
                """ + ATTEMPT, params) != 1) {
            return withheld(ArmState.STALE);
        }
        updateLockedEffect(params, State.ARMED, null);
        if (endpoint != null) {
            requireOne(jdbc.update("""
                    UPDATE requests.outbox SET delivery_endpoint = :endpoint
                    WHERE last_attempt_no = :number AND
                    """ + KEY, params.addValue("endpoint", endpoint)));
        }
        return new ArmResult(ArmState.ARMED,
                Optional.of(new Invocation(claim.id(), effect.obligation().ref(), effect.obligation().revision())));
    }

    private ArmResult withhold(Claim claim, State state, ArmState result) {
        return withheld(finishLocked(claim, State.CLAIMED, state, true, null) ? result : ArmState.STALE);
    }

    private ArmResult denied(Claim claim, LockedEffect effect) {
        return effect.retryUncertain() ? withhold(claim, State.DEFERRED, ArmState.DEFERRED)
                : withhold(claim, State.SUPPRESSED, ArmState.SUPPRESSED);
    }

    private boolean finishLocked(Claim claim, State expected, State result, boolean live, UUID confirmation) {
        var params = attemptParams(claim).addValue("expected", expected.name()).addValue("result", result.name())
                .addValue("live", live).addValue("confirmation", confirmation);
        if (jdbc.update("""
                UPDATE requests.outbox_attempt SET state = :result, finished_at = clock_timestamp(),
                    confirmation_ref = :confirmation
                WHERE state = :expected AND (lease_expires_at > clock_timestamp()) = :live AND
                """ + ATTEMPT, params) != 1) {
            return false;
        }
        var effectState = switch (result) {
            case DEFERRED, EXPIRED_UNSENT -> State.READY;
            default -> result;
        };
        updateLockedEffect(params, effectState, result == State.DEFERRED || result == State.RETRYABLE ? deferMillis : null);
        return true;
    }

    private void updateLockedEffect(MapSqlParameterSource params, State effectState, Long defer) {
        params.addValue("effectState", effectState.name()).addValue("deferMillis", defer);
        var status = !syntheticRoutes.isEmpty() && effectState == State.READY
                ? "CASE WHEN retry_uncertain THEN 'RETRYABLE' ELSE :effectState END" : ":effectState";
        requireOne(jdbc.update("""
                UPDATE requests.outbox SET status =
                """ + status + ",\n" + """
                    deferred_until = clock_timestamp() + cast(:deferMillis AS bigint) * interval '1 millisecond'
                WHERE last_attempt_no = :number AND
                """ + KEY, params));
    }

    private <T> T transaction(Supplier<T> work) {
        return transactions.run(() -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !(TransactionSynchronizationManager.getResource(workerSource) instanceof ConnectionHolder)) {
                throw new IllegalStateException("Dispatch requires the owning worker transaction and data source.");
            }
            return work.get();
        });
    }

    private static OutboundObligation obligation(ResultSet row) throws SQLException {
        return new OutboundObligation(row.getString("destination"), new OrganizationId(row.getString("tenant")),
                row.getString("effect_id"), row.getString("trigger_slot"), new RequestRef(row.getString("request_ref")),
                row.getLong("request_revision"), row.getString("operation"), row.getInt("schema_version"),
                row.getString("projection_ref"),
                Instant.ofEpochSecond(row.getLong("scheduled_epoch_second"), row.getInt("scheduled_nano")));
    }

    private static EffectKey effectKey(ResultSet row) throws SQLException {
        return new EffectKey(row.getString("destination"), new OrganizationId(row.getString("tenant")),
                row.getString("effect_id"));
    }

    private static MapSqlParameterSource attemptParams(Claim claim) {
        return effectParams(claim.id().effect()).addValue("number", claim.id().number())
                .addValue("token", claim.token()).addValue("writer", claim.writer().writerId())
                .addValue("generation", claim.writer().generation());
    }

    private static MapSqlParameterSource recoveryParams(Recovery recovery) {
        var id = recovery.invocation().id();
        return effectParams(id.effect()).addValue("number", id.number()).addValue("recovery", recovery.id())
                .addValue("issuer", recovery.actor().issuer()).addValue("operator", recovery.actor().principal())
                .addValue("writer", recovery.writer().writerId()).addValue("generation", recovery.writer().generation())
                .addValue("action", recovery.action().name());
    }

    private static MapSqlParameterSource effectParams(EffectKey key) {
        return new MapSqlParameterSource("destination", key.destination()).addValue("tenant", key.tenant().value())
                .addValue("effect", key.effectId());
    }

    private MapSqlParameterSource routeParams(MapSqlParameterSource params) {
        return params.addValue("routes", syntheticRoutes).addValue("endpoint", endpoint);
    }

    private static Invocation invocation(AttemptId id, OutboundObligation obligation) {
        return new Invocation(id, obligation.ref(), obligation.revision());
    }

    private void requireSynthetic() {
        if (syntheticRoutes.isEmpty()) {
            throw new IllegalStateException("Synthetic remote delivery is not configured.");
        }
    }

    private static long durationMillis(Duration duration) {
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Dispatch intervals must be at least one millisecond.");
        }
        return duration.toMillis();
    }

    private static ArmResult withheld(ArmState state) {
        return new ArmResult(state, Optional.empty());
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Expected exactly one locked dispatch row transition.");
        }
    }

    private record LockedEffect(OutboundObligation obligation, boolean cancelled, boolean predecessorsProved,
                                UUID publication, boolean retryUncertain) { }

    private record Expired(Claim claim, State state) { }
    private record RecoveryTarget(OutboundObligation obligation, Claim claim, long lastAttempt, State state,
                                  UUID publication, UUID token, boolean cancelled) { }
}
