# B20 protected ordered history evidence

Candidate `177e65322ea3f07038a05e5f9fe43b541e25d8be`, tree `65767133e6965412ae53e908f6d41ffef8e69eed`, branch `codex/b20-ordered-history`. The candidate directly extends independently approved B18 `cfa2dc457269ab35f66f8a53abac88fd0c507394`, with B19 at `b2be441c501fa86d4229a103df6f7184ca1525cb` below it. Both Git dates are 2026-11-22 22:40 Warsaw. The executor matched all 30 approved plan files in Git-normalized form against PH4-PLAN-r1, manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. [Assignment](assignment.md) · [Plan verification](../../../../tmp/phase5-development/b20-fixture/logs/ph4-plan-r1-verification.log) · [Handoff](handoff.md)

## Focused baseline

The independently approved B19 source with only the added history MVC expectation failed the port-free smoke test. The registered profile returned 404 for the history route where authentication requires 401 and a single Bearer challenge before validation. The exact candidate passed the same assertion in both reactor build modes. [Focused patch](../../../../tmp/phase5-development/b20-fixture/focused-base-red.patch) · [Baseline log](../../../../tmp/phase5-development/b20-fixture/logs/focused-base-red.log)

## Exact builds and runtime packages

Strict offline Maven `-o -B -C -ntp clean verify` with Maven 3.9.16 and Temurin 25.0.5.1+2 passed 336 tests in 14 suites across 11 reactor modules in both default and `-Psynthetic-http` modes, with zero failures, errors or skips. The bootstrap runtime graphs contained 26 and 28 JARs respectively. The profile fixture classpath added two driver dependencies. The scans found the history controller and service, the effective profile, and V012. The listener was present only in the synthetic profile. All runtime archives passed the private-fixture and test-class scan. [Default build](../../../../tmp/phase5-development/b20-fixture/logs/default-exact-verify.log) · [Profile build](../../../../tmp/phase5-development/b20-fixture/logs/profile-exact-verify.log) · [Default packages](../../../../tmp/phase5-development/b20-fixture/logs/default-exact-runtime-sha256.csv) · [Profile packages](../../../../tmp/phase5-development/b20-fixture/logs/profile-exact-runtime-sha256.csv) · [Native runtime packages](../../../../tmp/phase5-development/b20-fixture/logs/runtime-sha256.csv)

## History oracle

The registered MVC profile exercised the history and command routes on PostgreSQL 18.7 with pgJDBC 42.7.14. Success responses matched exact UTF-8 bytes and an exact header set. Error comparisons replaced only generated correlation values. The fixture compared authoritative request, receipt, fact and reason counts around the checks. [Fixture source](../../../../tmp/phase5-development/b20-ordered-history/fixtures/postgresql/B20Postgresql.java) · [Exact run](../../../../tmp/phase5-development/b20-fixture/logs/b20-b20-177e653-01.log)

| Case | Verified response and durable effect |
| --- | --- |
| Authentication and typed grants | Unauthenticated input returned 401 and a single Bearer challenge. Invalid authorized inputs returned 400 before guarded storage. Missing `ROW_READ`, missing `HISTORY_READ` and an absent target shared ordinary 404 with zero fact reads. Both grants admitted a live request independently of detail field grants. |
| Delete | `create → delete` returned two ordered facts only after current scope proof and the separate deleted metadata grant. Terminal commit added one receipt, one fact and one protected reason. Exact replay matched. The same response survived a fixture-owned physical body and reason purge. |
| Reject from VERIFIED | A real Edit advanced revision and added one receipt with unchanged history bytes. Exact Edit replay left the counts unchanged. `create → verify → reject` returned three facts and ended at revision 4. Reject committed one receipt, one fact and one protected reason, with exact replay. |
| Reject from ACCEPTED | `create → verify → accept → reject` returned four facts. Reject committed one receipt, one fact and one protected reason. Exact replay preserved both history and receipt. |
| Publish | `create → verify → accept → publish` returned four facts after both original proofs were confirmed. Publish committed one receipt and one fact. Scope and publication confirmation independently deferred the visible target, and a concealed target matched ordinary 404 after scope confirmation. History omitted the publication ID even after its field grant was enabled. |
| Faults and integrity | An invalid Delete and an injected terminal reason insert failure left the exact earlier history and authoritative counts. Pending rollback proof yielded safe 503. The resolver's original abort reopened the read. An extra locally valid fact whose tail disagreed with the request also yielded safe 503 until fixture maintenance removed it. |
| Ordered release | PostgreSQL showed blocked `FOR UPDATE` when history reached guarded handoff first, and blocked `FOR SHARE` when the grant revocation writer held the guard first. Earlier handoff retained its exact legitimate bytes. Pending revocation deferred. Confirmed revocation returned the same response as an absent reference and the waiting read selected no protected facts before proof. |
| Restart | A physical restart of the proof PostgreSQL server plus a fresh MVC application context preserved all four exact histories and mutation receipts. Counts before and after were 10 requests, 19 receipts, 19 facts and three reason rows. A later physical workflow restart changed the certified server incarnation. Present and absent references both returned identical safe 503 with those counts preserved. |

The history success shape is `{ref,changes}`. Every change has a numeric `sequence`, a lower-case REST `action`, `from`, and `to`. Creation uses explicit JSON null for `from`. The query selects no actor or recorded-time columns. The exact response allowlist excludes protected payloads, actor, reason, timestamp, and action observations.

## Setup and corrected oracle

The sandboxed first launch started a workflow postmaster but loopback `pg_isready` reported no response. Fixture cleanup stopped that postmaster. The escalated retry reached all terminal, fault and revocation checks. Its restart assertion expected exact receipt replay after the certified workflow postmaster changed, which B08 deliberately fences. The corrected fixture first restarts the proof database and MVC for the exact replay comparison, then verifies workflow restart fencing and unchanged records. That draft passed before freezing the candidate. [Sandbox probe](../../../../tmp/phase5-development/b20-fixture/logs/sandbox-loopback-probe.log) · [First eligible native run](../../../../tmp/phase5-development/b20-fixture/logs/b20-green-02.log) · [Corrected draft](../../../../tmp/phase5-development/b20-fixture/logs/b20-green-03.log)

## Packaged native regression matrix

| Fixture | PASS records |
| --- | ---: |
| [B20 protected ordered history](../../../../tmp/phase5-development/b20-fixture/logs/b20-b20-177e653-01.log) | 27 |
| [B19 masked detail](../../../../tmp/phase5-development/b20-fixture/logs/b19-b20-177e653-01.log) | 21 |
| [B12 guarded release and physical rewind](../../../../tmp/phase5-development/b20-fixture/logs/b12-b20-177e653-01.log) | 26 |
| [B15 terminal actions](../../../../tmp/phase5-development/b20-fixture/logs/b15-b20-177e653-01.log) | 20 |
| [B17 publication and original confirmation](../../../../tmp/phase5-development/b20-fixture/logs/b17-b20-177e653-01.log) | 14 |
| [B18 external mapping](../../../../tmp/phase5-development/b20-fixture/logs/b18-b20-177e653-01.log) | 18 |

Every owned postmaster stopped. The teardown check found zero owned PID files and zero listeners on 55480–55482. The executor returned the lease to the coordinator for independent review. [Native run summary](../../../../tmp/phase5-development/b20-fixture/logs/exact-native-matrix.log) · [Teardown](../../../../tmp/phase5-development/b20-fixture/logs/owned-teardown.log)

## Exact review artifacts

The SHA-256 manifest covers 59 artifacts. The audit checked 28 console and fixture logs for signed operation keys, opaque observations, bearer values, private keys and credential-bearing JDBC URLs, with zero matches. The exact build and native runners reproduce the candidate against the qualified offline toolchain and assigned local fixture lease. [Archive](../../../../tmp/phase5-development/b20-fixture/candidate-177e653.tar) · [Full-index patch](../../../../tmp/phase5-development/b20-fixture/candidate-177e653.patch) · [Digest manifest](../../../../tmp/phase5-development/b20-fixture/evidence-177e653.sha256) · [Log audit](../../../../tmp/phase5-development/b20-fixture/logs/sanitized-log-audit.log) · [Exact package verifier](../../../../tmp/phase5-development/b20-fixture/Verify-B20-exact.ps1) · [Exact native runner](../../../../tmp/phase5-development/b20-fixture/Run-B20-exact-native.ps1)
