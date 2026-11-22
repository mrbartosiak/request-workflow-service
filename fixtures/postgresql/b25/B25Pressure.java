import com.company.requests.bootstrap.B32Gate;
import com.company.requests.bootstrap.B42FixtureEvidence;
import com.company.requests.integration.DeliveryReconciler;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.DispatchStore.EffectKey;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checks shared pressure against separate worker roles and an ordinary guarded REST request. */
final class B25Pressure {
    private static final String SLOW = B25Postgresql.SLOW, OUTAGE = B25Postgresql.OUTAGE;
    private static final String FAST = B25Postgresql.FAST, FILLER = B25Postgresql.FILLER;
    private static final String RETRY = B25Postgresql.RETRY, THROTTLE = B25Postgresql.THROTTLE;
    private static final String UNSAFE = B25Postgresql.UNSAFE, KEY = B25Postgresql.KEY;
    private final JdbcTemplate sql;
    private final JdbcTemplate remote;
    private final B25Profile profile;
    private final String publisher;
    private final Path run;
    private final B25Events events;
    private final Workload workload;

    private B25Pressure(B25Postgresql fixture, Path run, Workload workload) throws Exception {
        this.run = run;
        sql = fixture.sql;
        remote = fixture.remote.observer;
        profile = fixture.profile;
        publisher = fixture.base.publisherBearer;
        this.workload = workload;
        events = new B25Events(Files.createDirectory(run.resolve("pressure")));
    }

    static B25Pressure prepare(B25Postgresql fixture, Path run) throws Exception {
        for (int index = 0; index < 10; index++) {
            fixture.publish(SLOW);
        }
        for (int index = 0; index < 2; index++) {
            fixture.publish(OUTAGE);
            fixture.publish(FAST);
            fixture.publish(FILLER);
        }
        var rest = fixture.base.accepted(fixture.mvc(FAST), B25Postgresql.TYPE);
        var workload = new Workload(rest, stage(fixture, FAST), stage(fixture, THROTTLE),
                stage(fixture, THROTTLE), stage(fixture, FAST), stage(fixture, RETRY),
                stage(fixture, FAST), stage(fixture, UNSAFE),
                Boolean.getBoolean("requests.b42.protected") ? stage(fixture, FAST) : null);
        return new B25Pressure(fixture, run, workload);
    }

    private static EffectKey stage(B25Postgresql fixture, String route) throws Exception {
        var effect = fixture.publish(route);
        require(fixture.sql.update("""
                UPDATE requests.outbox
                SET scheduled_epoch_second = extract(epoch FROM statement_timestamp() + interval '1 hour')::bigint,
                    scheduled_nano = 0
                WHERE
                """ + KEY, B25Postgresql.args(effect)) == 1, "Could not stage a published fixture obligation.");
        return effect;
    }

    private EffectKey due(EffectKey effect) {
        require(sql.update("""
                UPDATE requests.outbox
                SET scheduled_epoch_second = extract(epoch FROM statement_timestamp())::bigint - 1, scheduled_nano = 0
                WHERE status = 'READY' AND
                """ + KEY, B25Postgresql.args(effect)) == 1, "Fixture obligation was not staged and ready.");
        return effect;
    }

    private record Workload(RequestRef rest, EffectKey fastWhenHeld, EffectKey throttle, EffectKey throttleSibling,
                            EffectKey fastDuringThrottle, EffectKey retry, EffectKey fastDuringBudget, EffectKey unsafe,
                            EffectKey b42Healthy) { }

    void exercise() throws Exception {
        verifyQualifiedCapacity();
        try (var a = B25App.start(run, "a", profile); var b = B25App.start(run, "b", profile)) {
            try {
                require(a.pid() != b.pid() && a.port() != b.port(), "B25 needs two independent services");
                a.enable();
                await(() -> pressured("begin").size() == 4, "four calls did not occupy the first instance");
                var original = expireHeld();
                await(() -> a.snapshot().connections() == 4 && a.snapshot().largestBatch() == 8,
                        "first service did not observe four connections and a batch of eight");
                await(this::heldPermitsWithoutTransaction, "occupied worker slots did not release their transactions");
                require(a.context(workload.rest(), publisher) == 200
                        && b.context(workload.rest(), publisher) == 200 && pressured("end").isEmpty(),
                        "REST did not advance while all four workers of the first service were occupied");
                verifyRestPools();
                b.enable();
                saturation(original);
                throttle();
                retryBudget();
                unsafe(b);
                require(a.snapshot().largestBatch() <= 8 && b.snapshot().largestBatch() <= 8
                        && a.snapshot().connections() == 4 && b.snapshot().connections() == 4
                        && a.snapshot().failures() == 0 && b.snapshot().failures() == 0,
                        "scheduled instance exceeded its ceiling or encountered worker failures");
                verifyWorkerPools();
                if (workload.b42Healthy() != null) {
                    protectedEvidence(a, b);
                }
            } finally {
                events.release();
            }
        }
        await(() -> sql.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'b25-instance-%'
                    OR application_name IN ('b25-a-rest', 'b25-b-rest')
                """, Integer.class) == 0, "private worker or REST sessions remain after teardown");
        System.out.println("PASS B25 native scheduling, pressure, safe retry and owned worker teardown.");
    }

    private void protectedEvidence(B25App a, B25App b) throws Exception {
        long sendsA = sendsBy("a");
        long sendsB = sendsBy("b");
        await(() -> evidenceCaughtUp(a, sendsA) && evidenceCaughtUp(b, sendsB),
                "Prior B25 send evidence had not drained before the B42 fill.");
        floodWithSinkCut(a);
        require(a.context(workload.rest(), publisher) == 503, "A admitted REST work above evidence restriction.");
        await(() -> evidence(a).edge() == 4096 && evidence(a).batch() == 0, "A did not retain its edge reject.");
        long initiallySentByA = sendsBy("a");
        due(workload.b42Healthy());
        await(() -> "CONFIRMED".equals(state(workload.b42Healthy())), "B did not advance at A's evidence restriction.");
        require(sendsBy("a") == initiallySentByA && b.context(workload.rest(), publisher) == 200,
                "A sent through restriction or B lost independent REST service.");
        Files.delete(a.control().resolve(B42FixtureEvidence.CUT));
        expectEvidenceEmpty(a);
        require(evidence(a).resumed() && a.context(workload.rest(), publisher) == 200,
                "A did not resume through the bounded drain.");

        await(() -> evidence(a).ingressOutcomes() == 28, "A did not drain its first resumed request.");
        expectEvidenceEmpty(a);
        floodWithSinkCut(a);
        for (int attempt = 0; attempt <= B42FixtureEvidence.EDGE_FRAMES; attempt++) {
            require(a.context(workload.rest(), publisher) == 503, "A admitted work while edge capacity was in use.");
        }
        await(() -> evidence(a).stopped() && evidence(a).ingressGaps() == 1 && evidence(a).durable(),
                "Edge exhaustion did not persist the routing stop.");
        Files.delete(a.control().resolve(B42FixtureEvidence.CUT));
        expectEvidenceEmpty(a);
        expectEvidenceEmpty(b);
        var aState = evidence(a);
        require(aState.stopped() && aState.batch() == 0 && aState.drain() == B42FixtureEvidence.DRAIN_LIMIT
                && aState.outboundGaps() == 0 && evidence(b).outboundGaps() == 0,
                "Routing resumed after a Q-05 gap, or a drain exceeded its budget.");
        verifyEvidenceCounts(a, b, aState);
    }

    private void floodWithSinkCut(B25App app) throws Exception {
        B32Gate.atomic(app.control().resolve(B42FixtureEvidence.CUT), "cut");
        int admits = B42FixtureEvidence.MAIN_FRAMES * 80 / 100 / 2;
        for (int request = 0; request < admits; request++) {
            require(app.context(workload.rest(), publisher) == 200, "A rejected healthy work below spool restriction.");
        }
        await(() -> {
            var state = evidence(app);
            return state.main() == admits * 2L * 4096 && state.alert() && state.restricted() && state.sinkFailures() > 0;
        }, "A did not observe isolated sink failure and restricted evidence pressure.");
    }

    private void verifyEvidenceCounts(B25App a, B25App b, EvidenceState aState) throws IOException {
        var aLines = Files.readAllLines(a.control().resolve(B42FixtureEvidence.SAFE_TRACE));
        var bLines = Files.readAllLines(b.control().resolve(B42FixtureEvidence.SAFE_TRACE));
        require(count(aLines, "\"direction\":\"INGRESS\"", "\"phase\":\"START\"") == 56
                && count(bLines, "\"direction\":\"INGRESS\"", "\"phase\":\"START\"") == 2,
                "Native ingress starts did not reconcile with 59 requests and one documented gap.");
        require(count(aLines, "\"direction\":\"INGRESS\"", "\"phase\":\"OUTCOME\"") == 56
                && count(bLines, "\"direction\":\"INGRESS\"", "\"phase\":\"OUTCOME\"") == 2
                && count(aLines, "\"phase\":\"ERROR\"", "\"outcome\":\"EVIDENCE_UNAVAILABLE\"") == 4,
                "Ingress outcomes or edge errors did not reconcile.");
        long sends = sendsBy("a") + sendsBy("b");
        long arms = sql.queryForObject("""
                SELECT count(*) FROM requests.outbox_attempt WHERE armed_at IS NOT NULL AND destination LIKE 'b25-%'
                """, Long.class);
        int remoteEffects = B25Postgresql.CONTRACTS.keySet().stream().mapToInt(this::remoteEffects).sum();
        for (var phase : List.of("START", "OUTCOME")) {
            var marker = "\"phase\":\"" + phase + "\"";
            require(count(aLines, "\"operation\":\"VENDOR_SEND\"", marker) == sendsBy("a")
                    && count(bLines, "\"operation\":\"VENDOR_SEND\"", marker) == sendsBy("b"),
                    "Native outbound send evidence did not match the actual fake calls.");
        }
        require(arms == sends && count(aLines, "\"phase\":\"OUTCOME\"", "\"outcome\":\"DELIVERY_TIMEOUT\"") == 4,
                "Durable arm and timeout evidence counts did not match the native gates.");
        for (var lines : List.of(aLines, bLines)) {
            require(lines.stream().noneMatch(line -> line.contains(publisher) || line.contains(workload.rest().value())
                    || line.contains("b25-") || line.contains("action=publish")),
                    "A protected synthetic value leaked into the safe fixture trace.");
        }
        System.out.printf("PASS B42 ingress=59 starts=58 outcomes=58 edge-errors=4 ingress-gaps=%d "
                        + "fake-sends=%d durable-arms=%d remote-effects=%d timeout-outcomes=4 drain-maximum=%d%n",
                aState.ingressGaps(), sends, arms, remoteEffects, aState.drain());
    }

    private static long count(List<String> lines, String first, String second) {
        return lines.stream().filter(line -> line.contains(first) && line.contains(second)).count();
    }

    private long sendsBy(String instance) {
        return events.read().stream().filter(event -> event.process().equals(instance) && event.phase().equals("begin"))
                .count();
    }

    private static void expectEvidenceEmpty(B25App app) throws InterruptedException {
        await(() -> evidence(app).empty(), "The native protected evidence queue did not drain.");
    }

    private static boolean evidenceCaughtUp(B25App app, long actualSends) {
        var state = evidence(app);
        return state.empty() && state.sendOutcomes() == actualSends;
    }

    private static EvidenceState evidence(B25App app) {
        try {
            var fields = B42FixtureEvidence.readState(app.control()).strip().split(" ");
            return new EvidenceState(Long.parseLong(fields[0]), Long.parseLong(fields[1]), Long.parseLong(fields[2]),
                    Boolean.parseBoolean(fields[3]), Boolean.parseBoolean(fields[4]), Boolean.parseBoolean(fields[5]),
                    Boolean.parseBoolean(fields[6]), Long.parseLong(fields[7]), Long.parseLong(fields[8]),
                    Long.parseLong(fields[9]), Integer.parseInt(fields[10]), Integer.parseInt(fields[11]),
                    Boolean.parseBoolean(fields[12]), Long.parseLong(fields[13]), Long.parseLong(fields[14]));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read B42 protected evidence metrics.", failure);
        }
    }

    private record EvidenceState(long main, long edge, long reserved, boolean alert, boolean restricted, boolean resumed,
                                 boolean stopped, long ingressGaps, long outboundGaps, long sinkFailures, int batch,
                                 int drain, boolean durable, long ingressOutcomes, long sendOutcomes) {
        boolean empty() {
            return main == 0 && edge == 0 && reserved == 0;
        }
    }

    private void verifyQualifiedCapacity() throws InterruptedException {
        require(sql.queryForObject("SHOW max_connections", Integer.class) == 16
                && remote.queryForObject("SHOW max_connections", Integer.class) == 16,
                "The original qualified fixture connection cap changed.");
        await(() -> sql.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'
                    AND application_name <> 'psql' AND pid <> pg_backend_pid()
                """, Integer.class) == 0, "Setup connections were not drained before service startup.");
    }

    private List<EffectKey> expireHeld() {
        var original = sql.query("""
                UPDATE requests.outbox_attempt SET claimed_at = statement_timestamp() - interval '16 seconds',
                    lease_expires_at = statement_timestamp() - interval '1 second'
                WHERE state = 'ARMED' AND destination IN (?, ?)
                    AND lease_expires_at - claimed_at = interval '15 seconds'
                RETURNING destination, tenant, effect_id
                """, (row, index) -> new EffectKey(row.getString(1), new OrganizationId(row.getString(2)),
                row.getString(3)), SLOW, OUTAGE);
        require(original.size() == 4, "four armed attempts lacked the exact 15-second lease");
        return original;
    }

    private boolean heldPermitsWithoutTransaction() {
        return Boolean.TRUE.equals(sql.queryForObject("""
                SELECT count(DISTINCT s.pid) = 4 AND bool_and(s.xact_start IS NULL)
                FROM pg_stat_activity s JOIN pg_locks l ON s.pid = l.pid
                WHERE s.application_name = 'b25-instance-a' AND s.usename = 'b23_worker_a'
                  AND l.locktype = 'advisory' AND l.granted
                """, Boolean.class));
    }

    private void verifyRestPools() {
        require(Boolean.TRUE.equals(sql.queryForObject("""
                SELECT count(*) = 2 AND count(DISTINCT pid) = 2
                    AND count(DISTINCT application_name) = 2 AND bool_and(usename = 'b04_workflow')
                FROM pg_stat_activity WHERE application_name IN ('b25-a-rest', 'b25-b-rest')
                """, Boolean.class)), "REST service pools did not have disjoint dedicated command sessions");
        System.out.println("PASS guarded REST HTTP 200 in both live services with one separate command session each.");
    }

    private void verifyWorkerPools() {
        require(Boolean.TRUE.equals(sql.queryForObject("""
                SELECT count(*) = 8 AND count(DISTINCT pid) = 8 AND count(DISTINCT application_name) = 2
                    AND count(*) FILTER (WHERE application_name = 'b25-instance-a' AND usename = 'b23_worker_a') = 4
                    AND count(*) FILTER (WHERE application_name = 'b25-instance-b' AND usename = 'b23_worker_b') = 4
                FROM pg_stat_activity WHERE application_name IN ('b25-instance-a', 'b25-instance-b')
                """, Boolean.class)), "The two worker pools did not own four disjoint backend sessions each.");
        System.out.println("PASS qualified max_connections=16 and independent 4/4 worker backend sessions.");
    }

    private List<B25Events.Event> pressured(String phase) {
        return events.read().stream().filter(event -> event.phase().equals(phase)
                && (event.destination().equals(SLOW) || event.destination().equals(OUTAGE))).toList();
    }

    private void saturation(List<EffectKey> original) throws Exception {
        long prior = events.read().stream().filter(event -> event.process().equals("b")
                && event.destination().equals(FAST) && event.phase().equals("begin")).count();
        var unrelated = due(workload.fastWhenHeld());
        await(() -> state(unrelated).equals("CONFIRMED"), "independent destination did not advance");
        require(events.read().stream().filter(event -> event.process().equals("b")
                && event.destination().equals(FAST) && event.phase().equals("begin")).count() > prior,
                "the second service did not progress independently");
        await(() -> original.stream().allMatch(item -> state(item).equals("UNKNOWN")),
                "expired armed work did not remain unknown");
        require(pressured("begin").size() == 4 && pressured("end").isEmpty()
                && countState(SLOW, "READY") == 8 && highest(SLOW) == 2,
                "expired old calls released destination capacity");
        require(original.stream().allMatch(item -> armedCount(item) == 1),
                "expiration discarded durable arm evidence");
        require(occupied(SLOW) == 2 && occupied(OUTAGE) == 2,
                "Expired UNKNOWN attempts did not retain their durable slots during I/O.");
        await(() -> pressured("deadline").size() == 4, "occupied invocations did not observe their deadline");
        var times = pressured("deadline").stream().map(B25Events.Event::millis).sorted().toList();
        require(times.getFirst() >= 1_900 && times.getLast() <= 3_500, "worker deadline outside its observed bound");
        events.release();
        await(() -> countState(SLOW, "CONFIRMED") == 8, "slow destination did not resume in bounded slots");
        await(() -> remoteEffects(SLOW) == 10, "late application fixture did not settle");
        await(() -> occupied(SLOW) == 0 && occupied(OUTAGE) == 0,
                "Actual connector completion did not release the occupied slots.");
        require(countState(SLOW, "UNKNOWN") == 2 && countState(OUTAGE, "UNKNOWN") == 2
                && remoteEffects(OUTAGE) == 0 && highest(SLOW) == 2 && highest(OUTAGE) == 2,
                "status, capacity or late-application count disagrees");
        System.out.printf("PASS batch=8, dedicated sessions=4, cluster lane ceilings=2/2, leases=15s,"
                + " deadlines ms=%s, REST in both JVMs and B destination advanced during A saturation.%n", times);
    }

    private void throttle() throws Exception {
        var initial = due(workload.throttle());
        await(() -> state(initial).equals("RETRYABLE"), "trusted throttle did not qualify as a safe transient");
        var sibling = due(workload.throttleSibling());
        require(Boolean.TRUE.equals(sql.queryForObject("SELECT last_attempt_no IS NULL FROM requests.outbox WHERE "
                + KEY, Boolean.class, B25Postgresql.args(sibling))), "shared throttle did not defer a sibling");
        var independent = due(workload.fastDuringThrottle());
        await(() -> state(independent).equals("CONFIRMED"), "throttle blocked independent destination");
        await(() -> state(sibling).equals("CONFIRMED"), "throttled route did not resume");
        require(highest(THROTTLE) <= 2 && Boolean.TRUE.equals(sql.queryForObject("""
                SELECT a.claimed_at >= l.resume_at AND l.resume_at >= first.finished_at + interval '8 seconds'
                FROM requests.outbox_attempt a
                JOIN requests.outbox_dispatch_lane l USING (destination, tenant)
                JOIN requests.outbox_attempt first ON first.destination = a.destination AND first.tenant = a.tenant
                WHERE a.destination = ? AND a.tenant = ? AND a.effect_id = ? AND a.armed_at IS NOT NULL
                  AND first.effect_id = ? AND first.armed_at IS NOT NULL
                ORDER BY first.attempt_no LIMIT 1
                """, Boolean.class, sibling.destination(), sibling.tenant().value(), sibling.effectId(),
                initial.effectId())), "trusted hint admitted sibling before its shared cooldown");
        System.out.println("PASS trusted cooldown persisted across instances and bounded route resumption.");
        if (workload.b42Healthy() != null) {
            settleThrottleBeforeEvidenceCut(initial, sibling);
        }
    }

    private void settleThrottleBeforeEvidenceCut(EffectKey initial, EffectKey sibling) throws InterruptedException {
        // Advance only the original, proved retry's jitter after the unchanged cooldown and sibling checks.
        sql.update("""
                UPDATE requests.outbox AS o
                SET deferred_until = clock_timestamp() - interval '1 second'
                WHERE o.destination = ? AND o.tenant = ? AND o.effect_id = ? AND o.status = 'RETRYABLE'
                  AND EXISTS (
                    SELECT 1 FROM requests.outbox_dispatch_lane l
                    WHERE l.destination = o.destination AND l.tenant = o.tenant
                      AND l.resume_at <= clock_timestamp()
                  )
                  AND EXISTS (
                    SELECT 1 FROM requests.outbox_attempt a
                    WHERE a.destination = o.destination AND a.tenant = o.tenant AND a.effect_id = o.effect_id
                      AND a.attempt_no = o.last_attempt_no AND a.state = 'RETRYABLE'
                      AND a.delivery_observation IS NOT NULL
                      AND a.attempt_no = (
                        SELECT min(first_arm.attempt_no) FROM requests.outbox_attempt first_arm
                        WHERE first_arm.destination = o.destination AND first_arm.tenant = o.tenant
                          AND first_arm.effect_id = o.effect_id AND first_arm.armed_at IS NOT NULL
                      )
                  )
                """, B25Postgresql.args(initial));
        await(() -> "CONFIRMED".equals(state(initial)) && occupied(THROTTLE) == 0,
                "Original throttle effect did not settle before the B42 fill.");
        require(armedCount(initial) == 2 && armedCount(sibling) == 1
                && remoteEffects(initial) == 1 && remoteEffects(sibling) == 1,
                "Settled throttle effects disagreed with exact arm or remote effect counts.");
        System.out.println("PASS original throttle retry settled after the cooldown, with exact arms and effects.");
    }

    private void retryBudget() throws Exception {
        var effect = due(workload.retry());
        for (int number = 1; number <= 6; number++) {
            int expected = number;
            await(() -> state(effect).equals("RETRYABLE") && armedCount(effect) == expected,
                    "proven transient branch did not persist");
            double delay = sql.queryForObject("""
                    SELECT extract(epoch FROM o.deferred_until - a.finished_at) FROM requests.outbox o
                    JOIN requests.outbox_attempt a USING (destination, tenant, effect_id)
                    WHERE o.destination = ? AND o.tenant = ? AND o.effect_id = ? AND a.attempt_no = o.last_attempt_no
                    """, Double.class, B25Postgresql.args(effect));
            require(delay >= 1 && delay <= 60.25, "retry deferral outside requested jitter: " + delay);
            sql.update("UPDATE requests.outbox SET deferred_until = clock_timestamp() - interval '1 second' "
                    + "WHERE status = 'RETRYABLE' AND " + KEY, B25Postgresql.args(effect));
        }
        var independent = due(workload.fastDuringBudget());
        await(() -> state(independent).equals("CONFIRMED"), "budgeted retry blocked independent work");
        require(armedCount(effect) == 6 && state(effect).equals("RETRYABLE"),
                "sixth repeat was admitted inside its rolling window");
        sql.update("""
                UPDATE requests.outbox_attempt SET armed_at = clock_timestamp() - interval '16 minutes'
                WHERE attempt_no = (SELECT attempt_no FROM requests.outbox_attempt
                    WHERE armed_at IS NOT NULL AND
                """ + KEY + " ORDER BY attempt_no OFFSET 1 LIMIT 1) AND " + KEY,
                effect.destination(), effect.tenant().value(), effect.effectId(),
                effect.destination(), effect.tenant().value(), effect.effectId());
        await(() -> state(effect).equals("CONFIRMED"), "retry budget did not reopen after the window elapsed");
        require(armedCount(effect) == 7 && remoteEffects(RETRY) == 1, "safe repeats changed the remote effect");
        System.out.println("PASS retries used bounded jitter and five repeats per window, with one remote effect.");
    }

    private void unsafe(B25App worker) throws Exception {
        var effect = due(workload.unsafe());
        await(() -> state(effect).equals("UNKNOWN"), "unsafe reply did not remain unknown");
        var number = sql.queryForObject("SELECT last_attempt_no FROM requests.outbox WHERE " + KEY, Long.class,
                B25Postgresql.args(effect));
        var id = new AttemptId(effect, number);
        require(worker.recover(id).equals(DeliveryReconciler.Result.RECORDED.name())
                && state(effect).equals("QUARANTINED") && armedCount(effect) == 1 && remoteEffects(UNSAFE) == 0,
                "unsafe ambiguity escaped exact proof quarantine");
        System.out.println("PASS unsafe delivery held UNKNOWN then quarantined with zero remote effects.");
    }

    private int highest(String destination) {
        return events.highest(destination);
    }

    private String state(EffectKey key) {
        return sql.queryForObject("SELECT status FROM requests.outbox WHERE " + KEY, String.class,
                B25Postgresql.args(key));
    }

    private int countState(String destination, String state) {
        return sql.queryForObject("SELECT count(*) FROM requests.outbox WHERE destination = ? AND status = ?",
                Integer.class, destination, state);
    }

    private int armedCount(EffectKey key) {
        return sql.queryForObject("SELECT count(*) FROM requests.outbox_attempt WHERE armed_at IS NOT NULL AND " + KEY,
                Integer.class, B25Postgresql.args(key));
    }

    private int remoteEffects(String destination) {
        return remote.queryForObject("""
                SELECT count(*) FROM synthetic_delivery.delivery_attempt WHERE destination = ? AND effect_ref IS NOT NULL
                """, Integer.class, destination);
    }

    private int remoteEffects(EffectKey key) {
        return remote.queryForObject("""
                SELECT count(*) FROM synthetic_delivery.delivery_attempt
                WHERE effect_ref IS NOT NULL AND
                """ + KEY, Integer.class, B25Postgresql.args(key));
    }

    private int occupied(String destination) {
        return sql.queryForObject("""
                SELECT count(*) FROM requests.outbox_attempt WHERE destination = ?
                    AND scheduling_invocation IS NOT NULL AND invocation_quiesced_at IS NULL
                """, Integer.class, destination);
    }

    static void await(BooleanSupplier check, String failure) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (!check.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(30);
        }
        require(check.getAsBoolean(), failure);
    }

    private static void require(boolean condition, String message) {
        B25Postgresql.require(condition, message);
    }
}
