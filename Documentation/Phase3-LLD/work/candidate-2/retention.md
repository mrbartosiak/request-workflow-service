# Retention and lifecycle preservation

**Scope** F-04/06, Q-02/03/11/13, DEC-04/05/08/11/14. **Status** Keep lifecycle facts and restrict deleted payload. Values below are delegated proposals. Access, holds and material policy changes need explicit approval. [HLD data direction](../../../Phase2-HLD/workflow-and-history.md#approved-representation-and-open-settings)

| Data | Proposed rule |
| --- | --- |
| Header and LifecycleChange core fields | Preserve ref, type, tenant, state, ordinal and from/to permanently in the operational record or compatible durable archive. Keep terminal markers and applicable stable mappings. |
| Body | Retain while request is live. Delete makes body invisible to normal readers in the committing transaction. After 30 days from Delete purge body absent a verified hold. Explicit retention rules for other terminal bodies remain open. |
| Reason | Validate at entry and store in a separate protected reason row for Delete/Reject. Purge 180 days after recorded transition absent a verified hold. Initial API never returns reasons. |
| Actor and identity | Store protected stable actor link per history change and immutable originating person binding. Actor history disclosure remains withheld. Exact archival access and legal basis remain open. |
| Replay | Replay policy is valid during the key's 7-day lifetime. Purge digest/receipt after verified expiry plus a proposed 1-day clock/maintenance margin and any authorized evidence hold. A hold does not extend replay visibility or ability to act. |

`RetentionService` reads a bounded set of expiry candidates and takes the same exclusive scope gate and request locks as other security-affecting changes. Recheck application clock proof, expiry and authoritative hold version before changing data. Purge body/reason rows only after lifecycle row/terminal invariants hold and leave a purge fact with kind, ref, policy version and time. Purge or archive a receipt only after trusted key expiry proof, admission windows and unresolved transactions have cleared. The durable hold/redaction ledger records scope, bounds, policy version and operator authority. Restore applies ledger facts before exposing protected data. Missing proof keeps affected access/purge fenced. Restrict backup access and separately approve disposal windows. A restore cannot make purged sensitive data available to ordinary reads or replay. [Retries](retries.md) · [Recovery](recovery.md)

**Alternative** Archive encrypted payload behind independently controlled keys with verified erasure and a separate legal-hold process. This improves isolation and costs key-management and restore coordination. Retired-key markers support client-generated keys but grow with historical commands unless shortening their protection is provably safe. Signed expiring keys bound receipt storage and add a client hop and key/clock governance. [Retry choices](retries.md)

**Evidence** Before/after purge, verify unchanged history, mask and terminal marker, no old body/reason copied into a replay or observable cursor, and no re-execution with expired or restored fenced keys. Restore a backup containing a purged body and apply the current redaction ledger before a detail or browse release. [Acceptance](acceptance.md)
