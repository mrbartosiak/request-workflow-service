# REST operations and revision contract

**Scope** REST operations, observed revisions and external responses. **Status** DEC-06, DEC-08, DEC-11 and DEC-13 approve the validation, revision, history and identifier directions. Exact paths, wire fields, errors and access mappings remain proposed. [Brief](../tmp/pdfs/source-1.png) · [Features](../Phase1-conceptual/features.md) · [Follow-ups](product-follow-ups.md)

## Proposed unversioned operations

| Operation | Purpose and success |
| --- | --- |
| `POST /requests` | Create with name and body. Return 201, an opaque request reference, Location and an authorized result. Type selection needs approval. |
| `GET /requests/{ref}` | Return 200 with permitted detail fields and the current revision when authorized. A publication ID is available only after Publish and subject to access. |
| `GET /requests/{ref}/action-context?action={action}` | Proposed scoped read for an existing-request action. Return 200 with the current revision and the permitted decision context tied to that revision. Requires a distinct grant and access to required fields. |
| `PATCH /requests/{ref}/body` | Update the body in an eligible state using `expectedRevision`. Return 200 and authorized outcome. |
| `POST /requests/{ref}/actions/{action}` | Invoke `verify`, `accept`, `reject`, `delete` or `publish` with `expectedRevision`. Return 200 and authorized outcome. Reject and Delete require a reason. Authorized Publish outcomes carry the numeric publication ID. |
| `GET /requests` | Return 200 and a page of authorized requests. |
| `GET /requests/{ref}/history` | Initial endpoint with its own grant. Return 200 with an authorized, masked sequence. Actor and reason remain withheld until exact grants are approved. |

Delete is a reason-bearing workflow command. Named actions constrain state writes and accept action-specific inputs. One transition resource with a typed payload offers fewer routes and a larger shared request contract. [Workflow](workflow-and-history.md)

## Observed revision contract

Every mutation carries a scoped caller key. Every new existing-request write submits an observed revision for its action and target. The proposed wire field is `expectedRevision`. The observation binds the request revision and material context versions. Eligible actors can obtain an authorized minimal context read, including for Edit. Detail, Create and mutation results can supply a usable token only with the required authorized context. The token conveys no permissions. At effect time, check versions, authorization and state together. Deny or defer when context or version proof is unavailable. Exact fields and format remain open. [Access](access-and-approvals.md) · [Local consistency](local-consistency.md)

An exact authorized replay submits the original precondition and returns the original result revision when permitted. This historical revision conveys no current precondition. Before a new command after a replay or revision conflict, fetch current authorized context and reassess intent. Recovery must prevent a previously issued token from matching divergent restored content. [Retry contract](retries-and-failure-timing.md) · [Recovery](operations-and-recovery.md#database-incident-recovery)

## Validation and response behavior

Authenticate and validate at the REST boundary. Require nonblank Create name/body and Delete/Reject reason. Names are immutable. Only body editing is available initially. Reject unsupported input and confirmed storage violations before attempting mutation. Preserve supported punctuation and multilingual text. Proposed checks cover media, bounded content, control fields and path/query values. Exact size, encoding and storage rules remain open. Check state and policy at effect time. [Local consistency](local-consistency.md)

Use safe categories and correlation. Propose 400 for malformed or missing input, 415 for unsupported media, 401 for missing/invalid credentials, 404 for missing or concealed targets, and 403 for denied action on a disclosable target. Use distinct 409 categories for illegal state, stale revision, key conflict and known expired outcome. Use 429 for throttling and 503 for unavailable authority or uncertain commit. For uncertainty keep the same logical key. Apply authentication, input validation and scoped access before replay or eligibility disclosure. [Replay](retries-and-failure-timing.md)

## Browsing

Browse by name and state with default size 10. The [browse contract](browse-contract.md) records approved matching, field authorization and paging, plus pending wire settings.
