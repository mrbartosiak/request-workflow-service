# B27 offline review · b52193a

**Changes required for the offline development stage.** A second restored receiver used a disproved scope archive to acknowledge an original lost-ACK event as fresh. Hold integration pending correction and independent review of the replacement commit. Executor GPT-6.2 Sol xhigh (56). Independent reviewer GPT-6.2 Astra xhigh (62). [Assignment](assignment.md) · [B27 acceptance](../../../Phase4-development-planning/inbound.md#b27--inbound-continuity-and-restore-admission)

## Blocking finding

The recovery contract requires trusted coverage for the entire source, tenant and identity domain, including commits whose acknowledgment was lost. A receipt in a trusted backup contradicting that scope archive's explicit absence claim disproves its completeness. [Inbound recovery](../../../Phase3-LLD/inbound-recovery.md)

I reproduced the following sequence through the signed synthetic receiver using the candidate's transaction fixture and a reviewer-only test. A and B are separately certified identity domains. Both restored receivers use the same authority and seal.

| Step | Observed outcome |
| --- | --- |
| Commit A1, snapshot, then commit A2 and lose the commit response | Original contains two receipt/obligation pairs. The restore contains A1 only. |
| Supply a signed, pinned `COMPLETE` archive incorrectly asserting A1 and A2 were both absent | A1's unmarked committed pair contradicts the seal. Its retry returns `UNAVAILABLE`. |
| Admit an independently certified B event | `ACKNOWLEDGED` with one B pair. |
| Retry A2 through another receiver with the same A seal | **Expected** `UNAVAILABLE` and no A2 writes. **Observed** `ACKNOWLEDGED`. The restore now contains three receipts and three obligations. A2's stamp has a null original transaction, identifying a fresh admission. |

`JdbcInboundInbox.classifyRecovery` detects inconsistent local provenance. `InboundCoordinator.receiveVerified` converts that condition into request-local unavailability. The next `InboundRecovery.coverage` lookup continues to accept the same seal. The delivered existing-row test exercises individual mismatches and does not submit a second key from the contradicted scope. [Coordinator](../../../../tmp/phase5-development/b27-inbound-continuity/integration/src/main/java/com/company/requests/integration/InboundCoordinator.java) · [JDBC classification](../../../../tmp/phase5-development/b27-inbound-continuity/adapter-integration-jdbc/src/main/java/com/company/requests/adapter/integration/jdbc/JdbcInboundInbox.java) · [Coverage lookup](../../../../tmp/phase5-development/b27-inbound-continuity/integration/src/main/java/com/company/requests/integration/InboundRecovery.java)

Require scope-wide agreement before accepting a seal, or ensure a discovered proof contradiction fences that lineage and source scope across receivers until independently recertified. Keep unrelated certified scopes available. Add the two-key lost-ACK regression and verify that A2 cannot acquire a fresh receipt or obligation under the disproved seal. The reviewer probe SHA-256 is `cf15577e08d92f49f6de65d6007501cd6bdbc0540688c1cdd215a1ebc0a2a0b7`. Its failing log SHA-256 is `e89f89b4bce155120ad94802e6b621e577eb1ffb1d2c116bd0a9c6a35a011214`. [Probe](../../../../tmp/phase5-development/b27-review-b52193a/scope-probe/integration/src/test/java/com/company/requests/integration/B27LostAckScopeReviewProbeTest.java) · [Observed assertion and counts](../../../../tmp/phase5-development/b27-review-b52193a/lost-ack-scope-contradiction.log)

## Exact source and independent checks

| Item | Verified value |
| --- | --- |
| Candidate | `b52193ae4715680bda44c7f85c9d53fa9d67791c` |
| Tree | `c4a71bc59dd24f898ff3c4a2e7634c83309c7e68` |
| Sole parent and recorded published B42 | `b489f38f7d35fefbb0156fc32d52e274cb17beee` |
| Author and committer timestamps | `2026-11-22T22:52:00+01:00` |
| Source ZIP SHA-256 | `89816c2eed29003a53192cb5f0f30c45347505ec231808a297c245dc88df1fec` |
| All 723 paths manifest SHA-256 | `97e556b81bc2c19201c6476c3c076b0cc51b9ccd4ddc936e84891ef011b8a7bd` |
| Nine changed paths manifest SHA-256 | `f98369102cd78355502c48d4420f9f51cc59b7101eaeb0204d738425939fb31b` |
| Executor evidence manifest SHA-256 | `d30bbab9fc004a03dfc2c4f583a65ea33909f3cb806e24f3816ad7b17f8b5ffc` |

All 723 ZIP entries matched the exact checkout bytes and candidate Git blobs after text line-ending normalization where applicable. The manifest identifies exactly the nine changed paths. I verified all ten executor evidence entries. The independent default and synthetic-http builds each passed 453 tests in 22 suites with zero failures, errors and skips. Each ran 13 inbound coordinator tests. The ten independently built synthetic-profile JARs matched the executor's artifact digests. Both builds used the verified archive, an isolated dependency cache, Maven 3.9.16, the B01 Temurin 25 toolchain and the B01 settings for both user and global configuration. Commands used `-o -B -C -ntp` with `clean verify` and the second run added `-Psynthetic-http`. [Source verification](../../../../tmp/phase5-development/b27-review-b52193a/source-verification.txt) · [Default build](../../../../tmp/phase5-development/b27-review-b52193a/default-verify.log) · [Synthetic build](../../../../tmp/phase5-development/b27-review-b52193a/synthetic-http-verify.log)

A separate reviewer probe on exact B42 retained the committed original pair outside a rewound workflow and advanced the current target revision. B42's boolean admission returned `REJECTED` where exact recovery required `ACKNOWLEDGED`. This is a review-time baseline observation. The candidate's exact-recovery test passes that case with signed retained evidence. The candidate tests also exercised changed semantics and type, idempotent quarantine, unknown or lost commit responses, missing and tampered proof, explicit fresh absence and source-version limits. The signed `POSSIBLE_REMOTE_EFFECT` disposition fenced its scope before ledger classification while B continued. [B42 probe result](../../../../tmp/phase5-development/b27-review-b52193a/reviewed-base-red.log) · [Delivered test suite](../../../../tmp/phase5-development/b27-inbound-continuity/integration/src/test/java/com/company/requests/integration/InboundCoordinatorTest.java)

## Runtime evidence remaining

The ordinary B26 receiver and migration remain the default entry points. Inspection found that restored receipt, processing and stamp writes share the guarded workflow transaction and B26 full-key arbitration. The handoff specifies the additional SELECT and INSERT columns required for the recovered profile. The receiver has no direct business-command or connector invocation. The offline fixture measures receipt, processing and quarantine rows and tests the remote hold. Independent business-effect and remote-recorder counts remain open. [B26 handoff](../B26/handoff.md) · [B27 candidate handoff](../../../../tmp/phase5-development/b27-inbound-continuity/Documentation/Phase5-development/work/B27/handoff.md)

B29 runtime wiring must bind current admission to the connected workflow marker, incident, independent active generation and pinned scope seal through transaction completion and fence the final response handoff. Actual restricted-role grants, SQL arbitration, trusted committed history, old-writer exclusion, two-process backup/restore, response and client cuts, remote reconciliation and owned native teardown await qualification. The planning assumption provided no observed B29 result. [Planning scope](../g0-downstream-development-assumption.md) · [B29 successor boundary](../../../../tmp/phase5-development/b29-recovery-epoch-v3/Documentation/Phase5-development/work/B29/handoff.md)
