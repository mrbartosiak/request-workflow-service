# B17 Publish evidence

Candidate `cab8a9c0e89dc97d2600b772e5ca6725b3d43cce`, tree `28f24a1c14a6be4ad3813f921234427742cc2545`, branch `codex/b17-publish`. Both Git dates are 2026-11-22 22:37 Warsaw. The clean candidate directly extends independently approved B33 `195636a332cc9d31f197dabe8dd19913198dda83`. B33 added only its private fixture directory to the B15 executable baseline. All 30 adopted plan files matched the PH4-PLAN-r1 manifest with SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. [Assignment](assignment.md) · [Plan verification](../../../../tmp/phase5-development/b17-fixture/logs/ph4-plan-r1-verification.log) · [Handoff](handoff.md)

## Focused baseline

On B15 `aa126e1476dd60551649e97a12378bb3cf8bb49d`, a port-free test with only the Publish route expectation added received 404 where the authenticated boundary required 401 and a Bearer challenge. The existing Edit, Verify, Accept, Delete and Reject checks passed. [Focused patch](../../../../tmp/phase5-development/b17-fixture/focused-base-red.patch) · [Focused baseline log](../../../../tmp/phase5-development/b17-fixture/logs/focused-base-red.log)

## Exact builds and package perimeter

Strict offline `-o -B -C -ntp clean verify` passed 328 tests in 13 suites and 11 reactor modules in both the default and `-Psynthetic-http` modes. Both builds used the clean candidate and qualified Maven cache. The packaged-profile scan covered 30 JARs and found the expected grants, witness, confirmation and audited resolution resources, the application append interface, and the synthetic HTTP launcher. The runtime JARs contained zero private fixture members. [Default build](../../../../tmp/phase5-development/b17-fixture/logs/default-verify.log) · [Synthetic profile build](../../../../tmp/phase5-development/b17-fixture/logs/profile-verify.log) · [Test counts](../../../../tmp/phase5-development/b17-fixture/logs/exact-test-counts.log) · [Package perimeter](../../../../tmp/phase5-development/b17-fixture/logs/exact-packaged-isolation.log)

## Native command and confirmation

| Cut | Verified effect |
| --- | --- |
| Current effect admission | Independent Execute permission and a fresh previous observation are required before reservation. Authorized attempts prepare an applicable ordered scope delta before the gate. PostgreSQL records the state, revision, fact, receipt, publication witness and applicable visibility and scope witness on the same command transaction. |
| Publication confirmation first | The independently audited publication decision and guarded projection can finish while scope confirmation is pending. Protected result and dispatch become available after current scope proof and the exact scope confirmation. |
| Scope confirmation first | An authorized same-key lookup returns 503 `outcome_unknown` while publication is unresolved. A different key defers. The independent publication decision survives an injected projection write denial. While projection is absent, the protected field and dispatch remain closed. Projection retry admits the same association and records the resolver role. |
| Original replay and grants | Byte-exact replay survives a lost response, MVC restart, login alias, and observation expiry. A changed intent on the same key conflicts. Publication field and result grants independently gate the original response. The persisted transition and original association remain singular. |
| Rollback after reservation | A denied witness insert rolls back the request transition, fact, key/receipt, visibility and both local witnesses. The independently reserved number persists. Both original abort proofs are required before the same exact intent can commit on a higher number. |
| Exact wire ID | The real response contains a decimal JSON string above 2^53 and at the positive signed-64-bit maximum. The command assigns increasing values across distinct requests and the shared B16 namespace gate orders their reservations. |

The exact candidate reproduced these cuts against PostgreSQL 18.7 and pgJDBC 42.7.14 on the qualified Temurin 25.0.5.1+2 runtime. The initial attempt could bind loopback but failed its readiness probe under the sandbox. Two isolated retries exposed missing test setup for the MVC query and the predecessor body lock grant. Both were corrected in the fixture. Each attempt stopped its owned processes. [Exact candidate native run](../../../../tmp/phase5-development/b17-fixture/logs/b17-cab8a9c-01.log) · [Successful precommit run](../../../../tmp/phase5-development/b17-fixture/logs/b17-green-04.log) · [Loopback readiness](../../../../tmp/phase5-development/b17-fixture/logs/b17-green-01.log) · [Fixture query setup](../../../../tmp/phase5-development/b17-fixture/logs/b17-green-02.log) · [Fixture role setup](../../../../tmp/phase5-development/b17-fixture/logs/b17-green-03.log)

## Shared regression checks

| Exact packaged regression | PASS records |
| --- | ---: |
| [B12 guarded release and physical rewind](../../../../tmp/phase5-development/b17-fixture/logs/b12-b17-cab8a9c-01.log) | 26 |
| [B13 action context and observations](../../../../tmp/phase5-development/b17-fixture/logs/b13-b17-cab8a9c-01.log) | 17 |
| [B14 Edit, Verify and Accept](../../../../tmp/phase5-development/b17-fixture/logs/b14-b17-cab8a9c-01.log) | 20 |
| [B15 Delete and Reject](../../../../tmp/phase5-development/b17-fixture/logs/b15-b17-cab8a9c-01.log) | 20 |
| [B16 namespace gate, original lineage and reservation limits](../../../../tmp/phase5-development/b17-fixture/logs/b16-b17-cab8a9c-01.log) | 16 |
| [B33 two-process receipt contention and crossed intents](../../../../tmp/phase5-development/b17-fixture/logs/b33-b17-cab8a9c-01-run.log) | 78 |

B16 independently observed a real namespace lock wait across organizations and request types. The waiting transaction reserved after the first command ended, using a higher number after the first rolled back. The same regression retained the confirmed original association through the journal checks, fenced restored absence, and verified signed range exhaustion.

The unchanged B33 suite used two JVMs and the exact B17 runtime packages. The test exercised PostgreSQL key contention, authorized cross-route conflict, normalized concealed retry, and expiry. Both worker ports refused connections after shutdown and their database backends detached. The 09:59:21 Warsaw teardown found zero owned postmaster PID files and zero listeners on 55480–55482 or either worker port. The coordinator received the port lease. [Native matrix](../../../../tmp/phase5-development/b17-fixture/logs/cab8a9c-native-matrix.log) · [Owned teardown](../../../../tmp/phase5-development/b17-fixture/logs/owned-teardown.log)

## Candidate artifacts

The SHA-256 manifest covers the candidate archive and full-index patch, focused red patch, qualified runner, runtime package hashes, and reviewed logs. The log scan found zero matching signed keys, bearer values, opaque observations, private keys or credential-bearing connection strings. [Candidate archive](../../../../tmp/phase5-development/b17-fixture/candidate-cab8a9c.tar) · [Candidate diff](../../../../tmp/phase5-development/b17-fixture/candidate-cab8a9c.patch) · [Digest manifest](../../../../tmp/phase5-development/b17-fixture/evidence-cab8a9c.sha256) · [Sanitized log audit](../../../../tmp/phase5-development/b17-fixture/logs/sanitized-log-audit.log) · [Qualified runner](../../../../tmp/phase5-development/b17-fixture/Run-B17-local.ps1)
