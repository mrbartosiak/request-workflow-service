# B13 action context and observation evidence

Candidate `337a9d977cf32d8370287370e3d532cdb885e934`, tree `0ea4f8d3862ba26eed863a323150ccfac383937d`, branch `codex/b13-action-context`. Parent B12 is `9b1f4b25c2869ff6ace267275db69190fcc47415`. Author and committer timestamps are both 2026-11-22 22:33 Warsaw. The isolated worktree is clean. The approved LF form of the PH4-PLAN-r1 manifest matched `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6` and all 30 payloads matched. [Assignment](assignment.md) · [Plan verification](../../../../tmp/phase5-development/b13-fixture/logs/ph4-plan-r1-verification.log) · [Command handoff](handoff.md)

## Focused baseline

The new MVC smoke test uses the B12 effective configuration and unreachable data sources. The request uses the adopted path and invalid unauthenticated query. On the unmodified B12 source with only that test added, Spring reported no mapping and returned 404. The expected authentication response was 401 with a Bearer challenge. One test failed. The exact candidate passes that test and the native fixture checks validation with an authenticated bearer before any target storage read. [Baseline test patch](../../../../tmp/phase5-development/b13-fixture/focused-base-red.patch) · [Baseline run](../../../../tmp/phase5-development/b13-fixture/logs/focused-base-red.log)

## Exact build and runtime evidence

Both strict offline `-o -B -C -ntp clean verify` builds passed 327 tests in 12 suites and all 11 reactor projects, with zero failures, errors or skips. Both used the adopted global and user Maven settings and copied qualified cache. The synthetic HTTP profile had 30 runtime JARs, one intended launcher and zero private fixture classes. Inputs were Temurin 25.0.5.1+2, Maven 3.9.16, Spring 7.0.11, pgJDBC 42.7.14 and PostgreSQL 18.7 on Windows. [Default build](../../../../tmp/phase5-development/b13-fixture/logs/default-verify.log) · [Synthetic profile build](../../../../tmp/phase5-development/b13-fixture/logs/profile-verify.log) · [Packaged isolation](../../../../tmp/phase5-development/b13-fixture/logs/exact-packaged-isolation.log)

| Exact B13 check | Observed outcome |
| --- | --- |
| Wire and grant precedence | The effective Spring TestDispatcherServlet returned exact JSON, UTF-8 and no-store for each approved action. Edit, Verify and Accept returned the exact body. Reject, Delete and Publish returned the exact name. Neither shape contained a descriptive revision or unapproved fact. Invalid action, duplicate query and unexpected body failed before storage. A max-size body with JSON-escaped controls round-tripped. |
| Scope and state | Missing row, context, execute and body or name grants withheld the observation. Missing permissions masked illegal-state cues. Correct permissions on a visible illegal state yielded 409. Hidden, absent, foreign and deleted targets returned ordinary 404 without material reads. Verify and Publish had independent grants. Alternate login preserved actor binding and could not bypass distinct-person Accept. A separately assigned approver succeeded. A configuration with an uncertified writer deferred before target or token evaluation. |
| Authenticated observations | The new-intent verifier accepted the exact current tuple under the exclusive guard. Changed ref, action, actor or assignment, fact bytes at the same stored revision, changed revision, unknown key ID, tampering and expiry all failed. The codec tests independently changed actor, scope, certified policy, writer, guard, type, assignment, visibility, revision and material text. They exercised rotation, key separation, malformed envelopes, display revisions and the last valid nanosecond. Concealed and denied targets masked token syntax. |
| Shared handoff | The fixture observed a real PostgreSQL `FOR UPDATE` wait behind a valid read at the buffer handoff and a `FOR SHARE` wait behind revocation. The winning handoff produced its authorized context. A pending earlier revocation deferred before a target read and the confirmed revocation denied the next context. Trusted time reaching expiry at the offer withheld the bytes. Request, body, history, receipt and reservation counts stayed fixed across context and verification. |

The B13 endpoint was exercised through configured Spring MVC and real PostgreSQL. The independent socket run below covers the previous standalone Create profile. The epoch regression combines unit evidence for stale authentic observations on changed certified coordinates with native deferral for an uncertified writer. Production writer promotion, corporate grants and type-specific fact mapping remain assigned separately. [B13 native run](../../../../tmp/phase5-development/b13-fixture/logs/b13-exact-337a9d9.log) · [Successor interfaces](handoff.md)

Two managed-sandbox preflights could start the workflow postmaster but could not connect with `pg_isready` on 55480. Both stopped their own postmaster. The approved execution outside that network sandbox connected over loopback and passed. The recorded native runs used only owned synthetic clusters and credentials.

## Oracle and reproduction

B13 covers the context and access slices of E1 and E4. It provides the stale-observation predicate for P6 and the context-side guard cut for P8. The writer-coordinate test covers the B13 binding needed by P9. Command-effect races and production promotion remain successor evidence. Before and after the read/verifier cuts, the fixture queried the workflow request, body, transition and receipt tables and the independent creation-reservation table. All five counts matched. [Functional evidence](../../../Phase3-LLD/verification.md) · [PostgreSQL evidence](../../../Phase3-LLD/postgres-evidence.md)

From the exact candidate worktree, use the qualified cache and build the default and synthetic-profile packages offline. Run the source-controlled fixture per its README. The private runner used `Compile` for the exact classpath and `B13` with run ID `b13-exact-337a9d9`. The same runner executes `B12`, `B08Mvc`, `B08`, `B10`, `B11` and `B32` with distinct owned run IDs. Keep a single fixture on the reserved PostgreSQL ports at a time. [Fixture instructions](../../../../tmp/phase5-development/b13-action-context/fixtures/postgresql/README.md#b13-action-context-and-observation-verification) · [Private runner](../../../../tmp/phase5-development/b13-fixture/Run-B13-local.ps1) · [Wave 2 build reuse](../g0-b-wave2-approval.md) · [Wave 2 fixture reuse](../g1-u-wave2-approval.md) · [Standing authorization](../g0-remaining-plan-approval.md)

## Predecessor regression runs

The exact candidate passed B12's guarded current and earlier-generation replay tests and its physical rewind. It passed B08's MVC Create, B10's ordered access changes, B11's resolver and B32's two-process receipt and release races. The first standalone B08 run returned 503 `outcome_unknown` on the restarted process's replay after the original Create had succeeded. The structured ingress diagnostic measured 2.64 seconds and both application JVMs and database postmasters stopped. The unchanged repeat passed, with the original response body and Location reproduced, one business effect and two permitted preparations. Both listening application JVMs then exited and refused connections. [First standalone run](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-wire-01.log) · [Repeat](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-wire-02.log)

| Exact run | PASS records |
| --- | --- |
| [B13 action context and verifier](../../../../tmp/phase5-development/b13-fixture/logs/b13-exact-337a9d9.log) | 17 |
| [B12 guarded replay and physical rewind](../../../../tmp/phase5-development/b13-fixture/logs/b12-b13-regression-01.log) | 26 |
| [B08 MVC Create](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-mvc-01.log) | 20 |
| [B08 listening HTTP and restart repeat](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-wire-02.log) | 17 |
| [B10 effective access](../../../../tmp/phase5-development/b13-fixture/logs/b10-b13-regression-01.log) | 32 |
| [B11 original-lineage proof](../../../../tmp/phase5-development/b13-fixture/logs/b11-b13-regression-01.log) | 22 |
| [B32 two-process harness](../../../../tmp/phase5-development/b13-fixture/logs/b32-b13-regression-01-run.log) | 41 |

The last owned-process check found zero initialized test clusters with a remaining postmaster PID file and zero listeners on ports 55480–55482 at 08:08:29 Warsaw time. The executor released the ports to the coordinator. [Teardown](../../../../tmp/phase5-development/b13-fixture/logs/owned-teardown.log)

## Artifact digests

| Evidence | SHA-256 |
| --- | --- |
| [Exact candidate archive](../../../../tmp/phase5-development/b13-fixture/candidate-337a9d9.tar) | `b713933fa4cd7ad07ae3a7100da7fbb08bd456b7ae140c19224db5bf3fa9d881` |
| [Full-index diff from B12](../../../../tmp/phase5-development/b13-fixture/candidate-337a9d9.patch) | `1b8e80fdc9052daf44c3c025547e51c4a42a91dd284c49f2fa6aa43c486773ea` |
| [Focused red test patch](../../../../tmp/phase5-development/b13-fixture/focused-base-red.patch) | `3a708eaca3390b91723e7f121d7af542b7eba438fe32c57589e7fe591b09a526` |
| [Focused red log](../../../../tmp/phase5-development/b13-fixture/logs/focused-base-red.log) | `064b8f40f4254d01461cf4fcf8981c2aa75c49cd07aa1d4415a897436d28db4b` |
| [PH4-PLAN-r1 verification](../../../../tmp/phase5-development/b13-fixture/logs/ph4-plan-r1-verification.log) | `13cbb4d6a7499bc27b8f029e7b64fdc9fc7678029d511bbaf0c17b2f2d5a1bda` |
| [Strict offline default build](../../../../tmp/phase5-development/b13-fixture/logs/default-verify.log) | `148183bb4efa98844efbb7f3d27b8e93fe079c198ee15012364ad5f0a1ea8868` |
| [Strict offline synthetic-profile build](../../../../tmp/phase5-development/b13-fixture/logs/profile-verify.log) | `d1e37a8850db6632875f19d85e70e32da62bdde68ddae0ad36b3a3141959d3fe` |
| [Packaged runtime fixture isolation](../../../../tmp/phase5-development/b13-fixture/logs/exact-packaged-isolation.log) | `94799c80b70df4c27260e9ba36a5e82a642638ab5e3eae1d92603eb2df039de4` |
| [First standalone wire run](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-wire-01.log) | `447415c1ae1e2b3838682e2b183674e23de5490b5d3c6dbeaba74dfb66328013` |
| [First standalone replay diagnostic](../../../../tmp/phase5-development/b13-fixture/runs/b08-b13-wire-01/http-replay.log) | `fa9b3b0fbc4374af143fc86256b8926031a2b16aef98bf52c8400433ea8b8131` |
| [Owned process and port teardown](../../../../tmp/phase5-development/b13-fixture/logs/owned-teardown.log) | `25554968d138dcec2d0a7cbb93096a63edc4cf368d2593fc3571bfbe50c63f79` |
| [B13 action context and verifier](../../../../tmp/phase5-development/b13-fixture/logs/b13-exact-337a9d9.log) | `36fa53ddd44cce5dea6bcec8ad072ddf860f5e93a8ce9367991f2c723b0f33c0` |
| [B12 guarded replay and physical rewind](../../../../tmp/phase5-development/b13-fixture/logs/b12-b13-regression-01.log) | `4befadd99ef34bf30c27d300733131a9e56118f2fb6e63ec2ff094193f64b0b2` |
| [B08 MVC Create](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-mvc-01.log) | `eb251dd9854c313820ab6f26a65ac0f744c0174218e6495d8680729ef11458cb` |
| [B08 listening HTTP and restart repeat](../../../../tmp/phase5-development/b13-fixture/logs/b08-b13-wire-02.log) | `7d1e20ecc95194e10fdae50d3ef7cbbdaa2c8230e66572100f7866788e183a86` |
| [B10 effective access](../../../../tmp/phase5-development/b13-fixture/logs/b10-b13-regression-01.log) | `3f7023271f553aa5a0f07151f73d3d20e1cb850fdf4624c0975213a9522f2b50` |
| [B11 original-lineage proof](../../../../tmp/phase5-development/b13-fixture/logs/b11-b13-regression-01.log) | `261cfa7f5e6cef2635cb2b428c0136f2bbdf8aed53948c5f6114b1322840051c` |
| [B32 two-process harness](../../../../tmp/phase5-development/b13-fixture/logs/b32-b13-regression-01-run.log) | `96f9d50e38adc73bb24c70b1972138b015679bd3199b35fc769cdff33e935ef5` |
| [Evidence log hygiene](../../../../tmp/phase5-development/b13-fixture/logs/sanitized-log-audit.log) | `91360b36e5c08c9bb62b8d2130e82e1dab7f92054e415fa11a0801ada58d7064` |
