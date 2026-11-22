# Conceptual domain model

## Required interview concepts

| Concept | Purpose and relationship | Invariant |
| --- | --- | --- |
| Request | The item managed through the lifecycle. It has one name, one body, and one current state. | Creation requires a name and body. State changes and body edits follow the [interview workflow](workflows.md). |
| Lifecycle state | The request’s current position in its workflow. A state can describe many requests at the same time. | Each request has exactly one current state from creation onward. |
| Delete or reject reason | Input attached to a particular Delete or Reject action. | Each successful Delete or Reject requires a supplied reason. Reason validation details and data retention remain open. |
| Publication identifier | Identifies a request when published. Each published request has one publication identifier. | The numeric identifier is assigned at publication and is unique across all published interview requests, including when separate requests are published concurrently. |

DEC-13 uses an opaque request reference at Create and a distinct numeric publication identifier at Publish, with gaps allowed and no ordering guarantee. Namespaces, display and allocator recovery settings remain open. [DEC-13](decisions.md#dec-13-publication-identifier-contract)

Names are immutable. Deleted payload is restricted by default. Detailed input and retention settings remain open. [DEC-05](decisions.md#dec-05-deleted-data-and-retention) · [DEC-06](decisions.md#dec-06-name-editing-and-input-rules)

## Owner-approved history and browsing

Both features are in interview scope. Each request has a complete history starting at Start → `CREATED`, followed by committed changes in order. Browsing uses exact name and current state with newest-first live cursors. The [history](../Phase2-HLD/workflow-and-history.md) and [browse](../Phase2-HLD/browse-contract.md) modules record field access and settings still open under DEC-05 and DEC-11.

## Corporate use and proposed concepts

Initial corporate request types use the shared interview lifecycle. Future corporate variants are deferred. The [corporate workflow rules](workflows.md#corporate-workflows) define the approved constraint on future variants.

| Concept | Purpose | Proposed relationships |
| --- | --- | --- |
| Request type | Classifies a request by business purpose, such as HR ticket, bug report, or internal approval request. | Each corporate request would have one type. One type can classify many requests. |
| Actor | Identifies who initiates an action or records an approval decision. | One actor can take many actions. Each recorded approval decision would identify one responsible actor. |
| Organizational user type | Classifies organizational users. | How user types are represented and whether they affect actor eligibility require owner approval. [DEC-04](decisions.md#dec-04-identity-roles-and-access) |
| Group | Represents a collection of actors used by organizational policy. | An actor can belong to several groups, and a group can have several actors. |
| Role | Describes duties relevant to access or approval. | Policy could grant a role to individual actors, through group membership, or both. Eligibility depends on the applicable policy. |
| Approval policy | Defines which requests require approval, eligible decision makers, and how decisions affect workflow progression. | A policy could apply to many requests. Its scope, conditions, and applicability are open. |
| Approval decision | Records an eligible actor’s decision for a request when approval applies. | Each decision concerns one request. A request could have multiple decisions if the approved policy permits it. |

The owner approved scoped role/group grants, composition and initial approval through the existing actions. Exact role mappings, delegation and quorum remain open. [Decision register](decisions.md) · [Access and approvals](../Phase2-HLD/access-and-approvals.md)

## Integration boundary

An integration may provide business information for a request or receive the outcome of a request action, subject to an approved use case. Resulting actions follow the applicable [access and workflow checks](workflows.md#access-and-workflow-checks). Phase 1 assesses all five named enterprise tools. Integration priorities, external record relationships, provider responsibilities, and delivery scope still require owner approval. See [Integrations](integrations.md) and the [decision register](decisions.md) for DEC-03 and DEC-09.
