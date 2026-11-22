# Access control and approvals

**Scope** Access and approvals for all initial types. **Status** DEC-04 and DEC-12 approve the access and approval directions below. Zero unauthorized reads or changes in approved scenarios remains required. Exact grants, mappings, context and freshness settings remain open. [Q-13](../Phase1-conceptual/quality-acceptance.md#q-13-access-isolation) · [Follow-ups](product-follow-ups.md)

## Approved authorization direction

Authenticate a stable organizational actor or scoped service identity. Derive actor type, roles, group membership and scope from a trusted authority. Treat payload actor, group and scope as untrusted. Require a specific grant for access and fail closed on absent, stale beyond approved tolerances, or unavailable authority. Scope each action to organization, request type, target, and ownership/assignment as applicable. Validate target identity in that scope before processing any mutation.

| Surface | Decision at the point of use |
| --- | --- |
| Create | Actor may create that request type and assign approved scope. |
| Direct read and browse | Row and field permissions. Apply filter-field authorization before matching, pagination and counts. |
| Action context | Explicitly scoped read grant for the relevant action. The minimal view binds permitted decision context to its observed revision. Deny the action if required context is unavailable to the actor. |
| Edit, Verify, Accept, Reject, Delete, Publish | Independently check operation eligibility, target constraints, role/group or ownership, and current workflow state. A general approver grant conveys no automatic publish or history permission. |
| History | Require its own grant for the masked history endpoint. Withhold actor and reason fields until their exact grants are approved. |
| Exact retries and key conflicts | Verify current access to original operation, target and response fields before returning information about any prior result. Denied outcomes disclose neither existence nor contents. |
| Vendor, delivery and operators | Verify sender and scope. Apply dedicated service identities and grants. Require audited operator grants for manual reconciliation, replay, delivery pause and resume. |

Scope grants by ownership, assignment and request boundaries. Separate sensitive-field, action-context and service grants. The exact requester and staff permission matrix remains open. Bind material facts, including independently versioned assignments, approvals or vendor evidence, to the observation precondition. Check versions at the database boundary. Deny or defer a dependent action when an independent authority cannot prove agreement. A token grants no protected-field access. Check current grants independently. [REST context contract](rest-and-browsing.md#observed-revision-contract)

## Authorization races

Order local grants with database effects. Re-evaluate changed policy, ownership or assignment before commit. Earlier revocation denies the change. Recheck permission immediately before protected disclosure, including after waits. Revoked required context suppresses protected fields and usable token. Revocation after commit can suppress the result while preserving effects. A released response can arrive later. A directory mirror needs verified freshness and denial if freshness is unproven. Immediate external revocation needs a proven ordering mechanism. Approve the effective point, freshness and outage behavior. [Retry timing](retries-and-failure-timing.md)

## Approval decisions

Gate Accept and Reject with scoped grants and the exact lifecycle. At effect time Accept compares the actor's stable person identity against the durable originating requester, regardless of login aliases, reassignment or ownership change. Self-Accept fails even with a grant. Deny if the distinct people cannot be established, including unresolved on-behalf-of or service mappings. Authorize Verify and Publish independently of Accept/Reject and of each other. Further duty rules, delegation, quorum and separate records remain open. Any later evidence follows the core lifecycle and commits with its effect where required. [DEC-12](../Phase1-conceptual/decisions.md#dec-12-approval-policy) · [Persistence](local-consistency.md)
