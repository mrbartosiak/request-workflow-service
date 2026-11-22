# B19 masked detail and current revision evidence

Candidate `b2be441c501fa86d4229a103df6f7184ca1525cb`, tree `a308c76d563032afa752fa290426b219fa954142`, branch `codex/b19-masked-detail`. The clean candidate directly extends independently approved B17 `cab8a9c0e89dc97d2600b772e5ca6725b3d43cce`. Both Git dates are 2026-11-22 22:38 Warsaw. All 30 adopted plan files matched PH4-PLAN-r1, manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. [Assignment](assignment.md) · [Plan verification](../../../../tmp/phase5-development/b19-fixture/logs/ph4-plan-r1-verification.log) · [Successor handoff](handoff.md)

## Focused baseline

The port-free test on B17 added only the normal unauthenticated detail route expectation. It got 404 where the boundary required 401 and a Bearer challenge. All six existing action routes passed that same test. The candidate passed the focused expectation in both exact reactor runs. [Focused patch](../../../../tmp/phase5-development/b19-fixture/focused-base-red.patch) · [Focused baseline log](../../../../tmp/phase5-development/b19-fixture/logs/focused-base-red.log)

## Exact builds and packages

Strict offline Maven `-o -B -C -ntp clean verify` passed 328 tests in 13 suites across 11 reactor modules in both default and `-Psynthetic-http` modes. The scans found the detail service and controller, effective profile and V011 in the exact packages. The bootstrap runtime graph contained 26 JARs in default mode and 28 with the synthetic listener. The PostgreSQL classpath included two additional driver dependencies. The inspected packages contained no private fixture classes. [Default build](../../../../tmp/phase5-development/b19-fixture/logs/default-exact-verify.log) · [Profile build](../../../../tmp/phase5-development/b19-fixture/logs/profile-exact-verify.log) · [Default package hashes](../../../../tmp/phase5-development/b19-fixture/logs/default-exact-runtime-sha256.csv) · [Profile package hashes](../../../../tmp/phase5-development/b19-fixture/logs/runtime-sha256.csv)

## Current detail on PostgreSQL

The exact B19 fixture passed 21 checks with Temurin 25.0.5.1+2, PostgreSQL 18.7 and pgJDBC 42.7.14. The normal Spring MVC profile performed the full request, authorization and release flow. The log preserves UTF-8 response bytes in readable form and replaces only generated problem correlation values with placeholders for comparison. The first precommit run stopped on a fixture assertion that compared a bare media type to the shared UTF-8 problem response. The corrected fixture passed before commit and passed again on the exact packages. [Exact B19 wire and database evidence](../../../../tmp/phase5-development/b19-fixture/logs/b19-b19-b2be441-01.log) · [Initial assertion](../../../../tmp/phase5-development/b19-fixture/logs/b19-green-01.log) · [Corrected precommit run](../../../../tmp/phase5-development/b19-fixture/logs/b19-green-02.log)

| Case | Verified response and durable effect |
| --- | --- |
| Authentication and input | 401 with a single Bearer challenge before validation. Authenticated invalid reference, query and body returned 400 before any guard lock. |
| Live row and field access | No row grant yielded the same complete 404 shape and headers as an absent row. Row grant alone returned just `ref`. Separate grants and assignment permitted the full view. A field revocation omitted the revision independently. Retained visibility masked name and state. A fixture-only retired body disappeared on the next detail. |
| Current revision and original receipt | A real Edit advanced the seeded revision to `"9007199254740993"` and detail returned the edited body. Exact Create replay retained revision `"1"`, status, body and Location. Supplying either descriptive revision as an action observation returned 400. An escaped 16 KiB body fit the bounded handoff and retained exact text. |
| Detail first against Delete and revocation | The detail connection paused at guarded transaction commit after the buffer accepted the prepared response. Independent `pg_stat_activity` observed the competing writer blocked on `FOR UPDATE` with the detail connection as blocker. The earlier response retained the exact live mask. |
| Delete or revocation first | The writer paused at the guarded scope witness insert. PostgreSQL showed the detail connection blocked on `FOR SHARE` behind that writer. Detail returned safe 503 until scope proof confirmed and did no protected body read. After confirmation the concealed result matched ordinary 404. Separate deleted metadata and state grants allowed only the authorized tombstone fields. Exact Delete replay remained minimal. PostgreSQL retained the protected body and reason. |
| Publish confirmation | An otherwise-visible published target returned 503 while publication proof remained unresolved after scope confirmation. A concealed target returned ordinary 404. After independent confirmation and guarded projection the actor received the published state. The publication ID appeared only after its own field grant and was the exact decimal string `"9007199254740993"`. |

All successful and failed detail responses were checked against exact field sets and status or protocol expectations. These sets carry no observation, action-context payload or operation key.

## Packaged predecessor regressions

| Regression | PASS records |
| --- | ---: |
| [B10 assignment, visibility and current result](../../../../tmp/phase5-development/b19-fixture/logs/b10-b19-b2be441-01.log) | 32 |
| [B12 guarded release and physical rewind](../../../../tmp/phase5-development/b19-fixture/logs/b12-b19-b2be441-01.log) | 26 |
| [B13 action context and observation](../../../../tmp/phase5-development/b19-fixture/logs/b13-b19-b2be441-01.log) | 17 |
| [B14 Edit, Verify and Accept](../../../../tmp/phase5-development/b19-fixture/logs/b14-b19-b2be441-01.log) | 20 |
| [B15 Delete and Reject](../../../../tmp/phase5-development/b19-fixture/logs/b15-b19-b2be441-01.log) | 20 |
| [B16 publication reservation and proof](../../../../tmp/phase5-development/b19-fixture/logs/b16-b19-b2be441-01.log) | 16 |
| [B17 Publish and independent confirmation](../../../../tmp/phase5-development/b19-fixture/logs/b17-b19-b2be441-01.log) | 14 |
| [B33 two-process receipt contention](../../../../tmp/phase5-development/b19-fixture/logs/b33-b19-b2be441-02-run.log) | 78 |

B33's first setup encountered a PostgreSQL statement timeout while applying the existing release-evidence migration. The unchanged repeat passed. Both B33 worker ports refused connections after shutdown and both workers detached their database backends. Scratch inspection found zero owned postmaster PID files and zero listeners on 55480–55482. The executor returned the port lease to the coordinator. [First B33 run](../../../../tmp/phase5-development/b19-fixture/logs/b33-b19-b2be441-01-run.log) · [PostgreSQL timeout](../../../../tmp/phase5-development/b19-fixture/logs/b33-first-attempt-statement-timeout.log) · [Native matrix](../../../../tmp/phase5-development/b19-fixture/logs/exact-native-matrix.log) · [Owned teardown](../../../../tmp/phase5-development/b19-fixture/logs/owned-teardown.log)

## Candidate artifacts

The SHA-256 manifest covers 63 evidence and package files. The audit reviewed 32 console and fixture logs and found zero matches for signed keys, opaque observations, bearer values, private keys or credential-bearing JDBC URLs. [Candidate archive](../../../../tmp/phase5-development/b19-fixture/candidate-b2be441.tar) · [Full-index patch](../../../../tmp/phase5-development/b19-fixture/candidate-b2be441.patch) · [Digest manifest](../../../../tmp/phase5-development/b19-fixture/evidence-b2be441.sha256) · [Log audit](../../../../tmp/phase5-development/b19-fixture/logs/sanitized-log-audit.log) · [Exact package verifier](../../../../tmp/phase5-development/b19-fixture/Verify-B19-exact.ps1) · [Qualified fixture runner](../../../../tmp/phase5-development/b19-fixture/Run-B19-local.ps1)
