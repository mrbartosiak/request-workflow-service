import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.IntentDigest;
import com.company.requests.application.OperationKeyIssuer;
import com.company.requests.bootstrap.B32Database.Store;
import com.company.requests.bootstrap.B32Gate.Cut;
import com.company.requests.bootstrap.B32Gate.Event;
import com.company.requests.bootstrap.B32Gate.Point;
import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.company.requests.bootstrap.B32Database.scalar;

/** P1/P2 against two B14 service processes and one independently certified writer. */
public final class B33Harness {
    static final Instant CLOCK = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final Duration BARRIER_WAIT = Duration.ofSeconds(4);
    private static final String NAME = " Case Zażółć 東京 ";
    private static final String BODY = "  line 1\r\nline 2 🚀  ";
    private static final String ALTERED = BODY + "é";
    private static final String EDITED = "Edit α\n  ";
    private static final String HIDDEN_EDIT = " Conceal β ";
    private static final RequestType TYPE = B10Postgresql.TYPE;
    private static final String CREATED = "CREATED";
    private static final String VERIFIED = "VERIFIED";
    private static final String ACCEPTED = "ACCEPTED";
    private static final B33Evidence.Delta CREATE = B33Evidence.Delta.CREATE;
    private static final B33Evidence.Delta EDIT = B33Evidence.Delta.EDIT;
    private static final B33Evidence.Delta TRANSITION = B33Evidence.Delta.TRANSITION;
    private static final B33Evidence.Delta RELEASE = B33Evidence.Delta.RELEASE;
    private static final B33Evidence.Delta CONFIRMED_SCOPE = B33Evidence.Delta.CONFIRMED_SCOPE;
    private static final Set<String> PRIVATE_CLASSES = Set.of("B08MvcSession", "B08Postgresql", "B10FixtureSources",
            "B10Postgresql", "B14Postgresql", "B15Postgresql", "B32", "B33", "QualifyPostgresql");
    private static final String CONTROLLER = "com/company/requests/adapter/http/SyntheticRequestActionController.class";

    private B33Harness() { }

    public static void main(String[] args) {
        LogManager.getLogManager().reset();
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            if (args.length == 2 && args[0].equals("scan")) {
                scan(args[1]);
            } else if (args.length == 2 && args[0].equals("controls")) {
                controls(args[1]);
            } else {
                run(args);
            }
        } catch (Exception | AssertionError failure) {
            B33Evidence.failure(failure);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        B33Evidence.require(args.length == 5 && List.of("run", "cleanup").contains(args[0])
                && args[3].matches("b33-[a-z0-9-]{1,23}"), "Usage: B33Harness run|cleanup BIN RUNS b33-ID CLASSPATH");
        if (args[0].equals("run")) {
            scan(args[4]);
        }
        String id = "b15-" + args[3];
        Path run = Path.of(args[2]).resolve(id).toAbsolutePath().normalize();
        QualifyPostgresql.terminalRequestActions(new String[]{args[0], args[1], args[2], id},
                (workflow, proof, projector, resolver, createProof, logins, restore) -> {
                    B33Profile profile;
                    try (var sources = new B10FixtureSources()) {
                        profile = new B33Profile(workflow, proof, projector, resolver, createProof, sources);
                    }
                    verify(profile, run);
                });
        System.out.println(args[0].equals("run") ? "PASS B33 scenarios and owned PostgreSQL teardown."
                : "PASS B33 owned PostgreSQL cleanup.");
    }

    private static void verify(B33Profile profile, Path run) throws Exception {
        var child = profile.child;
        var sensitive = new HashSet<>(child.fields());
        sensitive.removeAll(List.of(child.writer().writerId().toString(), Long.toString(child.writer().generation())));
        sensitive.addAll(List.of(NAME, BODY, ALTERED, EDITED, HIDDEN_EDIT));
        try (var work = profile.observer(Store.WORKFLOW); var proof = profile.observer(Store.PROOF)) {
            B33Evidence.verifyObserver(work, Store.WORKFLOW);
            B33Evidence.verifyObserver(proof, Store.PROOF);
            try (AutoCloseable noLeaks = () -> B33Evidence.poolsClosed(work, proof);
                 var a = B33App.start(run, "b33-a", child, CLOCK);
                 var b = B33App.start(run, "b33-b", child, CLOCK)) {
                B33Evidence.verifyApps(a, b, work, proof);
                new Cases(profile, a, b, work, proof, sensitive).exercise();
            }
        }
        audit(run, sensitive);
    }

    private record Call(String method, String path, String body, IntentDigest.Intent intent) {
        CompletableFuture<B33App.Reply> send(B33App app, String actor, String key) {
            return app.send(method, path, actor, key, body);
        }
    }

    private record Race(B33App.Reply winner, B33App.Reply contender) { }

    private enum Outcome { SETTLED, TIMEOUT, CONFLICT, CONCEALED }

    private record Cases(B33Profile profile, B33App a, B33App b, Connection work, Connection proof,
                         Set<String> sensitive) {
        void exercise() throws Exception {
            var original = create(TYPE, BODY);
            var changed = create(TYPE, ALTERED);
            var owner = B33Profile.OWNER;
            var bearer = profile.child.owner();
            var alias = profile.child.alias();

            // Warm the real command and release paths on both JVMs with the two fresh-key controls.
            var beforeFresh = B33Evidence.read("p1 before fresh keys", work, proof);
            String freshA = issue(a, bearer);
            String freshB = issue(b, alias);
            B33Evidence.require(!freshA.equals(freshB), "Fresh issuance reused a signed key.");
            String third = created(B33App.completed(original.send(a, bearer, freshA)), owner, freshA, original);
            var afterFirst = B33Evidence.read("p1 after first fresh key", work, proof);
            B33Evidence.counts("first fresh key", afterFirst, beforeFresh.plus(CREATE));
            String fourth = created(B33App.completed(original.send(b, alias, freshB)), owner, freshB, original);
            var afterSecond = B33Evidence.read("p1 after both fresh keys", work, proof);
            reject("actual additional durable HTTP Create", "Unexpected durable counts",
                    () -> B33Evidence.counts("fresh-write control", afterSecond, afterFirst));
            B33Evidence.counts("both fresh keys", afterSecond, beforeFresh.plus(CREATE).plus(CREATE));

            String settledKey = issue(a, bearer);
            var settled = race("p1-settled", settledKey, bearer, alias, original, original, CREATE, Outcome.SETTLED);
            String first = created(settled.winner, owner, settledKey, original);
            B33Evidence.sameSuccess("p1 settled peer", settled.winner, settled.contender);

            String timedKey = issue(b, alias);
            var timed = race("p1-timeout", timedKey, bearer, alias, original, original, CREATE, Outcome.TIMEOUT);
            String second = created(timed.winner, owner, timedKey, original);
            var beforeRetry = B33Evidence.read("p1 before exact retry", work, proof);
            var business = B33Evidence.fingerprint(work, proof);
            int firstTrace = b.traces().size();
            var replay = B33App.completed(original.send(b, alias, timedKey));
            B33Evidence.sameSuccess("p1 retry after native lock timeout", timed.winner, replay);
            B33Evidence.traces("p1 exact retry", b, firstTrace, "CLAIM_DUPLICATE:-", "LOOKUP:-");
            B33Evidence.counts("p1 retry", B33Evidence.read("p1 retry", work, proof), beforeRetry.plus(RELEASE));
            B33Evidence.require(business.equals(B33Evidence.fingerprint(work, proof)), "Retry changed business rows.");

            B33Evidence.require(Set.of(first, second, third, fourth).size() == 4, "Fresh keys reused a request identity.");
            System.out.println("PASS P1 four distinct references and signed receipts; replay added only release evidence.");

            String differed = issue(a, bearer);
            var sameRoute = race("p2-create-changed-body", differed, bearer, alias, original, changed, CREATE, Outcome.CONFLICT);
            created(sameRoute.winner, owner, differed, original);

            var edit = action(b, third, Action.EDIT, alias, EDITED);
            String createKey = issue(a, bearer);
            var across = race("p2-create-vs-edit", createKey, bearer, alias, original, edit, CREATE, Outcome.CONFLICT);
            created(across.winner, owner, createKey, original);
            state(third, BODY, CREATED, 1, 1);

            String editKey = issue(a, bearer);
            var edited = race("p2-edit-vs-create", editKey, bearer, alias, edit, changed, EDIT, Outcome.CONFLICT);
            actionEffect(edited.winner, third, Action.EDIT, owner, editKey, edit, EDITED, CREATED, 2, 1);

            var verify = action(a, third, Action.VERIFY, bearer, null);
            String verifyKey = issue(b, alias);
            var verified = race("p2-verify-vs-create", verifyKey, bearer, alias, verify, changed, TRANSITION, Outcome.CONFLICT);
            actionEffect(verified.winner, third, Action.VERIFY, owner, verifyKey, verify, EDITED, VERIFIED, 3, 2);

            var beforeAssign = B33Evidence.read("p2 before assignment", work, proof);
            profile.assign(third);
            B33Evidence.counts("audited assignment", B33Evidence.read("p2 after assignment", work, proof),
                    beforeAssign.plus(CONFIRMED_SCOPE));
            var approver = profile.child.approver();
            var approverAlias = profile.child.approverAlias();
            var accept = action(b, third, Action.ACCEPT, approverAlias, null);
            String acceptKey = issue(a, approver);
            var accepted = race("p2-accept-vs-create", acceptKey, approver, approverAlias, accept, changed,
                    TRANSITION, Outcome.CONFLICT);
            actionEffect(accepted.winner, third, Action.ACCEPT, B33Profile.APPROVER, acceptKey, accept, EDITED, ACCEPTED, 4, 3);

            conceal(owner, bearer, alias, fourth, changed);
            expiry(original, settledKey, alias);
        }

        private void conceal(AuthenticatedActor owner, String bearer, String alias, String target, Call changed) throws Exception {
            String key = issue(a, bearer);
            var ordinary = create(RequestType.INTERNAL_REQUEST, BODY);
            var before = B33Evidence.read("p2 ordinary denied input baseline", work, proof);
            int first = b.traces().size();
            var control = B33App.completed(ordinary.send(b, alias, key));
            B33Evidence.problem(control, 404, "not_found");
            B33Evidence.traces("p2 submitted concealment before receipt", b, first);
            B33Evidence.counts("ordinary submitted denial", B33Evidence.read("p2 ordinary submitted denial", work, proof), before);

            var edit = action(a, target, Action.EDIT, bearer, HIDDEN_EDIT);
            var concealed = race("p2-concealed-original", key, bearer, alias, edit, changed, EDIT, Outcome.CONCEALED);
            actionEffect(concealed.winner, target, Action.EDIT, owner, key, edit, HIDDEN_EDIT, CREATED, 2, 1);
            B33Evidence.sameDenial(control, concealed.contender);
            first = b.traces().size();
            var afterOriginal = B33Evidence.read("p2 retained but concealed", work, proof);
            var existingKeyDenied = B33App.completed(ordinary.send(b, alias, key));
            B33Evidence.sameDenial(existingKeyDenied, concealed.contender);
            B33Evidence.traces("p2 submitted concealment with retained receipt", b, first);
            B33Evidence.counts("p2 retained key submitted denial", B33Evidence.read(work, proof), afterOriginal);

            profile.setEditResult(true);
            var restored = B33Evidence.read("p2 after confirmed reinstatement", work, proof);
            B33Evidence.counts("confirmed result grant reinstatement", restored, afterOriginal.plus(CONFIRMED_SCOPE));
            first = b.traces().size();
            B33Evidence.problem(B33App.completed(changed.send(b, alias, key)), 409, "key_conflict");
            B33Evidence.traces("p2 both sides disclosable again", b, first, "CLAIM_DUPLICATE:-", "LOOKUP:-");
            B33Evidence.counts("reopened conflict", B33Evidence.read(work, proof), restored);
        }

        private void expiry(Call original, String key, String alias) throws Exception {
            var before = B33Evidence.read("before verified expiry", work, proof);
            var business = B33Evidence.fingerprint(work, proof);
            var cut = CLOCK.plus(OperationKeyIssuer.VALIDITY);
            a.setClock(cut);
            b.setClock(cut);
            int first = b.traces().size();
            B33Evidence.problem(B33App.completed(original.send(b, alias, key)), 409, "key_expired");
            B33Evidence.traces("expired same key skips receipt", b, first);
            B33Evidence.counts("expiry", B33Evidence.read("at signed expiry", work, proof), before);
            B33Evidence.require(business.equals(B33Evidence.fingerprint(work, proof)), "Expiry changed business rows.");
        }

        private String issue(B33App app, String bearer) throws Exception {
            var response = B33App.completed(app.send("POST", "/operation-keys", bearer, "", ""));
            B33Evidence.require(response.status() == 201, "Key issuance failed: " + B33Evidence.summary(response));
            var fields = response.json();
            String key = fields.path("key").asString();
            B33Evidence.require(fields.size() == 2 && !key.isBlank() && !sensitive.contains(key)
                    && Instant.parse(fields.path("expiresAt").asString()).equals(CLOCK.plus(OperationKeyIssuer.VALIDITY)),
                    "Signed key or trusted time differs.");
            sensitive.add(key);
            return key;
        }

        private Call action(B33App app, String ref, Action action, String bearer, String body) throws Exception {
            String name = action.name().toLowerCase(Locale.ROOT);
            String root = "/requests/" + ref;
            var context = B33App.completed(app.send("GET", root + "/action-context?action=" + name, bearer, "", ""));
            B33Evidence.require(context.status() == 200 && context.json().path("ref").asString().equals(ref)
                    && context.json().path("action").asString().equals(name),
                    "Missing currently authorized " + name + " context: " + B33Evidence.summary(context));
            String token = context.json().path("expectedRevision").asString();
            B33Evidence.require(!token.isBlank(), "Missing signed action context.");
            sensitive.add(token);
            if (action == Action.EDIT) {
                return new Call("PATCH", root + "/body",
                        B33App.JSON.writeValueAsString(Map.of("body", body, "expectedRevision", token)),
                        new IntentDigest.Edit(new RequestRef(ref), body, token));
            }
            return new Call("POST", root + "/actions/" + name,
                    B33App.JSON.writeValueAsString(Map.of("expectedRevision", token)),
                    new IntentDigest.Transition(action, new RequestRef(ref), java.util.Optional.empty(), token));
        }

        private Race race(String label, String key, String bearer, String alias, Call original, Call competing,
                          B33Evidence.Delta effect, Outcome outcome) throws Exception {
            var before = B33Evidence.read(label + " before", work, proof);
            int aTrace = a.traces().size();
            int bTrace = b.traces().size();
            var preClaim = new Cut(Point.BEFORE_CLAIM, label, 1800);
            var claimed = new Cut(Point.AFTER_CLAIM, label, 1800);
            var duplicate = new Cut(Point.BEFORE_RELEASE, label, 1700);
            b.gates.arm(preClaim);
            a.gates.arm(claimed);
            if (outcome == Outcome.CONCEALED) {
                b.gates.arm(duplicate);
            }
            var contender = competing.send(b, alias, key);
            Event waiter = entered(b, preClaim, contender);
            var winner = original.send(a, bearer, key);
            Event blocker = entered(a, claimed, winner);
            String unblocked = B32Fixture.blockers(work, waiter.backend());
            B33Evidence.require(unblocked.equals("{}"), "B waited before its native claim.");
            if (outcome == Outcome.SETTLED) {
                reject("real nonwaiting backend", "Missing exact PostgreSQL blocker",
                        () -> B33Evidence.exactBlocker(unblocked, blocker.backend()));
            }
            B33Evidence.counts(label + " A holds tentative claim",
                    B33Evidence.read(label + " A holds tentative claim", work, proof), before);
            b.gates.release(preClaim);
            Instant observed = blocking(work, blocker.backend(), waiter.backend(), contender);
            if (outcome != Outcome.TIMEOUT) {
                a.gates.release(claimed);
            }
            Event bReleased = finished(b, preClaim, waiter);
            System.out.printf("PASS %s pg_blocking_pids(%d)=[%d] observed=%s after-release-ms=%d%n",
                    label, waiter.backend(), blocker.backend(), observed, Duration.between(bReleased.at(), observed).toMillis());

            if (outcome == Outcome.TIMEOUT) {
                var response = B33App.completed(contender);
                B33Evidence.problem(response, 503, "outcome_unknown");
                B33Evidence.traces(label + " timed loser", b, bTrace, "CLAIM_ERROR:55P03");
                var attempted = b.traces().get(bTrace);
                long millis = Duration.between(bReleased.at(), attempted.at()).toMillis();
                B33Evidence.require(attempted.backend() == waiter.backend() && millis >= 195 && millis < 1500
                        && !winner.isDone(), "Contender did not hit its native 200 ms deadline while A was held.");
                System.out.printf("PASS %s timed loser HTTP=503/outcome_unknown SQLSTATE=55P03 lock-elapsed-ms=%d%n", label, millis);
                B33Evidence.counts(label + " B timed out while A held",
                        B33Evidence.read(label + " B timed out while A held", work, proof), before);
                a.gates.release(claimed);
            }
            Event aReleased = finished(a, claimed, blocker);
            B33Evidence.require(outcome == Outcome.TIMEOUT || (!aReleased.at().isBefore(observed)
                            && Duration.between(bReleased.at(), aReleased.at()).toMillis() < 200),
                    "A was released after B's native claim deadline.");

            var winningReply = B33App.completed(winner);
            B33Evidence.traces(label + " winner", a, aTrace, "CLAIM_WON:-", "LOOKUP:-");
            B33Evidence.require(winningReply.status() == (effect == CREATE ? 201 : 200),
                    label + " winner returned " + B33Evidence.summary(winningReply));
            var expected = before.plus(effect);
            if (outcome == Outcome.CONCEALED) {
                var conflictingClaim = entered(b, duplicate, contender);
                B33Evidence.require(conflictingClaim.backend() == waiter.backend(),
                        "Losing claim changed JDBC backend before the disclosure cut.");
                B33Evidence.traces(label + " before original lookup", b, bTrace, "CLAIM_DUPLICATE:-");
                B33Evidence.counts(label + " original disclosed to A", B33Evidence.read(label + " original disclosed to A", work, proof),
                        expected);
                var originalEffect = B33Evidence.fingerprint(work, proof);
                profile.setEditResult(false);
                expected = expected.plus(CONFIRMED_SCOPE);
                B33Evidence.counts(label + " confirmed revocation", B33Evidence.read(label + " confirmed revocation", work, proof), expected);
                B33Evidence.require(originalEffect.equals(B33Evidence.fingerprint(work, proof)),
                        "Grant revocation changed the committed business effect.");
                b.gates.release(duplicate);
                finished(b, duplicate, conflictingClaim);
                B33Evidence.require(originalEffect.equals(B33Evidence.fingerprint(work, proof)),
                        "Concealed contender changed business rows.");
            }

            var losingReply = B33App.completed(contender);
            if (outcome != Outcome.TIMEOUT) {
                B33Evidence.traces(label + " loser", b, bTrace, "CLAIM_DUPLICATE:-", "LOOKUP:-");
                var attempted = b.traces().get(bTrace);
                B33Evidence.require(attempted.backend() == waiter.backend() && !attempted.at().isBefore(aReleased.at()),
                        "Duplicate result did not settle on the expected waiting backend after A was released.");
                System.out.printf("PASS %s duplicate-claim-elapsed-ms=%d%n", label,
                        Duration.between(bReleased.at(), attempted.at()).toMillis());
            }
            switch (outcome) {
                case SETTLED -> expected = expected.plus(RELEASE);
                case CONFLICT -> B33Evidence.problem(losingReply, 409, "key_conflict");
                case CONCEALED -> B33Evidence.problem(losingReply, 404, "not_found");
                case TIMEOUT -> { } // Already checked while A was held.
            }
            B33Evidence.counts(label + " final", B33Evidence.read(label + " final", work, proof), expected);
            System.out.printf("PASS %s winner=%s contender=%s%n", label, B33Evidence.summary(winningReply),
                    B33Evidence.summary(losingReply));
            return new Race(winningReply, losingReply);
        }

        private Event entered(B33App app, Cut cut, CompletableFuture<B33App.Reply> pending) throws Exception {
            var event = app.gates.await(cut, "entered", BARRIER_WAIT);
            B33Evidence.require(event.outcome().equals("entered") && app.pool(Store.WORKFLOW).contains(event.backend())
                    && B32Fixture.liveApp(work, event.backend()).equals(app.name) && !pending.isDone(),
                    "B33 cut did not hold an in-flight command on its app-owned backend.");
            System.out.printf("ENTERED %s %s %s backend=%d at=%s%n", app.name, cut.id(), cut.point(),
                    event.backend(), event.at());
            return event;
        }

        private Event finished(B33App app, Cut cut, Event entered) throws Exception {
            var event = app.gates.await(cut, "finished", BARRIER_WAIT);
            B33Evidence.require(event.outcome().equals("released") && event.backend() == entered.backend(),
                    "B33 gate was not explicitly released or changed backend.");
            System.out.printf("RELEASED %s %s %s backend=%d at=%s%n", app.name, cut.id(), cut.point(),
                    event.backend(), event.at());
            return event;
        }

        private String created(B33App.Reply reply, AuthenticatedActor actor, String key, Call call) throws Exception {
            B33Evidence.require(reply.status() == 201, "Expected Create success, got " + B33Evidence.summary(reply));
            String ref = reply.json().path("ref").asString();
            B33Evidence.require(ref.matches("[A-Za-z0-9_-]{32}"), "Invalid issued Create reference.");
            sensitive.add(ref);
            B33Evidence.success(reply, 201, "create", ref, 1, CREATED);
            state(ref, ((IntentDigest.Create) call.intent).body(), CREATED, 1, 1);
            history(ref, Action.CREATE, actor, 1, 1, CREATED);
            receipt(ref, Action.CREATE, actor, key, call, 1, CREATED, 201);
            String order = scalar(work, """
                    SELECT concat_ws('/', created_epoch, created_ordinal)
                    FROM requests.request r
                    JOIN requests.request_assignment a USING (organization, ref)
                    WHERE ref = ? AND organization = ? AND request_type = ?
                      AND originating_person_id = ? AND assignee_person_id = ? AND assignment_version = 1
                    """, ref, actor.organization().value(), TYPE.name(), actor.person().orElseThrow().value(),
                    actor.person().orElseThrow().value());
            B33Evidence.require(order.equals(scalar(proof, """
                    SELECT concat_ws('/', created_epoch, created_ordinal)
                    FROM request_authority.creation_reservation WHERE organization = ? AND request_ref = ?
                    """, actor.organization().value(), ref)), "Create reservation and workflow order differ.");
            System.out.printf("PASS exact Create request/assignment/history/receipt/reservation ref-sha256=%s order=%s%n",
                    B33Evidence.digest(ref), order);
            return ref;
        }

        private void actionEffect(B33App.Reply reply, String ref, Action action, AuthenticatedActor actor, String key,
                                  Call call, String body, String state, int revision, int sequence) throws Exception {
            B33Evidence.success(reply, 200, action.name().toLowerCase(Locale.ROOT), ref, revision, state);
            state(ref, body, state, revision, sequence);
            receipt(ref, action, actor, key, call, revision, state, 200);
            if (action != Action.EDIT) {
                history(ref, action, actor, sequence, revision, state);
            }
            System.out.printf("PASS exact %s request/history/receipt ref-sha256=%s revision=%d sequence=%d state=%s%n",
                    action, B33Evidence.digest(ref), revision, sequence, state);
        }

        private void state(String ref, String body, String state, int revision, int sequence) throws Exception {
            B33Evidence.require(scalar(work, """
                    SELECT count(*) FROM requests.request r JOIN requests.request_body b USING (organization, ref)
                    WHERE ref = ? AND organization = ? AND name = ? AND body = ? AND state = ? AND revision = ?
                      AND last_sequence = ? AND retention_status = 'RETAINED'
                      AND publication_id IS NULL AND publication_namespace IS NULL
                      AND (SELECT count(*) FROM requests.transition_fact f WHERE f.ref = r.ref) = ?
                    """, ref, B33Profile.OWNER.organization().value(), NAME, body, state, revision, sequence, sequence)
                    .equals("1"), "Request body, display state, revision or history length differs.");
        }

        private void history(String ref, Action action, AuthenticatedActor actor, int sequence, int revision,
                             String state) throws Exception {
            B33Evidence.require(scalar(work, """
                    SELECT count(*) FROM requests.transition_fact WHERE organization = ? AND ref = ?
                      AND sequence = ? AND revision = ? AND action = ? AND to_state = ? AND actor_ref = ?
                      AND recorded_epoch_second = ? AND recorded_nano = ?
                    """, actor.organization().value(), ref, sequence, revision, action.name(), state, actor.principal(),
                    CLOCK.getEpochSecond(), CLOCK.getNano()).equals("1"), "Exact history event differs.");
        }

        private void receipt(String ref, Action action, AuthenticatedActor actor, String key, Call call,
                             int revision, String state, int status) throws Exception {
            var verified = profile.key(actor, key, CLOCK);
            var scope = verified.receiptKey();
            var digest = profile.digest().calculate(call.intent);
            B33Evidence.require(scalar(work, """
                    SELECT count(*) FROM requests.operation_receipt
                    WHERE issuer = ? AND organization = ? AND principal = ? AND token_id = ?
                      AND intent_digest_version = ? AND encode(intent_digest, 'hex') = ?
                      AND key_generation = ? AND expires_epoch_second = ? AND expires_nano = ?
                      AND result_action = ? AND result_ref = ? AND result_revision = ? AND result_state = ?
                      AND original_status = ? AND publication_id IS NULL AND publication_namespace IS NULL
                    """, scope.issuer(), scope.organization().value(), scope.principal(), scope.tokenId(),
                    digest.version(), digest.hex(), verified.generation(), verified.expiresAt().getEpochSecond(),
                    verified.expiresAt().getNano(), action.name(), ref, revision, state, status).equals("1"),
                    "Finalized receipt does not bind the exact original key, intent and result.");
        }
    }

    private static Call create(RequestType type, String body) {
        return new Call("POST", "/requests", B33App.JSON.writeValueAsString(Map.of("type", type.name(),
                "name", NAME, "body", body)), new IntentDigest.Create(type, NAME, body));
    }

    private static Instant blocking(Connection work, int blocker, int waiter, CompletableFuture<B33App.Reply> contender)
            throws Exception {
        B33Evidence.require(blocker != waiter, "The contender and blocker share a backend.");
        long budget = System.nanoTime() + Duration.ofMillis(600).toNanos();
        String observed;
        do {
            observed = B32Fixture.blockers(work, waiter);
            if (!observed.equals("{}")) {
                B33Evidence.exactBlocker(observed, blocker);
                B33Evidence.require(!contender.isDone(), "No in-flight HTTP contender during observed native lock wait.");
                return Instant.now();
            }
            Thread.sleep(2);
        } while (!contender.isDone() && System.nanoTime() < budget);
        B33Evidence.exactBlocker(observed, blocker);
        throw new AssertionError("No in-flight PostgreSQL key wait.");
    }

    private static void audit(Path run, Set<String> sensitive) throws Exception {
        try (var files = Files.walk(run, 2)) {
            var logs = files.filter(file -> file.getFileName().toString().endsWith(".log")).toList();
            for (Path file : logs) {
                String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                B33Evidence.require(sensitive.stream().noneMatch(text::contains),
                        "Protected synthetic data appeared in a fixture evidence log.");
            }
            System.out.printf("PASS protected data audit of %d native and worker logs; no matching values%n", logs.size());
        }
    }

    private static void scan(String classpath) throws Exception {
        String[] jars = classpath.split(Pattern.quote(File.pathSeparator));
        int controllers = 0;
        for (String entry : jars) {
            var path = Path.of(entry);
            B33Evidence.require(Files.isRegularFile(path) && entry.endsWith(".jar"), "Expected a packaged runtime JAR.");
            try (var jar = new JarFile(path.toFile())) {
                B33Evidence.require(jar.stream().noneMatch(member -> {
                    String name = member.getName();
                    String filename = name.substring(name.lastIndexOf('/') + 1);
                    return PRIVATE_CLASSES.stream().anyMatch(filename::startsWith);
                }), "Private fixture class in packaged runtime.");
                controllers += jar.getJarEntry(CONTROLLER) == null ? 0 : 1;
            }
        }
        B33Evidence.require(controllers == 1, "Expected one packaged B14 HTTP controller.");
        System.out.printf("PASS %d packaged JARs contain the B14 controller and no fixture controls.%n", jars.length);
    }

    private static void controls(String classpath) throws Exception {
        scan(classpath);
        reject("unblocked backend input", "Missing exact PostgreSQL blocker", () -> B33Evidence.exactBlocker("{}", 813));
        reject("unexpected PostgreSQL blocker", "Missing exact PostgreSQL blocker",
                () -> B33Evidence.exactBlocker("{812,813}", 813));
        var counts = new B33Evidence.Counts(Collections.nCopies(10, 0L), Collections.nCopies(7, 0L), 0);
        reject("extra durable write input", "Unexpected durable counts",
                () -> B33Evidence.counts("offline no second effect", counts.plus(CREATE), counts));
        Path injected = Files.createTempFile("b33-leak-", ".jar");
        try {
            try (var output = new JarOutputStream(Files.newOutputStream(injected))) {
                output.putNextEntry(new JarEntry("com/company/requests/bootstrap/B33Runtime.class"));
                output.closeEntry();
            }
            reject("fixture class leaked into an artifact", "Private fixture class in packaged runtime",
                    () -> scan(classpath + File.pathSeparator + injected));
        } finally {
            Files.deleteIfExists(injected);
        }
    }

    @FunctionalInterface
    private interface Probe {
        void run() throws Exception;
    }

    private static void reject(String label, String failurePrefix, Probe check) throws Exception {
        try {
            check.run();
        } catch (AssertionError failure) {
            B33Evidence.require(failure.getMessage().startsWith(failurePrefix), "Negative control failed for the wrong reason.");
            System.out.println("RED rejected " + label + " with " + failurePrefix);
            return;
        }
        throw new AssertionError("Negative control passed: " + label);
    }
}
