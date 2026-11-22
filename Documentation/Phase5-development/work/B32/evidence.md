# B32 executor evidence · Two-process PostgreSQL harness

**Candidate** `3d1ecd87ab00ee63926cc416b387a7a9a81273f3`, tree `afac33173b28b585d4166bf3eaf65a51c3cf4c21`, base `9469405a1e4b8bb1a345158f6b54d93ea59772ce`. The branch is `codex/b32-two-process-harness`. GPT-6.2 Sol xhigh (56) executed the candidate on 2026-11-22. The required independent review is GPT-6.2 Astra xhigh (62). [Assignment](assignment.md) · [B32 acceptance](../../../Phase4-development-planning/db-verification.md#b32--deterministic-real-engine-harness) · [Reproduction and reuse](../../../tmp/phase5-development/b32-two-process-harness/fixtures/postgresql/b32/README.md)

## Run and pins

The candidate adds nine files in `fixtures/postgresql/b32`. It uses a fixture-private reflective adapter to call the existing B08 listener with normal wiring and per-process Settings. A and B use their own nonpooling workflow and proof data sources. It uses the B04 dual-cluster provisioner with an inherited B08 ownership marker inside B32 scratch. The B41 candidate inspected at `04c1c53670e40fe5bd0345c6681f17eaf7c0c050` retains the visibility and signature of that listener method. This execution used the pinned B08 base.

Use `Run-B32.ps1 -Action Build` from the candidate with `-Workspace` set to the main repository and `-Scratch` set to a private workspace directory. The script runs Maven `-o -B -C -ntp -s <settings> -gs <settings> -Dmaven.repo.local=<private cache> clean verify`, then the same command with `-Psynthetic-http`, resolves the existing fixture POM offline and compiles the launcher with Java 25. The original observed artifacts came from scratch `b32-harness-runtime`. Executed control IDs were `b32-leak-01` with `-Action Leak`, `b32-stalled-01` with `-Action Stalled` and `b32-connected-02` with `-Action Run`. The latter two commands ran serially under the B32 reservation on 55480 and 55481 with local socket access. The standalone script Build reproduction used scratch `b32-harness-final-build`.

The host was Windows 11 x64 with Maven 3.9.16, Temurin 25.0.5.1+2, Spring 7.0.11, embedded core and annotations 11.0.29, PostgreSQL 18.7 and pgJDBC 42.7.14 with Checker Qual 3.55.1. The 30 packaged profile runtime JARs had verified hashes. The Tomcat JARs and POMs matched the B08 receipt, the two JDBC closure JARs matched G1-U, and all five PostgreSQL executable hashes used by this fixture matched the B04 tool manifest. The portable PostgreSQL archive SHA-256 matched `ead0bbc27fb678fa24dcf86b738981323042abd073c744103bf7f3aed23f9a33`. [G0](../g0-wave2-approval.md) · [G0-B and Tomcat inputs](../B08/http-runtime-inputs.md) · [G1-U](../g1-u-wave2-approval.md) · [Runtime manifest](../../../tmp/phase5-development/b32-harness-runtime/logs/runtime-sha256.csv) · [Native checksums](../../../tmp/phase5-development/b32-harness-runtime/logs/pg-native-verified-sha256.log)

## Red controls

- The real-engine preflight rejects an aliased A/A JVM and listener, the dedicated proof server presented as workflow, the fixture search schema presented as requests, a one-second trusted-clock deviation and mismatched B bearer configuration. The negative blocking control holds B in Java before issuing SQL. B's HTTP call is outstanding and the live workflow observer sees an empty `pg_blocking_pids` array. The same blocker oracle rejects the claimed wait at that point. [Connected evidence](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-02-run.log) · [Misbound actor evidence](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-stalled-01-stalled.log)
- The stalled run armed A's after-claim gate for 180 ms and omitted release. Backend 34820 entered at `19:33:13.712417400Z` and reported timeout at `19:33:13.894446100Z`. A returned sanitized HTTP 500 `internal_error`, both observers saw zero effects, and Java exited 1 after teardown. The PowerShell command validated the expected failure. [Stalled transcript](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-stalled-01-stalled.log)
- Placing a genuine compiled B32 gate class in a private control JAR made the packaged artifact scanner exit 1 with the expected control-leak assertion. [Leak transcript](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-leak-01-leak.log)

The two restricted attempts stopped during `pg_isready` and tore down their owned postmasters. In the first connected attempt the three gates and lock timeout worked, but starting B's HTTP admission while A was held consumed A's command budget and A returned 503 after its committed effect. The correction stages B at its pre-SQL gate before starting A, preserving the existing HTTP, statement and lock budgets. [Restricted diagnostic](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-trial-02-run.log) · [Connected timing diagnostic](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-01-run.log)

## Connected result

| Resource | Observed identifiers in `b32-connected-02` |
| --- | --- |
| Application A and B | JVMs 32892 and 35428, respectively, with one listener each at 127.0.0.1:57924 and 127.0.0.1:57928 |
| Restarted A | JVM 20400 with one listener at 127.0.0.1:27318, with B still live |
| Workflow | PostgreSQL postmaster 11064, system ID 7711081188743872160, 127.0.0.1:55480/workflow_db, `requests` schema OID 16397 owned by `b04_admin`, versions 1–3, observer backend 21728 |
| Separate proof | PostgreSQL postmaster 24336, system ID 7711081217110193588, 127.0.0.1:55481/proof_db, `request_authority` schema OID 16397 owned by `b04_admin`, versions 1–2, observer backend 4356 |
| A/B backend ownership | Startup workflow backends 14320 and 38596, startup proof backends 16208 and 9476. During contention, A held workflow backend 38164 and B used workflow backend 23680. A's pre-release proof backend was 18808. |

Independent verification matched both apps and restarted A on endpoint, server start, schema owner, migration history and packaged migrator hashes. The current role and live PostgreSQL application name identified each command backend. Both observer roles returned SQLSTATE 25006 for writes under read-only sessions and 42501 after intentionally relaxing the session setting. HTTP verified owner and alias 201, denied issuer 403 and missing bearer 401 with matching trusted instant `2026-01-02T03:04:05.123456789Z` and key expiry exactly 24 hours later. [Connected transcript](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-02-run.log)

| Cut (UTC) | Arrival | Release | Authoritative state |
| --- | --- | --- | --- |
| B before key-claim SQL | `19:33:41.464940100` | `19:33:42.533028500` | B had no PostgreSQL blocker while gated |
| A after successful key claim | `19:33:42.463034200` | `19:33:42.793029300` | Zero visible workflow rows, reservations and preparations |
| A before real commit | `19:33:43.200032300` | `19:33:43.215035500` | Same command backend as claim, zero visible workflow rows, one durable reservation and zero preparations |
| A before release preparation | `19:33:43.604033800` | `19:33:43.620031200` | One of each workflow row, one reservation and zero preparations while HTTP was pending |

At `19:33:42.584030600Z` the observer saw `pg_blocking_pids(23680)={38164}`. B returned 503 `outcome_unknown` with no Location or result ref while A remained gated and durable counts remained zero. A returned 201 and B's same-key retry returned the exact 201 envelope. After the controlled A restart, the same request returned the same status, Location, media type, cache policy and UTF-8 body. The workflow observer matched one request, body, fact, assignment and finalized receipt to the authorized principal, trusted timestamp and exact UTF-8 intent. The proof observer matched one reservation and three distinct preparations to that reference and key digest. Location SHA-256 was `1d883c4421ba9bbd2562e0caa16e6437c44e3c75017ab775a653e4689108b376` and body SHA-256 was `7f528db7cb6bc3d1fd56fe90e32db89f0d375d6a2fe0132f34bd89732569039c`. [Connected transcript](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-02-run.log)

All three application JVMs exited and refused new connections on their owned endpoints. Both PostgreSQL postmasters remained live during the application restart, then exited under B04's owned teardown. `pg_ctl status` returned 3 and both fixed ports were free. Worker diagnostics passed exact sensitive-value scans. Raw artifacts remain in private B32 scratch. [Connected transcript](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-02-run.log)

## Outgoing evidence

The documented `Build` command passed both strict-offline reactor builds. Each ran 317 tests in nine suites across 11 projects with zero failures, errors or skips. The default artifact scan covered 26 JARs and found neither the listener nor fixture classes. The profile scan covered all 30 runtime JARs, found the B08 listener and excluded fixture controls. A fresh leak control against these newly built artifacts reproduced the expected rejection.

| Artifact | SHA-256 |
| --- | --- |
| [Connected run](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-02-run.log) | `8aa33a54cdc8c36e4456054a6e614bc7c84f403ad98ab9f31b0ec21560b710fe` |
| [Stalled run](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-stalled-01-stalled.log) | `5756f967cf56aca7073ee6a0879200a2a4fc0883fdd8bff4dc88b6ac5fcd259c` |
| [Leak red](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-leak-01-leak.log) | `5e1036e1a867b08e26830222190f046af12de76b761ac5ae44bdd15ff371807d` |
| [First connected diagnostic](../../../tmp/phase5-development/b32-harness-runtime/logs/b32-connected-01-run.log) | `7d4cfe203f4a68f2b09c429773b59d362aeb84deff35e7fc6fb89376abe85910` |
| [Resolved runtime manifest](../../../tmp/phase5-development/b32-harness-runtime/logs/runtime-sha256.csv) | `34e0e56172476f629fbb67e884bd5601d22cfca15be5f878400aff73a18a2ec5` |
| [Default offline verification](../../../tmp/phase5-development/b32-harness-final-build/logs/default-verify.log) | `612e0b50849a3d5ea8fbc51ccdfe704c96f7e7ffdba43f978720122ae354ebef` |
| [Profile offline verification](../../../tmp/phase5-development/b32-harness-final-build/logs/profile-verify.log) | `308ae0aad2327af6c3aae20650ffbba317bcfdfd45fc9946d2c39e722bcaeecc` |
| [Default package scan](../../../tmp/phase5-development/b32-harness-final-build/logs/default-artifact-scan.log) | `564c020fb31909c8735991c1b2a000d0d5a2614d00cfa02bbc6ca59f8fe840a0` |
| [Profile package scan](../../../tmp/phase5-development/b32-harness-final-build/logs/profile-artifact-scan.log) | `816109892a4c747c1810f0e3ba91d3a3285358a3bd884c2f6f0cbd5659a0cc98` |

B33–B40 receive the two-process launcher, one-shot gates with bounded deadlines, trusted clock control, endpoint identities and independent proof/primary observers. The fixture expects one gate-eligible command per process during a cut. It provides a reusable starting point for the later scenario-specific and restore oracles on the qualified fixture. [Reuse contract](../../../tmp/phase5-development/b32-two-process-harness/fixtures/postgresql/b32/README.md#reuse-for-b33b40) · [LLD scenarios](../../../Phase3-LLD/postgres-evidence.md)

At evidence freeze, the integration branch had advanced through B09 at `c2634d1d4741e0d90378609391a5de5d6640e34b`. Inspection confirms B09's default `migrate()` methods still install workflow V001–V003 and proof V001–V002. Its V004/V003 are selected by the separate scope migration entry points. The coordinator will rebase the fixture and arrange independent reproduction on the resulting exact revision.
