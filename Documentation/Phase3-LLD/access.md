# Identity, grants and decision context

**Scope** A-05, F-03/08/10, Q-13, R-08, DEC-04/08/11/12. Required zero unauthorized reads or changes. Scoped grants, ordered revocation, masked current disclosure, distinct-person Accept and independent Verify/Publish grants are approved. D2-R selects the local mechanism. Provider and exact corporate mappings remain open. [HLD](../Phase2-HLD/access-and-approvals.md)

`ActorResolver` validates issuer, audience, credential, subject and organization binding. Resolve a stable principal and actor type from the trusted directory. Role and group identifiers are scoped to issuer/organization. An authenticated alias resolves to the existing stable person. Grant rows bind principal/group, organization, type, permission, and any owner, assignment or target predicate. Trusted mapping populates `originatingPersonId` on human Create. Changes to login, delegation, ownership and assignment keep that original person. Initially reject on-behalf-of and service Create until a trusted originating-person mapping is approved. An approved service identity with no resolved human person cannot Accept.

| Permission | Reference rule |
| --- | --- |
| `KEY_ISSUE` | Authenticated principal with current trusted scope proof. Minting a signed key grants no request access. |
| `CREATE` | Type and organization grant. Populate owning scope from registered policy. |
| `ROW_READ`, `FIELD_READ(field)` | Use grants and scope predicates to determine detail and visibility. |
| `FIELD_FILTER(field)` | Requires permission to read the filter field as well. Apply per row. |
| `CONTEXT(action)` | Separate action grant and access to all material facts for that action. |
| `EXECUTE(action)` | Check target scope, required context grants and independent action grant. Accept and Reject require individually scoped approval rights. Accept additionally compares stable person IDs. |
| `HISTORY_READ`, `DELETED_METADATA` | Separate history permission and a separate deleted visibility gate. |
| `RESULT_READ(action)` | Current scope, target, action, applicable context grant and minimum result fields. Create needs no context. Publish requires `FIELD_READ(publicationId)`. Delete requires `DELETED_METADATA` and its minimal receipt fields. |
| Service and operator grants | Explicit sender/tenant/operation scope. Audit operator pause, resume and reconciliation. |

`ActionContextService` issues an eligible actor the authorized action-specific decision fields and opaque observation. `CommandCoordinator` checks current policy, observed revision and all material facts under effect locks. Accept denies the same person even with a grant. Use a fresh observation only for new existing-request writes. [Observations](observations.md) · [Transactions](persistence.md)

**Locally ordered authority.** Every command and visibility-changing write takes the organization `PolicyGuard` exclusively, including Create, Delete, edits, grant/assignment changes, holds and purge. Advance affected context versions. `DisclosureGate` takes it shared in a fresh phase, derives current projections including browse lookahead, and hands off bytes to a bounded outgoing buffer before unlocking. This is release. A prior deletion, purge or revocation governs all fields, tokens and continuation. Earlier release remains valid if bytes arrive later. Conceal deleted context and pre-delete results. Detail gives ordinary 404, or allowed metadata under its distinct grant. Replay errors follow the key contract. Buffer failure leaves receipts recoverable. [Queries](queries.md) · [Retention](retention.md) · [Replay](retries.md)

Before protected release or effect, prove the active writer/reader epoch and effective scope authority. Unproved continuity closes protected detail, browse, history, context, receipt results, writes and new dispatch admission. A prior confirmation alone conveys no current grant. At handoff recheck applicable freshness and concealed-target permission. Network sends happen after handoff outside the lock. Fence old egress on recovery. [Authority proof](authority-continuity.md) · [Recovery](recovery.md)

A verified directory mirror can simplify provisioning. Its evidence must cover signed or trusted source, monotonic revision, gap detection, synchronization watermark, approved maximum staleness and fail-closed outage behavior. A freshness window permits revocation lag. Immediate external revocation needs a proof that directory revocation and local effect/release are ordered. Compare to administering locally ordered grants and treating the local commit as the effective point. Exact roles, person collisions, verified directory outage and service mappings affect these tests. [Decision](decisions.md) · [Access evidence](verification.md)
