# B16 correction evidence · First registration

GPT-6.2 Sol xhigh (Intelligence Index 56) froze `ed9ed3aca45abc25c493bf65a02a33623dd92899`, tree `a620303684b73f4fb0e47e9e1022501eabd39f8f`, on `codex/b16-publication-journal`. Both Git dates are 2026-11-22 22:28:00 +01:00. Its parent is the frozen first candidate `b3f94486861150cb9975f7ccbdcb85727482ed4f`. The original evidence and Astra review still match SHA-256 `c99da23713503c41230703f193fd99b67897a0429bee2caaf4eecf7ea5bd11ba` and `4149a1e7bbb6b21783ab02097ee0bbc4038b63f424399b3acdfce2d80cbde29e` respectively. [Assignment](assignment.md) · [Original evidence](evidence.md) · [P1 review](review-b3f9448.md)

## Correction and reproduction

The owned workflow transaction still takes the database-wide namespace lock after the scoped request lock. Once the exact attempt exists, it takes eight companion transaction locks encoding all 256 bits of a domain-separated digest. The digest binds the namespace, attempt UUID, certified writer and generation, organization, type, ref, PID, XID, receipt identity and resulting revision. The original observer checks the global lock, XID lock and all companion locks in one materialized PostgreSQL lock snapshot for that certified backend. PostgreSQL releases those locks at transaction termination. The existing registration and reservation paths both invoke this exact gate check before their first write. This uses the existing trusted workflow command role and original observer, with four publication-specific files changed. [Correction diff](../../../../tmp/phase5-development/b16-gate-binding/correction.diff)

| Check | Native observation |
| --- | --- |
| Exact predecessor red | The strengthened fixture compiled against the verified archive of `b3f94486861150cb9975f7ccbdcb85727482ed4f`. With two eligible targets at the same revision it gated one target and registered the other by substituting organization, type and ref before any durable attempt existed. The oracle exited 1 at that assertion. [Red transcript](../../../../tmp/phase5-development/b16-gate-binding/logs/native-red.log) |
| Corrected first registration | The same fixture and unchanged schema used the corrected classes compiled from the frozen source. The three substituted messages changed the target, receipt identity and attempt UUID respectively. Each was denied. Neither target had an active binding, each attempted registration remained absent and the high-water stayed zero. The genuine first message and exact repeat returned the same REGISTERED row, then the valid reservation burned 1. |
| B16 regression | The actual different-organization contender waited on the shared gate. Known rollback burned 1 and the following commit got 2. Early closure and a lost reply through independent proof restart kept their original outcomes. Repeats, stale generation, active-ref exclusion, same-system-ID rewind, late witness fencing and original confirmation passed. Exact increasing values above 2^53 and signed-64-bit exhaustion passed. [Green transcript](../../../../tmp/phase5-development/b16-gate-binding/logs/native-green.log) · [Native backup verification](../../../../tmp/phase5-development/b16-gate-binding/runs/b16-gate-binding-green/014-pg_verifybackup-5222797910683923624.log) |
| Role and process evidence | Both native runs used only the established synthetic PostgreSQL 18.7 and pgJDBC 42.7.14 fixture roles. Each launcher stopped its owned processes. Exclusive IPv4 loopback binds succeeded on 55480, 55481 and 55482 before launch and after teardown. [Qualified inputs](../B04/g1-u-request.md) · [Before](../../../../tmp/phase5-development/b16-gate-binding/logs/ports-before.log) · [After](../../../../tmp/phase5-development/b16-gate-binding/logs/ports-after.log) |

The fresh candidate archive matched all 11 B16 change-set blobs after Git's normal text-line conversion. The established private offline repository and settings, Temurin 25.0.5.1+2 and installed Maven 3.9.16 ran `-o -B -C -ntp -Psynthetic-http clean verify` against that archive. All 11 modules built. Surefire reported 320 tests in ten suites, with zero failures, errors or skips. [Archive verification](../../../../tmp/phase5-development/b16-gate-binding/logs/exact-archive-blobs.log) · [Exact build](../../../../tmp/phase5-development/b16-gate-binding/logs/exact-offline-clean-verify.log) · [Surefire counts](../../../../tmp/phase5-development/b16-gate-binding/logs/exact-test-totals.log)

| Artifact | SHA-256 |
| --- | --- |
| Frozen Git archive | `0e219a89ac685c3a9b7725d20f41ca196f44f1303aec02783b886d6bfd451bae` |
| Correction diff | `a8b3170d122bca6d17b6be929ec7b3bac1e5ed8383c9fd03f3915f289c32189e` |
| Full B16 diff from B11 | `7e7029858ed7fc2dc88c70e91caf22d5155fc06925e1430b48cf7f6e99f72f10` |
| Exact normalized blob verification | `403e562c15b05bcd541fc78f42a7ff6174edc7068056204c236def27bf6ddc07` |
| Exact native red | `917cf7ebf4af8bb91b000197bd55d6887faddbe2e4cd95e12fc51e22e4d27bcd` |
| Corrected native green | `13d6fe6b0dd99a15142ad9d3e4cac5dff9581d0f1275559c1756496a6f4347fa` |
| Port checks, each transcript | `ba704c9752b05a270b58e86a079e78e9109bd299719946edd28108d254adcaf9` |
| Native backup verification | `0754d7d15ec01e79210439c42400038c0590df438c3a0b0f404b47d782030ea3` |
| Exact offline build | `376df424b62f97fa2c1ae3bf2196bd3a2b2f58603a1a83f2870af7b1c515be47` |
| Surefire counts | `494a98875a7d22a85befe0251896ca8df4bb78eeaba5f8f207c482a86ab1d1d7` |

B17 should pass the exact attempt produced by the gate through registration, reservation and witness append. The independent proof still covers one certified live workflow postmaster. B29 receives the same authoritative attempt evidence for its cross-generation work, and B37 can keep distinguishing reservation and confirmation from evidence of delivery. [Successor handoff](evidence.md#successor-handoff)
