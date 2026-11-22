# B11 correction evidence · Database terminal authority

Candidate `af9eddf15e3f5cfc625a36b8ba09110b6425cead`, tree `4cf3f7ea075820479fc95601f566b9436de10902`, on `codex/b11-scope-resolver`. The six-file correction directly follows `6fa09f4ee816d18f3a44b2afbe92391f966c4656`, tree `2e2ed611a03dd12dd3a26495a8836a7d329bc4dc`. Its integrated B09 ancestor is `c2634d1d4741e0d90378609391a5de5d6640e34b`. GPT-6.2 Sol xhigh (56) executed the correction on 2026-11-22 Europe/Warsaw. Ready for renewed independent Astra xhigh (62) review. [Assignment](assignment.md) · [Changes-required review](review-6fa09f4.md) · [Original evidence](evidence.md)

This addendum retains the original evidence SHA-256 `621ab4ed40acd7802de70b392dd2e71739b403446ba19e114b9f23e628bbb146` and review SHA-256 `df966396d05ce554f3f2ea94216907660cacf085386e6b817fcf1fefa435cee3`. The accepted `PH4-PLAN-r1` manifest SHA-256 is `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. [G0](../g0-wave2-approval.md) · [G0-B](../g0-b-wave2-approval.md) · [G1-U](../g1-u-wave2-approval.md) · [B11 acceptance](../../../Phase4-development-planning/scope-proof.md#b11--audited-resolver-and-confirmation-projection)

## Corrected boundary and observations

The preparation role now inserts registration coordinates and updates only the sequence on the attempt table. V004 supplies the REGISTERED default and derives PENDING when the sequence is assigned. Its transition guard rejects terminal or numbered registrations, changed identity coordinates, and unauthorized transitions even under historical broad grants. The resolver role has EXECUTE on the private terminal routine and lacks direct attempt-state UPDATE. The routine binds the exact authorized audit row to the scope, attempt and operation, rechecks the current operator grant, applies the terminal state and records its audit association in one independent transaction. The application still checks exact certified original evidence and guarded projection coordinates. Both roles lack raw payload reads. [V004 migration](../../../tmp/phase5-development/b11-scope-resolver/adapter-authority/src/main/resources/db/authority/V004__scope_authority_resolver.sql) · [B11 oracle](../../../tmp/phase5-development/b11-scope-resolver/fixtures/postgresql/B11Postgresql.java)

The owner migration holds the attempt-table lock while recording pre-existing ABORTED rows from a trusted B09 V003 journal. The upgrade assumes that the owner qualified those predecessor closures through B09's certified rollback contract. Unresolved predecessor attempts receive no legacy provenance. Numbered and unnumbered closures on V004 require their audited association. The prefix verifier checks that evidence before accepting either shape. [Predecessor integration](../B09/integration.md) · [Checkpoint verifier](../../../tmp/phase5-development/b11-scope-resolver/adapter-authority/src/main/java/com/company/requests/adapter/authority/ScopeJournalCheckpoint.java)

| Check | Observed result |
| --- | --- |
| Exact original candidate red | The independently authenticated preparation role on archived `6fa09f4` reported `sequence UPDATE=true`, `state UPDATE=true` and `state INSERT=true`. The read-only catalog control exited 1 at the intended assertion and its owned clusters stopped. |
| Corrected registration and transition privileges | The enabled preparation login had registration-column INSERT and sequence UPDATE. PostgreSQL declined direct terminal state UPDATE, terminal or pre-numbered INSERT, mutation of a closed sequence and direct terminal function invocation. The dedicated resolver login had function EXECUTE, lacked direct attempt-state UPDATE and could not apply a missing audit item. |
| Stale B09 authorization on V004 | The fixture restored the old role grants temporarily. PostgreSQL still returned 42501 for malformed inserts and direct numbered or unnumbered closure. The actual stale B09 abort API, given original rollback evidence, deferred both shapes. The fixture restored the narrow grants afterward. |
| Genuine V003 migration | The B09-only API certified and closed one numbered and one unnumbered abort before V004. The numbered closure retained sequence 1 and enabled guarded projection rebuild at policy version 1. Delayed prepare of the unnumbered closure returned ABORTED with no sequence. A distinct unresolved registration survived as REGISTERED without legacy or audited terminal evidence. |
| New terminal associations | New committed, numbered abort and unnumbered abort decisions linked the exact authorized audit. Temporarily removing either abort association preserved the existing state, fenced public admission and deferred the exact audited retry. Restoring the exact association restored verified readiness. |
| Original B11 cuts and predecessor | The green launcher passed 22 checks, including exact witness mismatch and saved delta rejection, the physically verified same-system-ID rewind, gap, commit-to-projection recovery and zero new business or release rows. A separate B09-only run passed 16 checks. The strict offline profile passed 317 tests in nine suites and 11 modules with zero failures, errors or skips. |

## Reproduction and checksums

Use the adopted Temurin 25.0.5.1+2 and Maven 3.9.16, both isolated settings arguments, the existing private `b11-fixture/repository`, and G1-U PostgreSQL 18.7 with pgJDBC 42.7.14. The correction adds no dependencies. Compile against the candidate checkout. For the red, extract an exact `git archive` of `6fa09f4ee816d18f3a44b2afbe92391f966c4656` under the correction scratch `base` directory and run the offline compile there. Use fresh IDs on rerun and reserve 55480, 55481 and 55482 before binding. [Runtime pins and original tooling](evidence.md#reproduction-and-checksums) · [Correction launcher](../../../tmp/phase5-development/b11-role-guard/run-fixture.ps1)

~~~powershell
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" -Psynthetic-http clean verify
# In the archived original candidate, compile -pl adapter-jdbc,adapter-authority -am test-compile instead.
& $launcher -Mode base -Compile
& $launcher -Mode base -RunId b06-b11-role-red-01
& $launcher -Mode green -Compile
& $launcher -Mode green -RunId b11-role-guard-green-01
& $launcher -Mode b09 -Compile
& $launcher -Mode b09 -RunId b09-b11-role-regression-01
~~~

| Artifact | SHA-256 |
| --- | --- |
| [Red catalog probe](../../../tmp/phase5-development/b11-role-guard/B11RoleBoundaryRed.java) | `69d629073e6dbf46c59790cfdf79600e4e1d32a126b3ad908bc2c535e7b47773` |
| [Correction launcher](../../../tmp/phase5-development/b11-role-guard/run-fixture.ps1) | `b24cd645f6275a1370302ea9a0c2040e93b80c8b241ca9472d2a6172c275bb3a` |
| [Exact-original offline compile](../../../tmp/phase5-development/b11-role-guard/logs/original-offline-test-compile.log) | `4d339d228b70039fd0f3b96ae7900137a7fda9921baac79cb63460b80f2ff43f` |
| [Exact-original grants red](../../../tmp/phase5-development/b11-role-guard/logs/exact-original-role-red.log) | `1954b20e3100b31eedc68ade51f09f73d01f2ebcb0035cd0da78fb58a394866b` |
| [Strict offline correction build](../../../tmp/phase5-development/b11-role-guard/logs/candidate-profile-offline-verify.log) | `d307731a3ba0b7d596d709066b2e917b103ab1131bc1373610e90d75ef431766` |
| [B11 green, 22 PASS](../../../tmp/phase5-development/b11-role-guard/logs/b11-role-green-01.log) | `c15bd9dc04617a71bc95fcf1fc688169dbe3a5c73ec33ebbbfc1dcaa7407691f` |
| [Native rewind backup verification](../../../tmp/phase5-development/b11-role-guard/runs/b11-role-guard-green-01/010-pg_verifybackup-931513192007121693.log) | `0754d7d15ec01e79210439c42400038c0590df438c3a0b0f404b47d782030ea3` |
| [B09-only regression, 16 PASS](../../../tmp/phase5-development/b11-role-guard/logs/b09-role-regression-01.log) | `97cb52efef7025834da5067811fdbab54ecee5ed09ae4bdf20c5c3d52ca277fe` |
| [Prebind listeners](../../../tmp/phase5-development/b11-role-guard/logs/prebind-ports.log) | `badbd0ed14c79775c6a22dfdc6b4dd244379833aa5fb0c71642be3b1df181541` |
| [Final listeners](../../../tmp/phase5-development/b11-role-guard/logs/final-port-check.log) | `3be74c8967a5518e72f24d6114e8b9a5e162b9a8a6af1fdd1e34087322fbae4e` |

The shareable transcripts contain no detected bearer values, password assignments, private-key blocks or credential-bearing JDBC URLs. The launchers stopped all owned processes. The Windows check saw the three ports clear at 21:25:57 Europe/Warsaw and the coordinator received the release.

## Successor amendment

B10 should apply the exact V004 grants exercised in the corrected oracle and retire the legacy table-wide INSERT and state UPDATE grants on an upgraded preparation login. Keep the owner separate from the preparation, resolver, original observer and workflow projector identities. The preparation login needs the integrity-only terminal check view for the current checkpoint. The resolver needs the private routine and its existing audit, witness and digest inputs. Its terminal routine links the audit without disclosing credential or delta bytes. Keep the certified-live-lineage, guard, effective version and result-release checks from the original handoff for B10, B12, B16 and B29–B31. [Corrected fixture role setup](../../../tmp/phase5-development/b11-scope-resolver/fixtures/postgresql/B11Postgresql.java) · [Original handoff](evidence.md#successor-handoff)
