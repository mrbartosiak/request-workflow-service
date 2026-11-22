import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.bootstrap.B32Gate;
import com.company.requests.bootstrap.B32Gate.Cut;
import com.company.requests.bootstrap.B32Gate.Event;
import com.company.requests.bootstrap.B32Gate.Point;
import com.company.requests.bootstrap.B34Probe.Mode;
import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static com.company.requests.bootstrap.B32Database.scalar;

record B34Cases(B34State state, Connection work, Connection proof, Set<String> sensitive) {
    static final String NAME = " B34 Zażółć 東京 ";
    static final String BODY = "   durable α\r\nbody 🚀 ";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int[] CREATE_LOCAL = {1, 1, 1, 1, 1, 1, 0, 0, 0, 0};
    private static final int[] PUBLISH_LOCAL = {0, 0, 1, 0, 1, 1, 1, 0, 1, 0};
    private static final int[] PURGED_LOCAL = {0, 0, 0, 0, -1, -1, 0, 0, 0, 0};

    record Intent(B34App.Call wire, IntentDigest.Intent input, RequestRef ref) {
        boolean publish() { return ref != null; }

        B34App.Call withBearer(String bearer) {
            return new B34App.Call(wire.method(), wire.path(), bearer, wire.key(), wire.body());
        }
    }

    record Lost(Intent command, B33App.Reply original, Entry publication) { }

    private B34State.Snapshot snapshot(String label) throws Exception {
        return state.snapshot(label, work, proof);
    }

    private String issue(B34App app, String bearer) throws Exception {
        var reply = app.request(new B34App.Call("POST", "/operation-keys", bearer, "", ""));
        B33Evidence.require(reply.status() == 201, "Operation key issue failed: " + B33Evidence.summary(reply));
        String key = reply.json().path("key").asString();
        B33Evidence.require(!key.isEmpty() && Instant.parse(reply.json().path("expiresAt").asString())
                .equals(B34State.CLOCK.plus(OperationKeyIssuer.VALIDITY)), "Trusted signed-key expiry mismatch.");
        sensitive.add(key);
        return key;
    }

    Intent create(B34App app) throws Exception {
        var bearer = state.child.owner();
        var type = B34Worker.TYPE;
        String key = issue(app, bearer);
        var data = B33App.JSON.writeValueAsString(Map.of("type", type.name(), "name", NAME, "body", BODY));
        return new Intent(new B34App.Call("POST", "/requests", bearer, key, data),
                new IntentDigest.Create(type, NAME, BODY), null);
    }

    Intent publish(B34App app, RequestRef ref) throws Exception {
        var bearer = state.child.approver();
        var root = "/requests/" + ref.value();
        var observation = app.request(new B34App.Call("GET", root + "/action-context?action=publish", bearer, "", ""));
        B33Evidence.require(observation.status() == 200
                && observation.json().path("ref").asString().equals(ref.value()), "Publish observation setup failed.");
        String token = observation.json().path("expectedRevision").asString();
        B33Evidence.require(!token.isEmpty(), "Publish observation token absent.");
        sensitive.add(token);
        var data = B33App.JSON.writeValueAsString(Map.of("expectedRevision", token));
        return new Intent(new B34App.Call("POST", root + "/actions/publish", bearer, issue(app, bearer), data),
                new IntentDigest.Transition(Action.PUBLISH, ref, Optional.empty(), token), ref);
    }

    private B34App.Call peer(Intent original) {
        return original.withBearer(original.publish() ? state.child.approverAlias() : state.child.alias());
    }

    void warm(B34App a, B34App b) throws Exception {
        var before = snapshot("warm-before");
        var first = create(a);
        var firstReply = a.request(first.wire);
        createResult(a, first, firstReply, trace(a, "native-commit"));
        var second = create(b);
        var secondReply = b.request(second.wire);
        createResult(b, second, secondReply, trace(b, "native-commit"));
        B33Evidence.require(!firstReply.json().path("ref").asString().equals(secondReply.json().path("ref").asString()),
                "Fresh Create keys shared a reference.");
        B33Evidence.counts("fresh baseline", snapshot("warm-after").counts(),
                before.counts().plus(B33Evidence.Delta.CREATE).plus(B33Evidence.Delta.CREATE));
    }

    void p3(B34App a, B34App b, Intent operation, String label) throws Exception {
        var before = snapshot(label + "-before");
        var pending = new Cut(Point.BEFORE_CLAIM, label + "-b-before", 1800);
        var claimed = new Cut(Point.AFTER_CLAIM, label + "-b-after", 1800);
        var failing = new Cut(Point.BEFORE_COMMIT, label + "-a", 1800);
        b.gates.arm(pending);
        b.gates.arm(claimed);
        var contender = b.send(peer(operation));
        Event waiter = b.gates.await(pending, "entered", WAIT);
        a.arm(failing, operation.publish() ? Mode.APPEND_ROLLBACK : Mode.CREATE_ROLLBACK);
        var original = a.send(operation.wire);
        Event owner = a.durability.await(failing, "entered", WAIT);
        var effect = trace(a, operation.publish() ? "append" : "finalized");
        B33Evidence.require(effect.backend() == owner.backend() && owner.backend() != waiter.backend(),
                "P3 cut and command must share a lease distinct from the contender.");
        B34State.transaction(work, effect, "in progress");
        B33Evidence.require(B32Fixture.blockers(work, waiter.backend()).equals("{}"), "B waited before its claim.");
        b.gates.release(pending);
        try {
            witnessWait(owner.backend(), waiter.backend(), contender, label);
        } finally {
            a.durability.release(failing);
        }
        finished(a.durability, failing, owner);
        finished(b.gates, pending, waiter);
        Event bClaim = b.gates.await(claimed, "entered", WAIT);
        Entry burned = null;
        B34State.Snapshot retryBefore;
        try {
            B33Evidence.require(waiter.backend() == bClaim.backend(), "Contender switched command lease.");
            problem(B33App.completed(original), 500, "internal_error");
            B34State.transaction(work, effect, "aborted");
            var aborted = snapshot(label + "-after-rollback");
            B34State.stableBusiness(before, aborted);
            released(before, aborted, 0);
            if (operation.publish()) {
                burned = state.pending(operation.ref, effect);
                B33Evidence.require(aborted.attempts() == before.attempts() + 1
                        && aborted.watermark() == before.watermark() + 1, "Rollback did not burn one ID.");
                problem(a.request(operation.wire), 503, "authority_unavailable");
                var held = snapshot(label + "-unproved-absence");
                B34State.stableEffect(aborted, held);
                released(aborted, held, 0);
                state.abort(burned);
            } else {
                B33Evidence.require(aborted.counts().latestCreationOrdinal()
                        == before.counts().latestCreationOrdinal() + 1, "Rollback did not burn one Create ordinal.");
            }
            retryBefore = snapshot(label + "-before-contender-effects");
        } finally {
            b.gates.release(claimed);
        }
        finished(b.gates, claimed, bClaim);
        var retried = B33App.completed(contender);
        var committed = trace(b, "native-commit");
        B33Evidence.require(committed.backend() == bClaim.backend() && !committed.xid().equals(effect.xid()),
                "Contender commit did not use its own separate command lease.");
        B34State.transaction(work, committed, "committed");
        var after = snapshot(label + "-contender-commit");
        if (operation.publish()) {
            problem(retried, 503, "authority_unavailable");
            Entry result = publication(operation, committed, retryBefore, after, state.pending(operation.ref, committed));
            B33Evidence.require(id(result) > id(burned), "Retry reused the burned ID.");
            proveAndReplay(a, b, operation, result, label);
        } else {
            createResult(b, operation, retried, committed);
            B33Evidence.counts(label + " retry", after.counts(), retryBefore.counts().plus(B33Evidence.Delta.CREATE));
        }
    }

    void p5Before(B34App a, B34App b, Intent command) throws Exception {
        var before = snapshot("p5-before-create-before");
        var cut = new Cut(Point.BEFORE_COMMIT, "p5-before-create", 1800);
        a.arm(cut, Mode.COMMIT_BEFORE);
        var response = a.send(command.wire);
        Event entered = a.durability.await(cut, "entered", WAIT);
        var abort = trace(a, "native-rollback");
        B33Evidence.require(abort.backend() == entered.backend(), "Precommit abort moved to another backend.");
        B34State.transaction(work, abort, "aborted");
        a.durability.release(cut);
        finished(a.durability, cut, entered);
        problem(B33App.completed(response), 503, "outcome_unknown");
        var held = snapshot("p5-before-safe-unknown");
        B34State.stableBusiness(before, held);
        released(before, held, 0);
        B33Evidence.require(held.counts().latestCreationOrdinal()
                == before.counts().latestCreationOrdinal() + 1, "Native abort did not burn exactly one Create ordinal.");
        var retry = b.request(peer(command));
        createResult(b, command, retry, trace(b, "native-commit"));
        B33Evidence.counts("P5 native abort settled on B", snapshot("p5-before-retry-committed").counts(),
                held.counts().plus(B33Evidence.Delta.CREATE));
    }

    void p5After(B34App a, B34App b, Intent command, String label) throws Exception {
        var before = snapshot(label + "-before");
        var cut = new Cut(Point.BEFORE_COMMIT, label, 1800);
        a.arm(cut, Mode.COMMIT_AFTER);
        var response = a.send(command.wire);
        Event entered = a.durability.await(cut, "entered", WAIT);
        var committed = trace(a, "native-commit");
        B33Evidence.require(committed.backend() == entered.backend(), "Postcommit cut moved to another backend.");
        B34State.transaction(work, committed, "committed");
        try {
            if (command.publish()) {
                B33Evidence.require(state.admissionId(command.ref) == 0, "Pending Publish admitted a send.");
                state.confirmScope(command.ref);
            }
        } finally {
            a.durability.release(cut);
        }
        finished(a.durability, cut, entered);
        problem(B33App.completed(response), 503, "outcome_unknown");
        var held = snapshot(label + "-held");
        released(before, held, 0);
        if (command.publish()) {
            var entry = publication(command, committed, before, held, state.pending(command.ref, committed));
            requirePublicationHold(b, command, label);
            state.confirmPublication(entry);
            B33Evidence.require(state.admissionId(command.ref) == id(entry), "Confirmation did not admit original ID.");
            replayPublish(a, b, command, entry, label);
        } else {
            trace(a, "withheld-create-lookup");
            localDelta(before, held, CREATE_LOCAL);
            createResult(b, command, b.request(peer(command)), committed);
            var after = snapshot(label + "-replay");
            B34State.stableEffect(held, after);
            released(held, after, 1);
        }
    }

    Lost p4(B34App a, Intent command, String label) throws Exception {
        var before = snapshot(label + "-before");
        var cut = new Cut(Point.BEFORE_COMMIT, label + "-native-commit", 1800);
        a.arm(cut, Mode.COMMIT_PAUSE);
        var lost = a.lose(command.wire);
        Event entered = a.durability.await(cut, "entered", WAIT);
        var committed = trace(a, "native-commit");
        B33Evidence.require(committed.backend() == entered.backend(), "Reply-loss cut moved to another backend.");
        B34State.transaction(work, committed, "committed");
        Entry entry = null;
        try {
            if (command.publish()) {
                entry = state.pending(command.ref, committed);
                B33Evidence.require(state.admissionId(command.ref) == 0, "Unconfirmed dropped Publish admitted a send.");
                state.confirmScope(command.ref);
                state.confirmPublication(entry);
            }
        } finally {
            a.durability.release(cut);
        }
        finished(a.durability, cut, entered);
        var reply = B33App.completed(lost);
        var after = snapshot(label + "-dropped");
        if (command.publish()) {
            publication(command, committed, before, after, entry);
            publishResult(reply, entry);
        } else {
            createResult(a, command, reply, committed);
            B33Evidence.counts(label, after.counts(), before.counts().plus(B33Evidence.Delta.CREATE));
        }
        released(before, after, 1);
        return new Lost(command, reply, entry);
    }

    void retryLost(List<Lost> lost, B34App b, B34App restarted) throws Exception {
        for (var item : lost) {
            var label = item.command.publish() ? "p4 publish" : "p4 create";
            var before = snapshot(label + "-before-replays");
            var peer = b.request(peer(item.command));
            var newProcess = restarted.request(item.command.wire);
            B33Evidence.sameSuccess(label + " separate peer replay", item.original, peer);
            B33Evidence.sameSuccess(label + " restarted original replay", item.original, newProcess);
            var after = snapshot(label + "-after-replays");
            B34State.stableEffect(before, after);
            released(before, after, 2);
        }
    }

    void expire(List<Lost> lost, B34App b, B34App restarted) throws Exception {
        var before = snapshot("p4-before-expiry");
        var expiry = B34State.CLOCK.plus(OperationKeyIssuer.VALIDITY);
        b.setClock(expiry);
        restarted.setClock(expiry);
        int bTraces = b.receiptEvents().size();
        int aTraces = restarted.receiptEvents().size();
        expireRetries(lost, b, restarted);
        var expired = snapshot("p4-at-signed-expiry");
        B34State.stableEffect(before, expired);
        released(before, expired, 0);
        var cutoff = expiry.plus(B34State.RECEIPT_RETENTION);
        var beforeCutoff = cutoff.minusNanos(1);
        b.setClock(beforeCutoff);
        restarted.setClock(beforeCutoff);
        for (var item : lost) {
            var actor = item.command.publish() ? B34State.PUBLISHER : B34State.OWNER;
            var key = item.command.wire.key();
            B33Evidence.require(state.liveReceiptCount(work, actor, key) == 1
                    && state.purge(actor, key, beforeCutoff) == 0 && state.liveReceiptCount(work, actor, key) == 1,
                    "A receipt changed before the exact trusted retention cutoff.");
        }
        var retained = snapshot("p4-one-nanosecond-before-retention-cutoff");
        B34State.stableEffect(expired, retained);
        released(expired, retained, 0);
        b.setClock(cutoff);
        restarted.setClock(cutoff);
        for (var item : lost) {
            var actor = item.command.publish() ? B34State.PUBLISHER : B34State.OWNER;
            var key = item.command.wire.key();
            B33Evidence.require(state.purge(actor, key, cutoff) == 1 && state.liveReceiptCount(work, actor, key) == 0,
                    "Receipt cleanup did not physically delete exactly one original scoped receipt.");
            var action = item.command.publish() ? "Publish" : "Create";
            var cleaned = snapshot("p4-after-" + action + "-receipt-cleanup");
            localDelta(retained, cleaned, PURGED_LOCAL);
            B34State.cleanedReceipt(retained, cleaned, item.command.publish());
            released(retained, cleaned, 0);
            if (item.command.publish()) {
                state.exactRetirement(work, item.publication, key);
            }
            System.out.printf("PASS %s retained through=%s deleted=1 at=%s live-receipts=0%n",
                    action, beforeCutoff, cutoff);
            retained = cleaned;
        }
        expireRetries(lost, b, restarted);
        B33Evidence.require(bTraces == b.receiptEvents().size() && aTraces == restarted.receiptEvents().size(),
                "A signed-expired command claimed or looked up a receipt.");
        var after = snapshot("p4-cleanup-expired-retries");
        B34State.stableEffect(retained, after);
        released(retained, after, 0);
    }

    private void expireRetries(List<Lost> lost, B34App b, B34App a) throws Exception {
        for (var item : lost) {
            problem(b.request(peer(item.command)), 409, "key_expired");
            problem(a.request(item.command.wire), 409, "key_expired");
        }
    }

    private void createResult(B34App app, Intent command, B33App.Reply reply, B34App.Trace commit) throws Exception {
        String ref = reply.json().path("ref").asString();
        B33Evidence.require(!ref.isBlank(), "Authorized Create lacks a reference: " + B33Evidence.summary(reply));
        B33Evidence.success(reply, 201, "create", ref, 1, "CREATED");
        state.exactReceipt(work, B34State.OWNER, command.wire.key(), command.input, ref, "CREATE", "CREATED", 1,
                null, commit.xid());
        B33Evidence.require(scalar(work, """
                SELECT count(*) FROM requests.request r JOIN requests.request_body b USING (organization, ref)
                JOIN requests.request_assignment a USING (organization, ref)
                WHERE r.ref = ? AND r.name = ? AND b.body = ? AND r.originating_person_id = ?
                  AND r.originating_person_id = a.assignee_person_id AND r.xmin = b.xmin AND r.xmin = a.xmin
                """, ref, NAME, BODY, B34State.OWNER.person().orElseThrow().value()).equals("1"),
                "Original Create request, assignment and body differ.");
        System.out.printf("PASS %s Create exact receipt/bytes result-ref-sha256=%s backend=%d xid=%s%n",
                app.name, B33Evidence.digest(ref), commit.backend(), commit.xid());
    }

    private Entry publication(Intent command, B34App.Trace trace, B34State.Snapshot before,
                              B34State.Snapshot after, Entry entry) throws Exception {
        localDelta(before, after, PUBLISH_LOCAL);
        B33Evidence.require(after.outbox() == before.outbox() + 1 && after.witness() == before.witness() + 1
                && after.attempts() == before.attempts() + 1 && after.watermark() == before.watermark() + 1
                && after.counts().latestCreationOrdinal() == before.counts().latestCreationOrdinal(),
                "Publish did not add exactly one transition, finalized receipt, witness and obligation.");
        state.exactReceipt(work, B34State.PUBLISHER, command.wire.key(), command.input, command.ref.value(),
                "PUBLISH", "PUBLISHED", entry.attempt().resultRevision(), id(entry), trace.xid());
        state.exactPublication(work, entry, command.wire.key());
        return entry;
    }

    private void proveAndReplay(B34App a, B34App b, Intent command, Entry entry, String label) throws Exception {
        B33Evidence.require(state.admissionId(command.ref) == 0, "Unconfirmed Publish admitted a send.");
        state.confirmScope(command.ref);
        requirePublicationHold(b, command, label);
        state.confirmPublication(entry);
        B33Evidence.require(state.admissionId(command.ref) == id(entry), "Publication confirmation lost exact ID.");
        replayPublish(a, b, command, entry, label);
    }

    private void requirePublicationHold(B34App b, Intent command, String label) throws Exception {
        var before = snapshot(label + "-scope-only");
        problem(b.request(peer(command)), 503, "outcome_unknown");
        var after = snapshot(label + "-publication-withheld");
        B34State.stableEffect(before, after);
        released(before, after, 0);
        B33Evidence.require(state.admissionId(command.ref) == 0, "Scope-only proof admitted a send.");
    }

    private void replayPublish(B34App a, B34App b, Intent command, Entry entry, String label) throws Exception {
        var before = snapshot(label + "-before-replay");
        var replay = b.request(peer(command));
        publishResult(replay, entry);
        var other = a.request(command.wire);
        B33Evidence.sameSuccess(label + " original ID on both peers", replay, other);
        var after = snapshot(label + "-after-replay");
        B34State.stableEffect(before, after);
        released(before, after, 2);
    }

    private static long id(Entry entry) {
        return entry.allocation().orElseThrow().value();
    }

    private static void publishResult(B33App.Reply reply, Entry entry) {
        var attempt = entry.attempt();
        var expected = B33App.JSON.valueToTree(Map.of("ref", attempt.ref().value(), "action", "publish",
                "resultRevision", Long.toString(attempt.resultRevision()), "state", "PUBLISHED",
                "publicationId", Long.toString(id(entry))));
        B33Evidence.require(reply.status() == 200 && reply.json().equals(expected) && reply.header("location").isEmpty()
                && reply.header("cache-control").equals("no-store")
                && reply.header("content-type").equalsIgnoreCase("application/json;charset=UTF-8"),
                "Wrong exact authorized Publish result: " + B33Evidence.summary(reply));
    }

    private static void localDelta(B34State.Snapshot before, B34State.Snapshot after, int[] changes) {
        var original = before.counts().workflow();
        var expected = IntStream.range(0, original.size()).mapToObj(i -> original.get(i) + changes[i]).toList();
        B33Evidence.require(expected.equals(after.counts().workflow()),
                "Wrong workflow deltas actual=" + after.counts().workflow() + " expected=" + expected);
    }

    private static void released(B34State.Snapshot before, B34State.Snapshot after, int expected) {
        var previous = before.counts().proof();
        var current = after.counts().proof();
        B33Evidence.require(current.get(1) - previous.get(1) == expected
                && current.get(2) - previous.get(2) == expected && current.get(3).equals(previous.get(3)),
                "Wrong preparation, handoff or client acknowledgment delta.");
    }

    private static void problem(B33App.Reply reply, int status, String code) {
        if (List.of("outcome_unknown", "key_expired").contains(code)) {
            B33Evidence.problem(reply, status, code);
        } else {
            String title = status == 500 ? "Internal server error" : "Authority unavailable";
            var json = reply.json();
            String correlation = json.path("correlationId").asString();
            B33Evidence.require(reply.status() == status && json.size() == 5
                    && json.path("type").asString().equals("urn:requests:problem:" + code)
                    && json.path("title").asString().equals(title) && json.path("status").asInt() == status
                    && UUID.fromString(correlation).toString().equals(correlation)
                    && json.path("instance").asString().equals("urn:uuid:" + correlation)
                    && reply.header("location").isEmpty() && reply.header("cache-control").equals("no-store")
                    && reply.header("content-type").matches("(?i)application/problem\\+json(?:;\\s*charset=UTF-8)?"),
                    "Unexpected safe response: " + B33Evidence.summary(reply));
        }
        System.out.printf("PASS safe HTTP=%d type=%s body-fields=%d%n", status, code, reply.json().size());
    }

    private static B34App.Trace trace(B34App app, String phase) throws Exception {
        return app.traces().stream().filter(row -> row.phase().equals(phase)).reduce((old, recent) -> recent).orElseThrow();
    }

    private static void finished(B32Gate gate, Cut cut, Event at) throws Exception {
        var finished = gate.await(cut, "finished", WAIT);
        B33Evidence.require(finished.backend() == at.backend() && finished.outcome().equals("released")
                && !finished.at().isBefore(at.at()), "Fixture latch did not finish on expected lease.");
    }

    private void witnessWait(int blocker, int waiter, CompletableFuture<B33App.Reply> contender, String label)
            throws Exception {
        long stop = System.nanoTime() + Duration.ofMillis(180).toNanos();
        String observed;
        do {
            observed = B32Fixture.blockers(work, waiter);
            if (!observed.equals("{}")) {
                B33Evidence.exactBlocker(observed, blocker);
                B33Evidence.require(!contender.isDone(), "HTTP contender finished before native observation.");
                System.out.printf("PASS %s pg_blocking_pids(%d)=[%d]%n", label, waiter, blocker);
                return;
            }
            Thread.sleep(2);
        } while (!contender.isDone() && System.nanoTime() < stop);
        B33Evidence.exactBlocker(observed, blocker);
    }
}
