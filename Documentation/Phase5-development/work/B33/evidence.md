# B33 execution evidence

The initial worktree base was the integrated B14 commit `0e24388ab0cba708ba5802ae8462c8cffe03234f`, tree `1fcd9050badf987b7e173a47bed339cb69500db1`. B33 is verification-only. The candidate adds fixture launchers, SQL and HTTP oracles, and instructions under `fixtures/postgresql/b33/`. [Assignment](assignment.md) · [Acceptance](../../../Phase4-development-planning/db-verification.md#b33--key-contention-and-intent-isolation--p1p2)

## B14 port-free checkpoint

Temurin 25.0.5.1+2 and Maven 3.9.16 ran strict offline `-o -B -C -ntp clean verify` with the adopted Maven settings and a private qualified cache. The default and synthetic HTTP builds each passed 328 tests in 13 suites across 11 reactor projects, with zero failures, errors or skips. The B32 packaging check inspected 26 default JARs and 30 profile JARs. The B33 scan independently found one B14 request action controller and zero private fixture classes in the 30 profile JARs. [Default verify](../../../../tmp/phase5-development/b33-fixture/logs/default-verify.log) · [Profile verify](../../../../tmp/phase5-development/b33-fixture/logs/profile-verify.log) · [Profile isolation](../../../../tmp/phase5-development/b33-fixture/logs/b33-profile-artifact-scan.log)

The same focused oracles used by the real-engine cases rejected an empty blocker list, a blocker list containing additional backends and an additional durable Create. The runtime scan also rejected a private JAR containing a fixture control. This invocation opened no PostgreSQL or HTTP sockets. [Control transcript](../../../../tmp/phase5-development/b33-fixture/logs/b33-focused-red-01-controls.log)

| B14 checkpoint log | SHA-256 |
| --- | --- |
| `default-verify.log` | `fb4b06a699917b1ae83f3388c1ced52fbbca671ad9ca162d7b875342d02c57d6` |
| `profile-verify.log` | `93ed910173377bd628551a0564f4ac99e2430599d322c0bd549970796146bfed` |
| `b33-focused-red-01-controls.log` | `0b7f92aa470ee685870da376774a60ccba43c778f593d78d3ce675aa8a5d05a0` |
| `b33-profile-artifact-scan.log` | `a6ace96ddaa8dc976b67ad7da9b7ae5a5f14a59e7a6c7d9fcd054b7bf0c8c0ea` |

## Integrated B15 candidate

The coordinator advanced the B33 worktree to independently approved B15 `aa126e1476dd60551649e97a12378bb3cf8bb49d`. The isolated B33 candidate is `195636a332cc9d31f197dabe8dd19913198dda83`, tree `8adf85a9dc312a436ff37b1546173edc55816a72`. Both Git dates are 2026-11-22 22:36 Warsaw. The patch adds eight verification fixture files. The workflow store runs the adopted V009 migration and the observed commands are Create, Edit, Verify and Accept. The proof migration history is V001–V005. [B15 approval](../B15/review-aa126e1.md) · [Candidate archive](../../../../tmp/phase5-development/b33-on-b15/candidate-195636a.tar) · [Full-index patch](../../../../tmp/phase5-development/b33-on-b15/candidate-195636a.patch)

The first draft run proved native settlement and matched both HTTP 201 envelopes. Its timeout assertion rejected the connector's safe Connection header. The fixture now checks the permitted standard Connection values alongside body framing and the application headers. The full draft repeat exercised all eight forced native waits and passed P1/P2 and signed expiry. Both invocations verified child socket refusal, empty child connection pools and clean PostgreSQL teardown. [First draft](../../../../tmp/phase5-development/b33-on-b15/logs/b33-draft-native-01-run.log) · [Full draft repeat](../../../../tmp/phase5-development/b33-on-b15/logs/b33-draft-native-02-run.log)

## Exact clean packaged verification

Strict offline verification of the clean B33 commit passed 328 tests in 13 suites in each mode, with zero failures, errors or skips. The default reactor took 37.206 seconds and the synthetic HTTP reactor took 46.673 seconds. The private Java fixture compiled from the clean checkout. The checks scanned 26 default JARs and 30 synthetic HTTP JARs. The focused controls rejected absent and additional blockers, an extra durable effect and a supplied private JAR containing a fixture class. [Default verify](../../../../tmp/phase5-development/b33-on-b15/logs/default-verify.log) · [Profile verify](../../../../tmp/phase5-development/b33-on-b15/logs/profile-verify.log) · [Artifact isolation](../../../../tmp/phase5-development/b33-on-b15/logs/b33-profile-artifact-scan.log) · [Focused controls](../../../../tmp/phase5-development/b33-on-b15/logs/b33-exact-controls-controls.log) · [Packaged runtime hashes](../../../../tmp/phase5-development/b33-on-b15/logs/runtime-sha256.csv)

## Exact native P1 and P2

The exact run used PostgreSQL 18.7 on two distinct system identifiers, pgJDBC 42.7.14 and Java 25.0.5.1+2. A and B ran as JVMs 32400 and 19184 on IPv4 loopback HTTP ports 3341 and 34670. Their workflow pools used backends `[30880,19108]` and `[37804,28068]`. The independent observer used distinct workflow and proof connections and verified read-only and write-denied SQLSTATEs 25006 and 42501. The complete transcript records each pool, role, database identity and migration hash. [Native transcript](../../../../tmp/phase5-development/b33-on-b15/logs/b33-exact-195636a-run.log) · [Worker A](../../../../tmp/phase5-development/b33-on-b15/runs/b15-b33-exact-195636a/b33-a/worker.log) · [Worker B](../../../../tmp/phase5-development/b33-on-b15/runs/b15-b33-exact-195636a/b33-b/worker.log)

| Case | Exact blocker observed for B | Time to observe | B claim result | A / B HTTP |
| --- | --- | ---: | --- | --- |
| P1 settle | `28068 → 30880` | 3 ms | Duplicate settled in 63 ms | 201 / identical 201 |
| P1 timeout | `28068 → 19108` | 3 ms | SQLSTATE 55P03 at 220 ms | 201 / 503 `outcome_unknown`, then identical 201 retry |
| Different Create body | `37804 → 19108` | 5 ms | Duplicate settled in 47 ms | 201 / 409 `key_conflict` |
| Create / Edit | `37804 → 19108` | 3 ms | Duplicate settled in 46 ms | 201 / 409 `key_conflict` |
| Edit / Create | `28068 → 19108` | 4 ms | Duplicate settled in 58 ms | 200 / 409 `key_conflict` |
| Verify / Create | `28068 → 19108` | 4 ms | Duplicate settled in 39 ms | 200 / 409 `key_conflict` |
| Accept / Create | `28068 → 19108` | 3 ms | Duplicate settled in 38 ms | 200 / 409 `key_conflict` |
| Concealed Edit / Create | `28068 → 30880` | 6 ms | Duplicate settled in 38 ms | 200 / ordinary 404 `not_found` |

The native preclaim control reported no blocker and failed the same exact-blocker assertion. The additional fresh-key Create failed the same no-additional-effect assertion when compared with the preceding state. The fresh-key acceptance verified distinct receipt keys, opaque request references and reservation ordinals. For settled and timed exact retries, the HTTP status, safe headers, Location and response bytes matched the original. Each accepted Create matched a single request, retained UTF-8 body, assignment, creation fact, signed receipt and independent reservation. Edit changed revision and body without adding a fact. Verify and Accept each added exactly one transition fact, and audited assignment made the independent approver current for Accept. [Exact assessments](../../../../tmp/phase5-development/b33-on-b15/logs/b33-exact-195636a-run.log)

The timeout loser reached its real JDBC claim and made no receipt lookup. Authorized conflicts each attempted one duplicate claim then made one receipt lookup. For concealment, B stopped immediately after its duplicate claim. A returned the original Edit result, then the independently confirmed grant revocation became current before B looked up that result. B's 404 matched the same Create controller's ordinary submitted-type denial before and after the receipt existed. Those ordinary denials made zero claims and zero lookups. The concealed and ordinary replies had identical stable `Cache-Control`, `Content-Type` and `Transfer-Encoding` headers, no Location and normalized problem SHA-256 `585867463064e62d8cb3e70af1b5b9daae6f24a04dbb212f3bcb2b90f51421f8`. The comparison normalized Date and the linked per-attempt correlation UUID and instance. Restoring the result grant restored 409. At exact signed expiry the original key returned 409 `key_expired` with zero claim or lookup and unchanged durable records. [Concealment, reinstatement and expiry](../../../../tmp/phase5-development/b33-on-b15/logs/b33-exact-195636a-run.log)

### Cumulative durable state

Workflow counts are requests, bodies, facts, assignments, receipts, finalized receipts, scope witnesses, grant overrides, visibility records and terminal reasons. Proof counts are reservations, preparations, handoffs, client acknowledgments, legacy preparations, scope attempts and confirmations. The certified baseline includes one foreign-scope request, body and fact and 13 initial typed grant confirmations. The native trace records the before and during-cut vectors for every race. [Count transcript](../../../../tmp/phase5-development/b33-on-b15/logs/b33-exact-195636a-run.log)

| Boundary | Workflow | Proof | Creation ordinal |
| --- | --- | --- | ---: |
| Initial fixture | 1/1/1/0/0/0/13/13/0/0 | 0/0/0/0/0/13/13 | 0 |
| Two fresh keys | 3/3/3/2/2/2/13/13/0/0 | 2/2/2/0/0/13/13 | 2 |
| Settled 201 pair | 4/4/4/3/3/3/13/13/0/0 | 3/4/4/0/0/13/13 | 3 |
| Timeout while A held | 4/4/4/3/3/3/13/13/0/0 | 3/4/4/0/0/13/13 | 3 |
| A committed after timeout | 5/5/5/4/4/4/13/13/0/0 | 4/5/5/0/0/13/13 | 4 |
| Exact retry completed | 5/5/5/4/4/4/13/13/0/0 | 4/6/6/0/0/13/13 | 4 |
| Same-route 409 | 6/6/6/5/5/5/13/13/0/0 | 5/7/7/0/0/13/13 | 5 |
| Create / Edit 409 | 7/7/7/6/6/6/13/13/0/0 | 6/8/8/0/0/13/13 | 6 |
| Edit / Create 409 | 7/7/7/6/7/7/13/13/0/0 | 6/9/9/0/0/13/13 | 6 |
| Verify / Create 409 | 7/7/8/6/8/8/13/13/0/0 | 6/10/10/0/0/13/13 | 6 |
| Accept / Create 409 after assignment | 7/7/9/6/9/9/14/13/0/0 | 6/11/11/0/0/14/14 | 6 |
| Original concealed after confirmed revocation | 7/7/9/6/10/10/15/13/0/0 | 6/12/12/0/0/15/15 | 6 |
| Reinstated conflict and signed expiry | 7/7/9/6/10/10/16/13/0/0 | 6/12/12/0/0/16/16 | 6 |

### Logs and teardown

The exact run validated matching input and result receipts, sensitive-data exclusion across 12 pre-teardown native and worker logs, exit of both child JVMs, HTTP connection refusal and the closure of all of their workflow and proof connections. Both owned PostgreSQL postmasters stopped with `pg_ctl status=3`. At 08:37:52 UTC a separate OS check confirmed no owned PID files or process IDs remained and ports 55480–55482 were free. The lease returned to the coordinator. The 29-file evidence manifest has SHA-256 `c756a4421ec446631211b19fef3efb916a3ac914d152abbd32d99cb35a95b2f7`. The exact run log SHA-256 is `a0edb4d4c766f25d8b053149cf81d65d820015ffaed3405c53e5b9161647e981`. [Teardown check](../../../../tmp/phase5-development/b33-on-b15/logs/owned-teardown.log) · [SHA-256 manifest](../../../../tmp/phase5-development/b33-on-b15/evidence-195636a.sha256) · [B34 and B39 handoff](handoff.md)
