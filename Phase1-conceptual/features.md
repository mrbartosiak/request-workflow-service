# Functional scope

## Required interview features

| ID | Feature | Required behavior |
| --- | --- | --- |
| F-01 | Create a request | Use the required inputs and creation transition in [Workflows](workflows.md#interview-lifecycle). |
| F-02 | Edit the body | Allow body changes only under the state rule in [Workflows](workflows.md). |
| F-03 | Verify and accept | Support Verify and Accept according to the transition table in [Workflows](workflows.md). |
| F-04 | Delete and reject | Support Delete and Reject with the mandatory reason and permitted states in [Workflows](workflows.md). |
| F-05 | Publish | Support Publish and assign the publication identifier under the rule in [Workflows](workflows.md). |
| F-08 | REST interface | Expose the included functionality through REST endpoints. The brief specifies a backend service with no GUI requirement. [Interview brief](../tmp/pdfs/source-1.png) |
| F-09 | Database persistence | Persist requests in a database. The brief permits SQL or NoSQL. [Interview brief](../tmp/pdfs/source-1.png) · [Engine decision](decisions.md#dec-03-database-and-initial-vendor-delivery) |

## Owner-approved interview features

| ID | Feature | Required behavior in interview delivery | Provenance |
| --- | --- | --- | --- |
| F-06 | State-change history | Store the complete history of request state changes. | [Brief optional](../tmp/pdfs/source-1.png) · [Owner-approved](decisions.md#dec-01-optional-interview-features) |
| F-07 | Browse requests | List requests with pagination. Default the page size to 10. Support filtering by name and by state. | [Brief optional](../tmp/pdfs/source-1.png) · [Owner-approved](decisions.md#dec-01-optional-interview-features) |

The owner approved a distinct-grant history endpoint with actor/reason masking and authorized, exact browse filters with newest-first live cursors. Exact grant and configuration settings remain open. [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-11](decisions.md#dec-11-history-and-browsing-behavior)

## Enterprise scope

| ID | Area | Scope |
| --- | --- | --- |
| F-10 | Internal workflows | Use the exact interview lifecycle initially across HR, bug reporting, and other request types. Account for user types, roles, and groups that may participate in approvals. Any future variant is subject to the owner's extension constraint. [DEC-02](decisions.md#dec-02-interview-lifecycle-and-corporate-variants) · [Domain model](domain-model.md) |
| F-11 | Integration readiness | Assess all five enterprise tools named in the owner assumptions for business value and ease of integration. Integration priorities and live delivery scope remain open. [Integrations](integrations.md) · [Quality acceptance](quality-acceptance.md) · [DEC-03](decisions.md#dec-03-database-and-initial-vendor-delivery) |
