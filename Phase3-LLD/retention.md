# Payload retention and holds

**Scope** F-04/06, Q-03/11/13, R-07/08/10, DEC-04/05/11/14. D2-R adopts timed protected retention. Mandatory reason validation, complete lifecycle facts and restricted deleted payload remain required. Exact operational access, other terminal-body policy, disposal windows and hold/purge authorities remain open. [Owner selections](owner-design-selections.md) · [HLD history](../Phase2-HLD/workflow-and-history.md)

| Data | Adopted reference treatment |
| --- | --- |
| Body | Retain while the request remains in scope. Delete conceals the body in the same transaction. The deleted body becomes eligible for purge after 30 days absent a verified hold. |
| Reason | Validate before persistence and store separately for Delete/Reject. A reason becomes eligible for purge 90 days after its transition absent a verified hold. Initial history and other public responses withhold reasons. |
| Durable facts | Preserve identity, terminal state, ordered lifecycle, original publication association and distinct receipt policy. |
| Retained protected payload | Pending approval, a separately scoped grant can authorize a restricted view until purge. Mask initial deleted payload/actor/reason as specified. A hold delays eligible purge within its approved scope. |

The 30/90-day values are configurable within owner delegation. The signed comparison records terminal minimization and protected archive as unselected alternatives. [Retention decision](work/reviews/comparison-access.md) · [Receipts](retries.md)

**Ordering.** Apply holds, hold release, retention visibility and purge under the exclusive organization guard. A hold effective first blocks retirement. A durably committed retirement fences that payload version after cleanup failure or restore. Later holds preserve remaining material and report that version unavailable. Commit background purge in the independent hold/purge ledger before physical deletion, then finish deletion idempotently. If commitment is uncertain, suspend affected release and purge pending proof. Govern read/release by the guard and effective decisions. Reapply the ledger before exposing restored data. Record actor/authority, payload version, basis, time and verified cleanup without payload text. [Disclosure](access.md) · [Recovery](recovery.md)

**Evidence, planned.** Force both hold/purge orders, cleanup failure after logical purge and restoration of an older copy with the payload. Verify authorized pre-purge view, zero post-purge exposure or revived replay and identical complete ordered lifecycle facts. [E7](verification.md)
