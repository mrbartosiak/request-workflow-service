# Domain and workflow

**Scope** All initial types, transitions and state-change facts. **Status** Required under F-01–06/10, Q-03/11 and DEC-01/02/05/06/12/13. Stable class names and type codes below are proposed. [Brief](../../../tmp/pdfs/source-1.png) · [Diagram](../../../tmp/pdfs/source-2.png) · [HLD workflow](../../../Phase2-HLD/workflow-and-history.md)

| Current state | Command | Result | Input or condition |
| --- | --- | --- | --- |
| Start | Create | CREATED | Name and body, approved type and scope. Bind originating person. |
| CREATED | Edit | CREATED | Replace body only. |
| CREATED | Delete | DELETED | Nonblank reason. |
| CREATED | Verify | VERIFIED | Scoped Verify grant. |
| VERIFIED | Edit | VERIFIED | Replace body only. |
| VERIFIED | Accept | ACCEPTED | Scoped Accept grant, different verified person from originator. |
| VERIFIED | Reject | REJECTED | Scoped Reject grant, nonblank reason. |
| ACCEPTED | Reject | REJECTED | Scoped Reject grant, nonblank reason. |
| ACCEPTED | Publish | PUBLISHED | Scoped Publish grant and durable publication allocation. |

DELETED, REJECTED and PUBLISHED are terminal. Every other new state/command pair returns ILLEGAL_STATE after authorization and observation checks. Any new eligible Edit advances the revision, including an identical replacement. Edit leaves state and history sequence unchanged. Retry recognition precedes new-action checks. [Transactions](transactions.md) · [Retries](retries.md)

`Request` is a domain record containing `RequestRef`, `RequestType`, organization, immutable name, immutable originator key, ownership/assignment reference, `RequestState`, revision, state-change counter and optional `PublicationId`. Sensitive body and reasons have separate records. Proposed codes are `HR`, `BUG` and `OTHER`. `WorkflowRules.evaluate` takes the current state and a typed `RequestCommand` and returns an allowed next state or a rejection. `Create`, `EditBody`, `Verify`, `Accept`, `Reject`, `Delete`, `Publish` are typed commands. `CommandService` holds authorization and atomic sequencing. `RequestTypePolicy` examines verified local decision facts and can deny a legal action. It cannot authorize, introduce new transitions, write storage, switch states or perform network I/O. [Records](records.md) · [Spring wiring](spring.md)

`LifecycleChange` is appended once on each committed state change, with per-request contiguous ordinal. Creation is ordinal 1 with previous state absent. Subsequent state changes append the prior and resulting state. Neither failure nor replay appends history. Timestamps are descriptive and may tie. Lifecycle facts and terminal markers outlive payload retention. Future owner-approved variants may only add behavior while preserving existing states. Published identity remains attached if a future re-publication variant is approved. [History](browse-history.md) · [Identifiers](identifiers.md)
