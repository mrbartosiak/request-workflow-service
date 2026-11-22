# B32 correction evidence

**Candidate** `72602d7ed69af83a044e0dc6beea96a2cd59df1e`, tree `5d20712f883d54cc59e44a23a3aca63ef4a9265b`, parent `955b529251c2b7e0bc2b6ae108c0d63dffdc796a`. Branch `codex/b32-harness-on-b09`. GPT-6.2 Sol xhigh (56) executed the correction. The required exact independent review uses GPT-6.2 Astra xhigh (62). [Assignment](assignment.md) · [Independent findings on parent](review-955b529.md) · [Frozen initial evidence](evidence.md)

## Corrections

The same-key contention request keeps the after-claim cut and both authoritative zero-effect reads. Once B returns its real 503, A can finish that request without waiting for further cuts. B and restarted A then replay A's exact 201. Two subsequent Creates, each with its own signed key, check precommit and prerelease separately under the existing application, SQL and socket deadlines. The JDBC fixture verifies a successful key claim and receipt finalization on the same command lease before arming the precommit observation. The prerelease observation comes from the real proof backend. [Fixture and commands](../../../tmp/phase5-development/b32-two-process-harness/fixtures/postgresql/b32/README.md)

Each worker now owns a two-slot workflow pool and a separate two-slot proof pool using pgJDBC's `PGConnectionPoolDataSource`. The fixture bounds a wait for a full pool to 200 ms. The startup probe holds two leases on distinct physical backends, obtains SQLSTATE `HYT00` for a third and reuses the released backend after resetting transaction state. The external observer checks the A and B pool sets are disjoint, and the red passes A twice and requires rejection. The initial A pool backends disappear after A stops, while B's original pools and both PostgreSQL postmasters stay live during restart. All changes are in the fixture directory. [Pool](../../../tmp/phase5-development/b32-two-process-harness/fixtures/postgresql/b32/B32Pool.java) · [Worker](../../../tmp/phase5-development/b32-two-process-harness/fixtures/postgresql/b32/B32Worker.java)

The PowerShell wrapper requires Java exit 3 and the dedicated clean-teardown completion marker for a genuine stall. Java can emit that marker only after both app resources close and the B04 fixture returns from both owned cluster shutdowns. The no-socket red combines the old timeout assertion with a suppressed `IOException`, exits 1 and is rejected by the same classifier. The injected release-classpath control still rejects a genuine compiled B32 gate class. [Compound failure transcript](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-light-red-stall-cleanup-red.log) · [Leak transcript](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-light-leak-leak.log)

## Repeated connected checks

Real checks compiled the corrective fixture from the assigned worktree against the independently verified 30-JAR B09-backed runtime used by the previous review. The packaged application, JDK, PostgreSQL 18.7, pgJDBC 42.7.14, Checker Qual 3.55.1, Tomcat 11.0.29 and Spring 7.0.11 pins are inherited. The B09 application default migrators still select workflow V001–V003 and proof V001–V002. Both runs repeated the independent server, schema, actor/clock, read-only observer, fake blocking and pool separation checks before assertions about client results. [Runtime manifest](../../../tmp/phase5-development/b32-astra-955b529/runtime/logs/runtime-sha256.csv) · [Qualified inputs and prior checksums](review-955b529.md#exact-inputs-and-artifact-checks)

| Evidence | `b32-fix-green-01` | `b32-fix-green-02` |
| --- | --- | --- |
| A, B and restarted A JVMs and loopback ports | 35584/28421, 20260/28430, 26388/28443 | 1976/29866, 34156/29873, 34204/29885 |
| Workflow and proof postmasters | 28468, 27508 | 36232, 11876 |
| Independent workflow and proof observer backends | 11724, 25060 | 36500, 11588 |
| Actual workflow blocking | `pg_blocking_pids(16340)={36088}` | `pg_blocking_pids(30788)={17608}` |
| B timeout and A original | 503 `outcome_unknown`; 201 in 812 ms | 503 `outcome_unknown`; 201 in 649 ms |
| B retry and restarted A | Exact original 201, Location, media type, cache and body | Exact original 201, Location, media type, cache and body |
| Isolated precommit backend and result | 22484; 201 in 612 ms | 23728; 201 in 197 ms |
| Isolated prerelease backend and result | 32816; 201 in 838 ms | 19520; 201 in 205 ms |
| A and B workflow pools | `[34424,36088]` and `[25776,16340]` | `[34280,17608]` and `[36312,30788]` |
| A and B proof pools | `[31532,28728]` and `[18528,32816]` | `[2548,25048]` and `[14940,19520]` |

In both runs the claim and post-timeout snapshots showed zero workflow effects, proof reservations and release preparations. A's 201 committed one request, body, fact, assignment and finalized receipt, plus one reservation and preparation. B's original-key retry and A's restart replay preserved that single workflow effect and reservation while bringing the separate proof preparation count to three. The isolated precommit read then showed one of each workflow row, two reservations and three preparations. The next isolated prerelease read showed three of each workflow row, three reservations and four preparations. Final state was three distinct workflow effects, three reservations and five preparations. The independent readers matched each result to its original key evidence, actor, exact UTF-8 intent and trusted instant. [First full transcript](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-green-01-run.log) · [Second full transcript](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-green-02-run.log)

| Repeat cut in UTC | Arrival | Release |
| --- | --- | --- |
| B before claim | `20:14:51.609478200` | `20:14:51.776711500` |
| A after claim | `20:14:51.721684800` | `20:14:52.045712100` |
| Independent precommit | `20:15:01.062522200` | `20:15:01.079644500` |
| Independent prerelease | `20:15:01.328546300` | `20:15:01.364560000` |

In `b32-fix-stall-01`, backend 38116 arrived at `20:12:25.275588900Z` and reported the gated timeout at `20:12:25.450316800Z`. The app returned sanitized HTTP 500 and both readers saw zero effects. Both application JVMs exited and refused connections, both owned PostgreSQL nodes stopped, and the cleaned-up marker followed. All three serial runs finished owned process teardown. A separate Windows socket inventory confirmed both fixed ports free at `20:15:40.0515778Z`. [Genuine stall](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-stall-01-stalled.log) · [Final socket inventory](../../../tmp/phase5-development/b32-fix-light/logs/ports-after.log)

## Reproduction and digests

From the exact checkout, set the parameters below and use the approved local PostgreSQL reservation. `Build`, `StallCleanupRed` and `Leak` run without PostgreSQL sockets. Use an approved loopback-capable shell for the three native runs.

~~~powershell
$workspace = 'C:/Users/Asia/Documents/ChatGPT/SocieteGeneraleCodingInterview'
$b32 = @{ Workspace = $workspace; Scratch = "$workspace/tmp/phase5-development/b32-fix-review" }
$fixture = './fixtures/postgresql/b32/Run-B32.ps1'
& $fixture @b32 -Action Build
& $fixture @b32 -Action StallCleanupRed -RunId b32-review-stall-red
& $fixture @b32 -Action Leak -RunId b32-review-leak
& $fixture @b32 -Action Stalled -RunId b32-review-stall
& $fixture @b32 -Action Run -RunId b32-review-green-01
& $fixture @b32 -Action Run -RunId b32-review-green-02
~~~

The published `Build` command completed strict-offline default and `synthetic-http` verification. Each reactor passed 317 tests in nine suites across 11 projects with zero failures, errors or skips. The default scan checked 26 packaged JARs. The profile scan checked 30 packaged JARs and confirmed the listening entry point. Both scans excluded fixture classes. The compound-stall classifier and injected fixture control repeated successfully against this build. All 31 compiled fixture classfiles, including the reused B04 launcher, matched byte for byte between the real-run launcher and the fresh exact checkout build.

| Evidence | SHA-256 |
| --- | --- |
| [First full run](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-green-01-run.log) | `30800c78d432d8f0d8826eb854493f326dcf4c0ef818fdaeecc8a6d397129a13` |
| [Second full run](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-green-02-run.log) | `7d0d002f9695528dbec85892f1e316553e24a65339c0812191c9b3dfac3ff315` |
| [Genuine stall](../../../tmp/phase5-development/b32-fix-light/logs/b32-fix-stall-01-stalled.log) | `b0ee9080784f21165e0ab4989cc7eb51f72859e23d05ea65dce936302a7efb29` |
| [Final socket inventory](../../../tmp/phase5-development/b32-fix-light/logs/ports-after.log) | `9a0686a3fe10f8a2fd285c6bc437808c0f7ce11a0439de860e92fce14c34e8ea` |
| [Runtime used for real checks](../../../tmp/phase5-development/b32-astra-955b529/runtime/logs/runtime-sha256.csv) | `a9d6cef38b53ec4728f15395a26dd53358d3d40d93e03122f50eaec6d5f19df1` |
| [Default offline build](../../../tmp/phase5-development/b32-fix-final/logs/default-verify.log) | `d67f154f04b0e11fe6d712037e01ad68d84b12d3bb5f3b1b4e2b2468cc52cfc7` |
| [Profile offline build](../../../tmp/phase5-development/b32-fix-final/logs/profile-verify.log) | `8b75592b6186c0885cdc8556157f5441ace9805024d130ce4eb47508cf2719c9` |
| [Default artifact scan](../../../tmp/phase5-development/b32-fix-final/logs/default-artifact-scan.log) | `564c020fb31909c8735991c1b2a000d0d5a2614d00cfa02bbc6ca59f8fe840a0` |
| [Profile artifact scan](../../../tmp/phase5-development/b32-fix-final/logs/profile-artifact-scan.log) | `816109892a4c747c1810f0e3ba91d3a3285358a3bd884c2f6f0cbd5659a0cc98` |
| [Compound stall and cleanup failure red](../../../tmp/phase5-development/b32-fix-final/logs/b32-fix-final-red-stall-cleanup-red.log) | `5208c36ec90b942b870fa5d50cad4e85ff53793883554a5f7aca3b985e99a4eb` |
| [Fixture artifact leak red](../../../tmp/phase5-development/b32-fix-final/logs/b32-fix-final-leak-leak.log) | `5e1036e1a867b08e26830222190f046af12de76b761ac5ae44bdd15ff371807d` |
| [Byte parity of the real-run and exact build fixture classes](../../../tmp/phase5-development/b32-fix-final/logs/launcher-class-parity.log) | `500827fb09b2ba910475f7a2d14f53189b7dc5ab9b7763783a69c0a705eb50af` |
| [Fresh build runtime digest inventory](../../../tmp/phase5-development/b32-fix-final/logs/runtime-sha256.csv) | `2d6f5c80100f364c4ecc4b8e915e95eee49fbf3761b3dd2ec98fbef1c173d6ce` |

B33–B40 can reuse scoped one-shot gates, normal HTTP startup and restart, four bounded two-slot pools, trusted clock control, and independent workflow and proof observations. Preserve the one gate-eligible request per instance assumption when scheduling cuts. Later tickets supply their own scenario-specific effects and restore checks. [Reuse contract](../../../tmp/phase5-development/b32-two-process-harness/fixtures/postgresql/b32/README.md#reuse-for-b33b40)
