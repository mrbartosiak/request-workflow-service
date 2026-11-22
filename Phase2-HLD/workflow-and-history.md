# Workflow, history and identifiers

**Scope** Required lifecycle and approved history, retention and identifier directions. **Status** DEC-01/02 govern core behavior. DEC-05/06/11/12/13 govern approved additions and exact settings still open. [Brief](../tmp/pdfs/source-1.png) · [Diagram](../tmp/pdfs/source-2.png) · [Follow-ups](product-follow-ups.md)

## Required lifecycle

Apply this table to every initial request type, including HR tickets, bug reports and other internal requests.

| From | Action | To | Condition or effect |
| --- | --- | --- | --- |
| Start | Create | `CREATED` | Name and body required. |
| `CREATED` | Delete | `DELETED` | Reason required. |
| `CREATED` | Verify | `VERIFIED` | Applicable authorization and workflow checks pass. |
| `VERIFIED` | Accept | `ACCEPTED` | Another authorized person accepts. Self-Accept is denied. |
| `VERIFIED` | Reject | `REJECTED` | Reason required. |
| `ACCEPTED` | Reject | `REJECTED` | Reason required. |
| `ACCEPTED` | Publish | `PUBLISHED` | Assign a unique numeric publication ID in the successful publication. |

An edit changes the body in `CREATED` or `VERIFIED` and preserves state. Enforce the edit rule at the point of effect. `DELETED`, `REJECTED`, and `PUBLISHED` are terminal. A new action outside the table or the edit rule fails without a state change. Recognized replays of committed actions follow the replay contract. Corporate policy may govern who acts or what evidence is required. Any future corporate variant is deferred, must be additive, and retains all existing states. [Access and approvals](access-and-approvals.md) · [Retries](retries-and-failure-timing.md)

## Required history and publication invariants

Persist the complete ordered sequence of actual state changes with one record per change. Failed attempts, state-preserving edits, and recognized replays produce zero state-change records. Keep current state and new history consistent across instances, failures, and recovery. Every published interview request has one numeric ID distinct across all initial types, even when two requests publish concurrently. The successful publication and its ID become visible together. A disclosed successful Publish replay carries the original ID. [Local consistency](local-consistency.md) · [Features](../Phase1-conceptual/features.md#owner-approved-interview-features)

## Approved representation and open settings

- **Creation and history.** Start with Start → `CREATED`, then every committed state change in per-request order even when clocks tie. The initial REST endpoint has a distinct grant and masks fields. Withhold actor and reason from responses until their exact grants are approved. Exact other metadata and wire format remain open. History needs no copy of the request body. [REST history](rest-and-browsing.md)
- **Deletion.** Keep complete ordered lifecycle facts and a terminal marker. Restrict deleted payload by default. Set separate retention/redaction policy for body, reason and replay. Require Delete/Reject reasons regardless of retention. Exact periods, holds, purge and recovery handling remain open. [Replay retention](retries-and-failure-timing.md)
- **Identity.** Use a stable opaque request reference from Create and a separate globally unique numeric publication ID at successful Publish. Numeric publication IDs may have gaps and carry no chronological order guarantee. Formats, any namespace and recovery proof remain open.
- **Name and reason.** The name is immutable. Mandatory text is nonblank and validated at the REST boundary. DEC-06 governs exact field limits and formats. [Validation](rest-and-browsing.md#validation-and-response-behavior)

[Decision proposals](decision-proposals.md) · [Domain model](../Phase1-conceptual/domain-model.md)
