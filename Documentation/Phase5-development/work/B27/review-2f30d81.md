# B27 V2 offline correction review

**Changes required** for `2f30d81c568f81c14d558aa7abfee9af3b3cb15f`. The earlier two-receiver regression passes when the hold commits, including when the commit response is lost. When the same hold transaction rolls back, another receiver still uses the disproved seal to acknowledge the original lost-ACK event as fresh. Executor GPT-6.2 Sol xhigh (56). Independent reviewer GPT-6.2 Astra xhigh (62). [Previous finding](review-b52193a.md) · [B27 acceptance](../../../Phase4-development-planning/inbound.md#b27--inbound-continuity-and-restore-admission)

## Blocking rollback case

The reviewer fixture acknowledged A1, captured the backup, committed A2 and lost its acknowledgment. The restore contains A1 only. An independently signed and pinned archive incorrectly declares both identities absent. A1's original, unmarked receipt and obligation disprove that archive. Each retry below uses another receiver with the same seal. The separately certified B domain admitted its event in all three cases. [Inbound recovery contract](../../../Phase3-LLD/inbound-recovery.md)

| Hold transaction after detection | A2 retry | Restored receipts / obligations | A2 provenance |
| --- | --- | --- | --- |
| Committed | Unavailable | 2 / 2 | No A2 stamp |
| Committed, response lost | Unavailable | 2 / 2 | No A2 stamp |
| Rolled back, database available for next request | **Acknowledged** | **3 / 3** | **Fresh, original transaction null** |

The third case used the candidate fixture's existing `failCommit` control. `holdRecoveryScope` wrote the hold inside the detecting workflow transaction. Rollback discarded that write and left A1's contradictory committed evidence intact. The next `admitRecoveryScope` found no hold, and the A2 receiver inserted a fresh pair. The expected outcome was unavailable with no A2 writes. Reviewer probe SHA-256 `1d77c3bcec44094d8e92e3bb2133821a73a2b95653f45bb4a6e30396e79f38a2`. Failing log SHA-256 `c2f5a5816c21f54b40e88a81e1bd9e792ab1bce47a4cf414d3e4ce378ee16aea`. [Probe](../../../../tmp/phase5-development/b27-review-2f30d81/probe/integration/src/test/java/com/company/requests/integration/B27HoldCommitReviewProbeTest.java) · [Failure](../../../../tmp/phase5-development/b27-review-2f30d81/failed-hold-commit.log) · [Committed-hold controls](../../../../tmp/phase5-development/b27-review-2f30d81/committed-hold-controls.log)

Require positive scope validation or a durable unresolved state that survives this failure before any receiver admits another key under the seal. One option is to validate against the available committed scope evidence before relying on a fresh absence. Add the rollback case alongside both committed controls and rerun independently on the replacement commit.

## Verified correction and remaining scope

The JDBC implementation checks READ COMMITTED isolation, takes a transaction-scoped advisory lock derived from the exact source scope, then reads holds in a separate statement before taking the B26 full-key lock. Different organization guards use the same source lock. Once committed, a held seal denies every receiver. A different signed and currently pinned complete archive must supply matching retained evidence for each disputed identity and its transaction, followed by proof for the submitted key. Authenticated semantic conflicts with proven originals still take the quarantine path. B26 retains its ordinary receiver and base migration alongside the opt-in recovery entry points. These JDBC conclusions come from source inspection. [JDBC adapter](../../../../tmp/phase5-development/b27-inbound-continuity-v2/adapter-integration-jdbc/src/main/java/com/company/requests/adapter/integration/jdbc/JdbcInboundInbox.java) · [Candidate handoff](../../../../tmp/phase5-development/b27-inbound-continuity-v2/Documentation/Phase5-development/work/B27/handoff.md)

All 15 delivered inbound tests passed in an independent, strict cached offline run, including the new replacement-seal and cross-organization cases. The two reviewer controls passed. The additional rollback probe failed at its intended assertion. The executor's sealed default and synthetic-http builds each recorded 455 tests in 22 suites with zero failures, errors or skips. Reproduce the rollback case in the linked reviewer extraction with the B01 Maven toolchain, settings and cache, using `-o -B -C -ntp -pl integration -am -Dtest=B27HoldCommitReviewProbeTest#failedHoldCommitCannotFreshAdmitLostAckOnAnotherReceiver -Dsurefire.failIfNoSpecifiedTests=false test`. [Independent inbound suite](../../../../tmp/phase5-development/b27-review-2f30d81/inbound-suite.log) · [Executor manifest](../../../../tmp/phase5-development/b27-v2-offline-evidence/final/evidence.sha256)

## Exact revision

| Item | Verified value |
| --- | --- |
| Candidate tree | `be20f9e231a3d5b86b28322b68b6fa10867aca82` |
| Sole published B42 parent | `b489f38f7d35fefbb0156fc32d52e274cb17beee` |
| Author and committer timestamps | `2026-11-22T22:52:00+01:00` |
| Source ZIP SHA-256 | `e4da9c66abd5de3528f4a8524ee6ba298e328ea25cc5a6f68e4e7d1797eb7d6d` |
| All 723 paths SHA-256 | `ec877e1e10a651ddf550fab5894071ceac8da0e47f5e4c9367344545303e2a29` |
| Nine changes from B42 SHA-256 | `e9113686b4cfdac58618c9337117c10de543f83eb1ab3ee8113d54c28dc95e7e` |
| Six corrections from V1 SHA-256 | `2bf80708c38fb711db77b2444bfb293eff8962c822c63d99ab9ede207e45cccd` |
| Executor evidence manifest SHA-256 | `f9453f4f144ff8e0bcfa4f46d4cf3076a1f7cb0387e668d944ea709b6f06c9f7` |

All archive entries matched the checkout byte-for-byte and the exact Git blobs after applicable text line-ending normalization. Both changed-path sets and all twelve evidence digests verified.

Native two-process PostgreSQL locking, runtime grants, independent retained evidence, the actual B29 writer and final-response fences, business and remote-effect counts, client receipt and native teardown remain pending. The downstream planning assumption supplies no observed runtime result. [Planning scope](../g0-downstream-development-assumption.md)
