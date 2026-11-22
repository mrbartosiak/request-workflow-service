# Local consistency and concurrency

**Scope** Database authority, atomic effects and state races. **Status** Durability, idempotency, legal concurrent outcomes, history and numeric uniqueness are required. DEC-08/11/13 approve the business invariants. The engine is deferred and concurrency mechanisms remain proposed. [Quality acceptance](../Phase1-conceptual/quality-acceptance.md) · [Engine decision](../Phase1-conceptual/decisions.md#dec-03-database-and-initial-vendor-delivery)

## Invariants and commit boundary

The shared durable database arbitrates writes across copies of the service. All contenders, including callbacks approved to trigger actions, share that authority. The transaction or equivalent atomic durable operation publishes these effects together when applicable.

| Business command | Effects committed together |
| --- | --- |
| Create | Complete new request with mandatory name and body, stable reference and `CREATED`, plus creation history and operation receipt. For ordinary human Create, bind the trusted originating requester person identity in the same commit. This protected binding is independent of mutable ownership, assignment and login aliases. |
| Body edit | New body, request revision and operation outcome. No state-change entry. |
| Verify or Accept | State, request revision, one history entry, and operation outcome. |
| Delete or Reject | Terminal state, request revision, one history entry, required reason according to retention policy, and operation outcome. |
| Publish | State, request revision, unique numeric publication ID association, one history entry, and operation outcome. |
| Any trigger for approved outbound work or linked approval evidence | Add the delivery obligation and/or required approval record to the same appropriate commit. |

Failure before commit leaves no durable business effect or eligible dispatch. DEC-13 allows numeric gaps. An allocator may reserve numbers internally. Assign a unique ID to one request only at successful Publish. Acknowledge after known durable commit and use authoritative or suitably consistent reads. Stale projections cannot govern workflow, replay or access.

## Race sequences

| At different instances | Result at the shared commit boundary |
| --- | --- |
| Publish vs Reject from `ACCEPTED` | At most one transitions. The other conflicts or sees the terminal state and fails. Preserve one corresponding history entry and any resulting ID or work. |
| Edit vs Accept from `VERIFIED` | Commands based on the same revision cannot both win. After Edit wins, a newly authorized Accept can follow using the observed edited revision. Accept first closes editing. |
| Verify vs Delete from `CREATED` | The winning action changes state. The other action is now ineligible. |
| Two Publishes on distinct requests | Both may commit with distinct numeric IDs even when allocators or instances race. |
| Two edits with the same caller-supplied revision | With distinct keys, one edit commits and advances the revision. The other conflicts. [Revision contract](rest-and-browsing.md#observed-revision-contract) |

## Alternatives and engine requirements

Compare database-enforced conditional writes with transactional locking. Conditional writes can create contention conflicts. Locking adds waits and potential deadlocks. Verify observed revisions and context versions at the consistency boundary with policy, workflow and mutation. Accept compares the current stable person identity with the durable originating requester at effect time. Reject stale or unverifiable observations. Bounded transient retries keep the original key and preconditions. [Revision contract](rest-and-browsing.md#observed-revision-contract) · [Access](access-and-approvals.md#approval-decisions)

Compare an authoritative transactional request model with event-sourced state. An event log can reconstruct history but adds projection consistency, schema evolution and replay/security complexity. Recommend atomic state plus ordered history for the initial workflows. A database candidate must prove atomic effects and reconciliation identities, durable known commits, cross-instance uniqueness, conditional or serial access, browse with scoped filters, and recoverability. A document database is viable only when its actual transaction and global uniqueness model meets the same invariants. An ordinary cache or per-instance mutex cannot meet database-level authority. PostgreSQL is an unselected candidate identified for later retry testing. [Retries](retries-and-failure-timing.md) · [Decision proposals](decision-proposals.md)
