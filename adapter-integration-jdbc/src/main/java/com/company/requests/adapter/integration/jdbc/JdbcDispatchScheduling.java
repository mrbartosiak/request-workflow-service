package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import com.company.requests.integration.DispatchCoordinator.Result;
import com.company.requests.integration.DispatchScheduler;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.Claim;
import com.company.requests.integration.DispatchStore.EffectKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Scheduling uses a durable armed slot independently of the session permit and the business outcome. */
final class JdbcDispatchScheduling {
    private static final int SLOT_NAMESPACE = 1919250820;
    private static final String MATCH_LANE = "o.destination = l.destination AND o.tenant = l.tenant";
    private static final String ELIGIBLE = """
              AND o.status IN ('READY', 'RETRYABLE')
              AND (o.delivery_endpoint IS NULL OR o.delivery_endpoint = :endpoint)
              AND (o.deferred_until IS NULL OR o.deferred_until <= statement_timestamp())
              AND (o.scheduled_epoch_second, o.scheduled_nano) <= (
                  floor(extract(epoch FROM statement_timestamp())),
                  (extract(epoch FROM statement_timestamp())
                      - floor(extract(epoch FROM statement_timestamp()))) * 1000000000)
              AND (SELECT count(*) FROM requests.outbox_attempt a
                   WHERE a.destination = o.destination AND a.tenant = o.tenant AND a.effect_id = o.effect_id
                     AND a.armed_at > statement_timestamp() - :repeatMillis * interval '1 millisecond'
                     AND a.attempt_no > (SELECT min(first_arm.attempt_no) FROM requests.outbox_attempt first_arm
                         WHERE first_arm.destination = o.destination AND first_arm.tenant = o.tenant
                           AND first_arm.effect_id = o.effect_id AND first_arm.armed_at IS NOT NULL))
                  < :repeatLimit
              AND
            """ + JdbcDispatchStore.NO_BLOCKING_PREDECESSOR;
    private static final String ACTIVE_SLOTS = """
            SELECT NOT EXISTS (SELECT 1 FROM requests.outbox_attempt a
                WHERE a.destination = :destination AND a.tenant = :tenant
                  AND (a.scheduling_slot = :slot OR a.scheduling_slot IS NULL)
                  AND (
                      (a.scheduling_invocation IS NOT NULL AND a.invocation_quiesced_at IS NULL)
                      OR (a.lease_expires_at > statement_timestamp()
                          AND (a.state = 'CLAIMED' OR (a.scheduling_slot IS NULL AND a.state = 'ARMED')))))
              AND pg_try_advisory_xact_lock(:namespace, :lockKey)
            """;
    private static final String LANE = "destination = :destination AND tenant = :tenant";

    private final DispatchWorkerConnections connections;
    private final NamedParameterJdbcTemplate jdbc;
    private final OrganizationId tenant;
    private final Set<String> routes;
    private final UUID endpoint;
    private final ThreadLocal<Permit> permitted = new ThreadLocal<>();

    JdbcDispatchScheduling(DispatchWorkerConnections connections, OrganizationId tenant, Set<String> routes,
                           UUID endpoint) {
        this.connections = connections;
        jdbc = new NamedParameterJdbcTemplate(connections);
        this.tenant = Objects.requireNonNull(tenant);
        this.routes = Set.copyOf(routes);
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    void enroll() {
        for (var route : routes.stream().sorted().toList()) {
            jdbc.update("""
                    INSERT INTO requests.outbox_dispatch_lane (destination, tenant) VALUES (:destination, :tenant)
                    ON CONFLICT (destination, tenant) DO NOTHING
                    """, laneParams(route));
        }
    }

    List<Claim> claimBatch(WriterEpoch writer, int maximum, BiFunction<Claim, Integer, Claim> persist) {
        var lanes = dueLanes(maximum);
        var claims = new ArrayList<Claim>(maximum);
        for (var lane : lanes) {
            for (int slot = 1; slot <= DispatchScheduler.DESTINATION_LIMIT && claims.size() < maximum; slot++) {
                var params = laneParams(lane.destination()).addValue("slot", slot)
                        .addValue("namespace", SLOT_NAMESPACE + slot).addValue("lockKey", lane.lockKey());
                if (Boolean.TRUE.equals(jdbc.queryForObject(ACTIVE_SLOTS, params, Boolean.class))) {
                    int assigned = slot;
                    next(writer, params).forEach(claim -> claims.add(persist.apply(claim, assigned)));
                }
            }
            jdbc.update("UPDATE requests.outbox_dispatch_lane SET last_claimed_at = statement_timestamp() WHERE " + LANE,
                    laneParams(lane.destination()));
        }
        return List.copyOf(claims);
    }

    private List<Lane> dueLanes(int maximum) {
        return jdbc.query("""
                SELECT l.destination, l.lock_key FROM requests.outbox_dispatch_lane l
                WHERE l.tenant = :tenant AND l.destination IN (:routes)
                  AND (l.resume_at IS NULL OR l.resume_at <= statement_timestamp())
                  AND EXISTS (SELECT 1 FROM requests.outbox o WHERE
                """ + MATCH_LANE + ELIGIBLE + """
                  )
                ORDER BY l.last_claimed_at NULLS FIRST, l.destination
                LIMIT :maximum FOR UPDATE OF l SKIP LOCKED
                """, eligibility(new MapSqlParameterSource("tenant", tenant.value()).addValue("routes", routes)
                        .addValue("maximum", maximum)),
                (row, index) -> new Lane(row.getString("destination"), row.getInt("lock_key")));
    }

    private List<Claim> next(WriterEpoch writer, MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT o.destination, o.effect_id, o.last_attempt_no FROM requests.outbox o
                WHERE o.destination = :destination AND o.tenant = :tenant
                """ + ELIGIBLE + """
                ORDER BY o.scheduled_epoch_second, o.scheduled_nano, o.effect_id
                LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, eligibility(params), (row, index) -> new Claim(new AttemptId(
                new EffectKey(row.getString("destination"), tenant, row.getString("effect_id")),
                Math.incrementExact(row.getLong("last_attempt_no"))), UUID.randomUUID(), writer));
    }

    Result dispatch(Claim claim, Function<UUID, Result> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || permitted.get() != null) {
            throw new IllegalStateException("An invocation permit requires an independent workflow session.");
        }
        if (!tenant.equals(claim.id().effect().tenant()) || !routes.contains(claim.id().effect().destination())) {
            return Result.STALE;
        }
        var slot = jdbc.query("""
                SELECT l.lock_key, a.scheduling_slot FROM requests.outbox_attempt a
                JOIN requests.outbox_dispatch_lane l USING (destination, tenant)
                WHERE a.destination = :destination AND a.tenant = :tenant AND a.effect_id = :effect
                  AND a.attempt_no = :number AND a.claim_token = :token AND a.writer_id = :writer
                  AND a.generation = :generation AND a.state = 'CLAIMED'
                  AND a.scheduling_slot IS NOT NULL AND a.lease_expires_at > clock_timestamp()
                """, claimParams(claim), (row, index) -> new Slot(row.getInt("lock_key"),
                        row.getInt("scheduling_slot"))).stream().findFirst();
        if (slot.isEmpty() || !advisory("pg_try_advisory_lock", slot.orElseThrow())) {
            return Result.STALE;
        }
        try (var permit = new Permit(claim, slot.orElseThrow())) {
            permitted.set(permit);
            return work.apply(permit.owner);
        }
    }

    boolean hasPermit(Claim claim) {
        var permit = permitted.get();
        return permit != null && claim.equals(permit.claim);
    }

    UUID permitOwner(Claim claim) {
        if (!hasPermit(claim)) {
            throw new IllegalStateException("Only the local invocation permit can own the durable arm.");
        }
        return permitted.get().owner;
    }

    void recordQuiescence(Claim claim, UUID owner) {
        jdbc.update("""
                UPDATE requests.outbox_attempt SET invocation_quiesced_at = clock_timestamp()
                WHERE destination = :destination AND tenant = :tenant AND effect_id = :effect
                  AND attempt_no = :number AND claim_token = :token AND writer_id = :writer
                  AND generation = :generation AND scheduling_invocation = :invocation
                  AND invocation_quiesced_at IS NULL
                """, claimParams(claim).addValue("invocation", owner));
    }

    boolean canArm(Claim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT resume_at IS NULL OR resume_at <= clock_timestamp()
                FROM requests.outbox_dispatch_lane WHERE
                """ + LANE + " FOR SHARE", laneParams(claim.id().effect().destination()), Boolean.class));
    }

    void throttle(Claim claim, Duration retryAfter) {
        jdbc.update("""
                UPDATE requests.outbox_dispatch_lane
                SET resume_at = greatest(resume_at, clock_timestamp() + :delay * interval '1 millisecond')
                WHERE
                """ + LANE, laneParams(claim.id().effect().destination()).addValue("delay", retryAfter.toMillis()));
    }

    long retryDelay() {
        return ThreadLocalRandom.current().nextLong(DispatchScheduler.MIN_JITTER.toMillis(),
                DispatchScheduler.MAX_JITTER.toMillis() + 1);
    }

    private boolean advisory(String function, Slot slot) {
        return Boolean.TRUE.equals(jdbc.getJdbcOperations().queryForObject("SELECT " + function + "(?, ?)",
                Boolean.class, SLOT_NAMESPACE + slot.number(), slot.lockKey()));
    }

    private void unlock(Slot slot) {
        try {
            if (!advisory("pg_advisory_unlock", slot)) {
                throw new IllegalStateException("Dispatch session lost its invocation permit.");
            }
        } catch (RuntimeException failure) {
            connections.discardCurrentSession();
            throw failure;
        }
    }

    private MapSqlParameterSource eligibility(MapSqlParameterSource params) {
        return params.addValue("endpoint", endpoint).addValue("repeatLimit", DispatchScheduler.REPEAT_LIMIT)
                .addValue("repeatMillis", DispatchScheduler.REPEAT_WINDOW.toMillis());
    }

    private MapSqlParameterSource laneParams(String destination) {
        return new MapSqlParameterSource("tenant", tenant.value()).addValue("destination", destination);
    }

    private MapSqlParameterSource claimParams(Claim claim) {
        var key = claim.id().effect();
        return laneParams(key.destination()).addValue("effect", key.effectId()).addValue("number", claim.id().number())
                .addValue("token", claim.token()).addValue("writer", claim.writer().writerId())
                .addValue("generation", claim.writer().generation());
    }

    private record Lane(String destination, int lockKey) { }
    private record Slot(int lockKey, int number) { }

    private final class Permit implements AutoCloseable {
        private final Claim claim;
        private final Slot slot;
        private final UUID owner = UUID.randomUUID();

        private Permit(Claim claim, Slot slot) {
            this.claim = claim;
            this.slot = slot;
        }

        @Override
        public void close() {
            permitted.remove();
            unlock(slot);
        }
    }
}
