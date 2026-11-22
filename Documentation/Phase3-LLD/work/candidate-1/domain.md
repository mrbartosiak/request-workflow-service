# Domain and workflow

**Scope** A-01/02/03/05/09/10, F-01–06/10, Q-03/08/11, DEC-01/02/05/06/10/11/12/13. **Required** Exactly this shared lifecycle applies to HR tickets, bug reports and other initial internal requests. Java names and effect records below are proposed. [Brief](../../../tmp/pdfs/source-1.png) · [Diagram](../../../tmp/pdfs/source-2.png) · [Owner identifiers](../../owner-identifier-requirements.md)

| Current | Action | Next | Input and additional effect |
| --- | --- | --- | --- |
| Start | Create | `CREATED` | Name and body. Assign an immutable opaque reference and record originating requester. |
| `CREATED` | Edit | `CREATED` | Replacement body. Edit only the body. |
| `CREATED` | Delete | `DELETED` | Nonblank reason. Apply deleted-data policy. |
| `CREATED` | Verify | `VERIFIED` | Independent Verify grant and applicable type checks. |
| `VERIFIED` | Edit | `VERIFIED` | Replacement body. Edit only the body. |
| `VERIFIED` | Accept | `ACCEPTED` | Scoped grant and a person distinct from originating requester. |
| `VERIFIED` | Reject | `REJECTED` | Scoped grant and nonblank reason. |
| `ACCEPTED` | Reject | `REJECTED` | Scoped grant and nonblank reason. |
| `ACCEPTED` | Publish | `PUBLISHED` | Independent Publish grant and one increasing numeric ID. |

`Lifecycle` maps each listed pair to its effect. Every other pair returns `IllegalState` on an authorized, current new intent. DELETED, REJECTED and PUBLISHED are terminal. Replays resolve through receipts outside the lifecycle. Initial types share all states and transitions. Future variants need owner approval and may add behavior while retaining every common state. [Authorization](access.md) · [Replay](retries.md)

`Request` holds `RequestRef`, type, organization, immutable name, current state, revision, creation order, originating `PersonId`, and optional `PublicationId`. Ownership/assignment are independently versioned facts. `RequestBody` and `TransitionReason` are separately protected payloads. The immutable name can be withheld by retention policy. No state effect uses a null body as an edit or missing reason as a transition. Equal-body edits advance the revision once per distinct committed intent. [REST](rest.md) · [Records](persistence.md)

`TransitionFact` captures the per-request index, action, from and to state, recorded instant and protected actor reference. Creation has index 1 and `from=null`. Every committed state transition increments the index. Body edits change only the body and revision. Failed attempts and replays add no history fact. `WorkflowPolicy` may require additional permitted decision facts or deny an action. Its output is a decision and optional approved routing intent. The command coordinator applies lifecycle and authorization, owns commit and creates any delivery obligation. [Assembly](wiring.md) · [History](queries.md) · [Evidence](verification.md)
