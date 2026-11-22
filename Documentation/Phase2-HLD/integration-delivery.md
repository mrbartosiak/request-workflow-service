# Durable integration delivery

**Scope** Common delivery and reconciliation contract. **Status** DEC-08 approves durable obligations, proof-gated retries, quarantine and inbound deduplication. Live triggers, connectors, data and proof details remain open under DEC-03/09/14. [Phase 1 integrations](../Phase1-conceptual/integrations.md#common-integration-lifecycle) · [Vendor assessment](vendor-assessment.md)

## Outbound contract

Commit each obligation with its business action. Keep a stable effect identity per destination and operation and separate delivery status. Process off the REST path with its own limits in the same deployment. Proposed bounded claims include durable attempt records. Check permitted data, cancellation and applicable grants. Order dispatch authorization with effective local revocation of each required grant. Earlier revocation blocks the send. If dispatch is authorized first and revocation races transmission, retain the uncertain outcome and reconcile. Decide per connector whether service authority alone suffices or the originating actor's grant is also required. For ordered effects hold successors or reconcile verified state.

Proposed states include pending, in progress, confirmed, retryable, permanent failure, unknown and suppressed. Confirm with proof of the required effect. An asynchronously accepted job awaits confirmation. Retain correlation and bound transient retries with jitter and vendor limits. For an ambiguous write, prove older claimants cannot send and in-flight work can no longer apply. Then require a current, complete authoritative lookup excluding a prior effect. A vendor fence can supply delivery exclusion and still requires that lookup. Alternatively, require at most one vendor effect for the stable identity in either order, covering all possible arrivals. Otherwise quarantine and prohibit repeat writes. [Local consistency](local-consistency.md) · [Atlassian rate limits](https://developer.atlassian.com/cloud/jira/platform/rate-limiting/)

| Failure window | Proposed handling |
| --- | --- |
| Crash after business commit, before dispatch | Recover pending obligations from durable state. |
| Crash, lease loss or pause after final lease check | A delayed worker may still send and a sent request may still apply. Prevent stale local finalization and keep uncertainty until the remote safety rule is met. |
| Remote applied, local confirmation lost | Confirm by approved correlation or verify the original outcome if the vendor's single-effect contract returns it. |
| Unknown outcome without a safe retry path | Quarantine with owner, age and evidence. An authorized operator can confirm, suppress, perform separately approved compensation, or retry under the same safety rule. |
| Permanent reject, contract drift or credential failure | Isolate the destination, record a safe error class, alert its owner, and recover retained work under the approved replay policy. |

## Inbound contract

Authenticate sender, scope, integrity, targets and mapping. Identify receipt by trusted tenant-scoped event ID or approved source/record/revision identity. Commit receipt and effect or processing obligation before acknowledgment. Compare target, revision and semantic content for a duplicate. A matching receipt adds no effect. Incompatible identity reuse preserves the first record, quarantines/reports the discrepancy and applies no second effect. Guard against older evidence replacing newer evidence using authoritative source ordering or lookup. Treat incomplete notifications as hints and reconcile against approved records with bounded checkpoints and overlap. Use normal authorization and workflow for any approved business action.

## Alternatives and proof

DEC-08 approves the atomic local obligation and independent dispatch. A broker still needs a reliable local handoff. A synchronous external write couples latency and can leave either system ahead. Polling trades callback trust for request volume and freshness delay. Webhooks with reconciliation require correlation and verification.

Test duplicates, order, missed notices, throttling and restart/restore. Pause an old worker after its last lease check, expire the lease, let a replacement see an empty lookup, then resume the old worker. Also let the old write apply before a newer fence and lose its response. Verify no second effect in either order. Revoke a required grant before dispatch authorization and verify no send. Revoke after authorization during transmission and verify the unresolved effect is tracked and reconciled. Compare durable status and remote effect counts, including quarantine for uncertainty. [Q-09](../Phase1-conceptual/quality-acceptance.md#q-09-reliable-integration-delivery) · [Operations](operations-and-recovery.md)
