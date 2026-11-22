package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AccessDeniedException;
import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.OutboundObligation;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.TransactionRunner;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.integration.DispatchStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL worker transactions for one scoped claim, arm, or finalization at a time. */
public final class JdbcDispatchStore implements DispatchStore {
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
                        AND proof.effect_id = p.effect_id AND proof.attempt_no = p.last_attempt_no
                        AND proof.state = 'CONFIRMED' AND proof.confirmation_ref IS NOT NULL
                ))
            )
            """;

    /** The shared organization guard and qualified proofs use this worker's owning connection. */
    public interface GuardedAccess {
        Optional<PolicyGuard> lock(OrganizationId tenant);

        Admission current(PolicyGuard guard);

        Optional<UUID> originalPublication(PolicyGuard guard, Admission admission, OutboundObligation obligation);
    }

    private enum State { READY, CLAIMED, ARMED, UNKNOWN, CONFIRMED, SUPPRESSED, DEFERRED, EXPIRED_UNSENT }

    private final DataSource workerSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRunner transactions;
    private final GuardedAccess access;
    private final long leaseMillis;
    private final long deferMillis;

    public JdbcDispatchStore(DataSource workerSource, TransactionRunner transactions, GuardedAccess access,
                             Duration lease, Duration deferral) {
        this.workerSource = Objects.requireNonNull(workerSource);
        jdbc = new NamedParameterJdbcTemplate(workerSource);
        this.transactions = Objects.requireNonNull(transactions);
        this.access = Objects.requireNonNull(access);
        leaseMillis = durationMillis(lease);
        deferMillis = durationMillis(deferral);
    }

    @Override
    public Optional<Claim> claim(WriterEpoch writer) {
        Objects.requireNonNull(writer);
        return transaction(() -> {
            recoverExpired();
            return jdbc.query("""
                    SELECT o.destination, o.tenant, o.effect_id, o.last_attempt_no
                    FROM requests.outbox o
                    WHERE o.status = 'READY'
                      AND (o.deferred_until IS NULL OR o.deferred_until <= statement_timestamp())
                      AND (o.scheduled_epoch_second, o.scheduled_nano) <= (
                          floor(extract(epoch FROM statement_timestamp())),
                          (extract(epoch FROM statement_timestamp())
                              - floor(extract(epoch FROM statement_timestamp()))) * 1000000000)
                      AND
                    """ + NO_BLOCKING_PREDECESSOR + """
                    ORDER BY o.scheduled_epoch_second, o.scheduled_nano, o.destination, o.tenant, o.effect_id
                    LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                    """, (row, index) -> new Claim(new AttemptId(effectKey(row),
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

    private Claim persistClaim(Claim claim) {
        var params = attemptParams(claim).addValue("leaseMillis", leaseMillis);
        requireOne(jdbc.update("""
                INSERT INTO requests.outbox_attempt (destination, tenant, effect_id, attempt_no, claim_token,
                    writer_id, generation, claimed_at, lease_expires_at, state)
                VALUES (:destination, :tenant, :effect, :number, :token, :writer, :generation, statement_timestamp(),
                    statement_timestamp() + :leaseMillis * interval '1 millisecond', 'CLAIMED')
                """, params));
        requireOne(jdbc.update("""
                UPDATE requests.outbox SET status = 'CLAIMED', last_attempt_no = :number, deferred_until = NULL
                WHERE status = 'READY' AND
                """ + KEY, params));
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
                ORDER BY a.lease_expires_at, a.destination, a.tenant, a.effect_id
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, (row, index) -> new Expired(
                new Claim(new AttemptId(effectKey(row), row.getLong("attempt_no")),
                        row.getObject("claim_token", UUID.class),
                        new WriterEpoch(row.getObject("writer_id", UUID.class), row.getLong("generation"))),
                State.valueOf(row.getString("state"))));
        expired.forEach(value -> finishLocked(value.claim(), value.state(),
                value.state() == State.CLAIMED ? State.EXPIRED_UNSENT : State.UNKNOWN, false, null));
    }

    private Optional<LockedEffect> lockActive(Claim claim, State state) {
        return jdbc.query("""
                SELECT o.*,
                """ + NO_BLOCKING_PREDECESSOR + """
                       AS predecessors_proved
                FROM requests.outbox o
                """ + ATTEMPT_JOIN + """
                WHERE o.destination = :destination AND o.tenant = :tenant AND o.effect_id = :effect
                  AND a.attempt_no = :number AND a.claim_token = :token AND a.writer_id = :writer
                  AND a.generation = :generation AND o.status = :state AND a.state = :state
                  AND a.lease_expires_at > clock_timestamp()
                FOR UPDATE OF o
                """, attemptParams(claim).addValue("state", state.name()),
                (row, index) -> new LockedEffect(obligation(row), row.getBoolean("cancelled"),
                        row.getBoolean("predecessors_proved"))).stream().findFirst();
    }

    private ArmResult arm(Claim claim, PolicyGuard guard, LockedEffect effect) {
        Optional<UUID> proof;
        try {
            var admission = access.current(guard);
            if (!claim.writer().equals(admission.writer()) || !effect.predecessorsProved()) {
                return withhold(claim, State.DEFERRED, ArmState.DEFERRED);
            }
            if (effect.cancelled()) {
                return withhold(claim, State.SUPPRESSED, ArmState.SUPPRESSED);
            }
            proof = access.originalPublication(guard, admission, effect.obligation());
        } catch (AuthorityUnavailableException unavailable) {
            return withhold(claim, State.DEFERRED, ArmState.DEFERRED);
        } catch (AccessDeniedException denied) {
            return withhold(claim, State.SUPPRESSED, ArmState.SUPPRESSED);
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
        return new ArmResult(ArmState.ARMED,
                Optional.of(new Invocation(claim.id(), effect.obligation().ref(), effect.obligation().revision())));
    }

    private ArmResult withhold(Claim claim, State state, ArmState result) {
        return withheld(finishLocked(claim, State.CLAIMED, state, true, null) ? result : ArmState.STALE);
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
        updateLockedEffect(params, effectState, result == State.DEFERRED ? deferMillis : null);
        return true;
    }

    private void updateLockedEffect(MapSqlParameterSource params, State effectState, Long defer) {
        params.addValue("effectState", effectState.name()).addValue("deferMillis", defer);
        requireOne(jdbc.update("""
                UPDATE requests.outbox SET status = :effectState,
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
        var key = claim.id().effect();
        return new MapSqlParameterSource("destination", key.destination()).addValue("tenant", key.tenant().value())
                .addValue("effect", key.effectId()).addValue("number", claim.id().number())
                .addValue("token", claim.token()).addValue("writer", claim.writer().writerId())
                .addValue("generation", claim.writer().generation());
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

    private record LockedEffect(OutboundObligation obligation, boolean cancelled, boolean predecessorsProved) { }

    private record Expired(Claim claim, State state) { }
}
