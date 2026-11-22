# B10 correction evidence · Effective access updates

Candidate `723579767fc57b4cdaa1b088cd3f688f292ce47e`, tree `3a186fe2d8f65ccadb1f0198ff6a41f82c9eb7ed`, is the single B10 commit directly above approved B16 `ed9ed3aca45abc25c493bf65a02a33623dd92899`. The author and committer dates are both `2026-11-22T22:30:00+01:00`. The local branch `codex/b10-effective-access` was clean after commit. Its 22 changed paths are application, JDBC, bootstrap and PostgreSQL fixture files. Independent review is pending. [Assignment](assignment.md) · [Initial review](review-9b46a1a.md)

The `PH4-PLAN-r1` manifest SHA-256 was `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. All 30 listed plan digests matched. [Scope acceptance](../../../Phase4-development-planning/scope-proof.md#b10--effective-grant-assignment-and-visibility-updates) · [Wave 2 fixture approval](../g1-u-wave2-approval.md)

## Effective access

- Mutable Create verifies submitted access and its signed key under a shared guard in an effect-free transaction. The effect transaction checks writer lineage, provisionally claims the receipt key, and then takes the exclusive organization guard. It rechecks current access and key eligibility before business writes. A guarded denial rolls back the claim. The release phase takes the shared guard and checks current submitted and result access through handoff.
- The mutable profile’s certified genesis digest includes the exact enable, revoke, assignment and visibility grants for each trusted operator. The mutation service checks the matching certified admission and the operation-specific grant under the owned exclusive guard. The B11 resolver uses its separately authorized and audited port.
- V006 stores affirmative and negative overrides for typed synthetic grants. Enablement, revocation and reinstatement use the same canonical delta, independent preparation, guarded local update, version advance and exact witness. Protected commands and release defer while the independent checkpoint or local projection is incomplete or inconsistent. The audited B11 confirmation activates current access. Assignment updates preserve immutable origin, and visibility updates change field membership.

## Verification

| Check | Observation |
| --- | --- |
| Strict offline `-Psynthetic-http clean verify` | Temurin `25.0.5.1+2`, Maven `3.9.16`, approved settings and the private offline cache. All 11 modules and 320 tests across 10 suites passed with zero failures, errors or skips. The corrected native launcher and the inherited B16 fixture compiled against the same integrated build. |
| B10 receipt order | PostgreSQL observed the duplicate key wait after 22 ms. A third session acquired the same organization guard with `NOWAIT` during the wait. The bounded contender rolled back, and the winner produced one durable effect and replayed exactly. A separate cut confirmed revocation after the tentative claim and before the exclusive guard. The recheck denied Create and left zero additional receipts, requests, reservations or release preparations. |
| B10 operator certification | Changing an otherwise identical profile’s operation-specific permission input changed its policy digest. Mutation and receipt admission deferred against the existing certified scope and produced zero new attempts or release preparations. |
| B10 positive and negative grants | A confirmed new Create grant allowed one durable effect. Its separately confirmed result grant allowed exact replay of that effect. A confirmed reinstatement reopened the earlier receipt. The fixture checked preservation of independent issuance and field grants, and verified no journal attempts for unauthorized and no-op updates. |
| B10 guard and confirmation cuts | The shared handoff and the earlier exclusive Create effect each completed before a queued writer. An earlier exclusive writer fenced waiting replay. A changed local projection, withdrawn independent confirmation, witness failure and lost preparation reply each deferred protected work. The final run passed 32 checks. Nine committed witnesses advanced policy version from 1 to 10. Two audited aborts advanced the confirmed prefix to 11 without a policy update. |
| B11 compatibility | All 22 native checks passed, including verified physical backup and isolated restore on 55482, original-transaction witness proof, audited resolution, and zero new business or release effects from the resolver. |
| B08 compatibility | All 17 listening HTTP checks passed. Two application processes returned the same HTTP 201, Location and body. The databases retained one request effect and reservation and recorded two permitted release preparations. Both application processes shut down. |

The first corrected native run stopped when the added rollback check inspected the pending witness after confirmation. The final run checks the pending witness before confirmation, then verifies the guarded denial and rollback. The fixture uses PostgreSQL `18.7`, pgJDBC `42.7.14` and the approved loopback ports. All owned PostgreSQL processes stopped. Ports 55480, 55481 and 55482 were clear at `2026-11-23T03:18:28.8695358+01:00` and returned to the coordinator. The listed logs had zero matches for bearer strings, private-key delimiters, password assignments and credential-bearing JDBC URL patterns.

## Execution artifacts

All 22 changed files in the archive matched the candidate’s Git blobs.

| Artifact | SHA-256 |
| --- | --- |
| [Candidate archive](../../../../tmp/phase5-development/b10-fixture/correction-7235797.tar) | `3e0905e857c3e50e0bc4141c975eb343c6a2d8545e9c694804f469f07a346160` |
| [Full-index binary diff against B16](../../../../tmp/phase5-development/b10-fixture/correction-7235797-full-index.patch) | `9051205a14128a5750445df6edd272df227e29aee3d73b2e890802091724940e` |
| [Strict offline profile](../../../../tmp/phase5-development/b10-fixture/logs/rebased-offline-verify-01.log) | `3ed584ec825f00b820e2e4cedf005905c7d142679f735aa30c2af45d6d8fe461` |
| [B10 native acceptance](../../../../tmp/phase5-development/b10-fixture/logs/rebased-native-green-02.log) | `cf8eba01ce240dd97f22d31e2da97cb43a0a11400d24ccddb5b60953aaf9d20a` |
| [First fixture assertion diagnostic](../../../../tmp/phase5-development/b10-fixture/logs/rebased-native-green-01.log) | `1adcc17162edfe4dac2c14f59c5ff2ffb3afa30a09fdda22c749f5eec2f0ea2f` |
| [B11 native compatibility](../../../../tmp/phase5-development/b10-fixture/logs/rebased-native-b11-01.log) | `fe7ad0730a447a170f8d4967fa47f0ce09d0e28a4a9a622c2225a13525b53cba` |
| [B11 physical backup verification](../../../../tmp/phase5-development/b10-fixture/runs/b11-b10-correction-01/010-pg_verifybackup-11092429254077747704.log) | `0754d7d15ec01e79210439c42400038c0590df438c3a0b0f404b47d782030ea3` |
| [B08 wire compatibility](../../../../tmp/phase5-development/b10-fixture/logs/rebased-native-b08-01.log) | `ed1295707e9fff1bc9f1d4235efe28daf3edaf157243204c40077cf116c2adc5` |
| [Port teardown](../../../../tmp/phase5-development/b10-fixture/logs/rebased-final-port-check.log) | `d511131399932cd22298e05706832202397f0d25595a123f48e94ef1328bf446` |
| [Prior candidate control receipt for command ordering](../../../../tmp/phase5-development/b10-fixture/logs/rebased-key-order-red-02.log) | `d4da7e2a48db4ea2c48ce2c4034cbdf94c0feeaa59b146fb48b6e53f903ae44b` |
| [Prior candidate control receipt for operator certification](../../../../tmp/phase5-development/b10-fixture/logs/rebased-operator-red-02.log) | `a6a631d93853c412763f4416b48876f59f0f5dc7086c4541e3b88a101ab258f3` |

For B12, use the guarded submitted and result checks through the bounded handoff. For B13–B15, read assignment and security-fact versions under the appropriate organization guard. For B19–B21, `CurrentAccess.permittedFields` composes the confirmed current grant, assignment and field mask on the owned connection. The B10 synthetic field reader derives eligibility from the Create-result grant.
