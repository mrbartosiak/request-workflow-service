# B27 V3 offline correction review

**Approved for the offline development stage** on `762b499243b7e741ee2db132dc51c1442bc04d8e`. The second receiver withheld acknowledgment for the original lost-ACK event after each of the three hold outcomes. The independently certified domain admitted its event in every case. Both earlier blocking findings are resolved for this stage. Executor GPT-6.2 Sol xhigh (56). Independent reviewer GPT-6.2 Astra xhigh (62). [V1 finding](review-b52193a.md) · [V2 finding](review-2f30d81.md) · [B27 acceptance](../../../Phase4-development-planning/inbound.md#b27--inbound-continuity-and-restore-admission)

## Decisive replay

I ran the original reviewer probes against the V3 production sources and V3 transaction fixture in a private extraction of the verified archive. Before rewind, A1 and A2 both committed and A2's response was lost. The backup contained A1 alone. A pinned scope archive falsely asserted both keys were absent. A1's committed pair disproved that seal. A second receiver then retried A2.

| Detecting transaction | A2 retry | Independent B | Restored receipts / obligations | A2 stamp |
| --- | --- | --- | --- | --- |
| Hold committed | Unavailable | Acknowledged | 2 / 2 | Absent |
| Hold committed, response lost | Unavailable | Acknowledged | 2 / 2 | Absent |
| Hold rolled back | Unavailable | Acknowledged | 2 / 2 | Absent |

In the rollback case the next receiver rediscovered the contradiction from the committed A1 pair. It safely withheld A2 even though the hold table was empty at entry. On V2 this same cut acknowledged A2 and wrote a third, freshly stamped pair. The three independent probes and all 18 delivered inbound cases passed with zero failures, errors or skips. The review used B01's cached toolchain and strict offline Maven options, targeting `integration` and its dependencies with `clean verify`. Independent log SHA-256 `c107c02611aae5f055cb102a6c20ef715b125f723a9910ef407856859de8b9e9`. Executor's V2 rollback red SHA-256 `d483b0c6dd30313f93085b612303c5e4876cd4fee3e8e55f5eb6785832e33047`. [Independent counts and assertions](../../../../tmp/phase5-development/b27-review-762b499/inbound-and-independent-probes.log) · [V2 red](../../../../tmp/phase5-development/b27-v3-offline-evidence/final/scope-hold-rollback-red.log)

## Admission and JDBC inspection

Restored ingress authenticates the sender, obtains the current writer and organization guard, verifies independent coverage, and authorizes the mapping and target before the workflow scope scan. An unlisted submission receives no admission. For a listed identity, JDBC takes a transaction-scoped lock on the exact source system, tenant and identity domain across organization guards. Inside READ COMMITTED, subsequent statements check held seals and the committed receipt/processing pairs before full-key arbitration. The lock covers admission and the resulting workflow transaction. [Coordinator](../../../../tmp/phase5-development/b27-inbound-continuity-v3/integration/src/main/java/com/company/requests/integration/InboundCoordinator.java) · [JDBC adapter](../../../../tmp/phase5-development/b27-inbound-continuity-v3/adapter-integration-jdbc/src/main/java/com/company/requests/adapter/integration/jdbc/JdbcInboundInbox.java)

- A local original pair must match the independently retained binding, v1 processing coordinates and transaction provenance. A proved original missing from the restore can be reconstructed once using its original identity and transaction reference.
- At a key proved absent at the cut, a local fresh pair requires a v1 obligation and a fresh stamp from the same transaction, carrying the current seal and a null original transaction. Unlisted committed rows, missing processing or incompatible provenance fence the scope. A genuine competing submission against a proven pair still follows the idempotent quarantine path.
- A replacement seal must be signed, pinned and complete. It must reconcile each recorded disputed identity and all local scope rows, and provide its own proof for the requested key. The original disproved seal remains closed. The delivered tests exercised wrong transaction, incomplete or unpinned replacement, omitted key, exact recovery and repeated replay.
- The signed possible-remote-effect disposition withholds ingress for its source scope. The ordinary B26 receiver and base migration retain their existing behavior. The full default and synthetic-http executor runs each passed 458 tests in 22 suites, with zero failures, errors or skips and ten packaged JARs scanned. [Test suite](../../../../tmp/phase5-development/b27-inbound-continuity-v3/integration/src/test/java/com/company/requests/integration/InboundCoordinatorTest.java) · [Executor evidence](../../../../tmp/phase5-development/b27-v3-offline-evidence/final/evidence.sha256)

## Exact revision

| Item | Verified value |
| --- | --- |
| Tree | `3e40c9cb1cce266a5b25c26f1905641941b013ca` |
| Sole published B42 parent | `b489f38f7d35fefbb0156fc32d52e274cb17beee` |
| Author and committer timestamps | `2026-11-22T22:52:00+01:00` |
| Source ZIP SHA-256 | `ccdd1bd509297ede16fddc9a776cbf99373793683d009d2203c3daf5bc1f0ac6` |
| All 723 paths SHA-256 | `e99db86859e6a2e56ee98c308e5357b598c037d0d935494e5b25aa87efc598a5` |
| Nine changes from B42 SHA-256 | `6e5c220124eebd37d24ecb351cb404bdbe4e7032c0f4af05f944ea5064f446c3` |
| Five corrections from V2 SHA-256 | `fb18e5f9c5610a1dadc2bcdc36e641daa7fd9d1d38058a916e16c50f39a0ba9b` |
| Executor evidence manifest SHA-256 | `db4713e42aab7b0c93422d4ea7bbcbe62134e4019c235673e8d805daf50797cc` |

All 723 archive paths matched the checkout bytes and the exact Git blobs after applicable text line-ending normalization. Both changed-path sets and all twelve executor evidence digests verified.

Actual B29 writer certification and final response fencing, independent extraction of committed history, restricted-role PostgreSQL grants, concurrent two-process backup/restore, client acknowledgment, independent business and remote-effect counts, and owned native teardown remain pending. The planning assumption supplied no native result. [Candidate handoff](../../../../tmp/phase5-development/b27-inbound-continuity-v3/Documentation/Phase5-development/work/B27/handoff.md) · [Downstream planning scope](../g0-downstream-development-assumption.md)
