# Enterprise integration candidates

Phase 1 assesses fit and ease of integration for all five tools named in the corporate assumptions. Vendor documentation establishes product capabilities. Each use case, direction, data set and deployment condition below is a proposal for the corporate environment. The owner deferred database selection and integration priorities. Live vendor delivery scope, exact products, directions, systems of record and ownership remain open. [DEC-03](decisions.md#dec-03-database-and-initial-vendor-delivery) · [DEC-09](decisions.md#dec-09-vendor-identities-and-commitments)

## Workday

- **Business value.** For HR requests, use approved workforce context to suggest the right HR queue or approval group. Evaluate routing accuracy and the number of manual reassignments against the existing process.
- **Proposed flow.** Workday → request system. Read a stable worker reference and an approved organization or routing attribute. A request to Workday would be a lookup or synchronization operation. Restrict processing to the minimum data authorised for routing. Identity, authorization and any employee data retention require approval.
- **Deployment conditions.** Confirm the Workday tenant, product entitlement, exact API or report, integration identity, permissions, network path, sandbox and acceptable freshness. Approve what staff should see and do when worker data is missing, stale or unavailable.
- **Delivery status.** Read-side candidate. Any Workday write-back requires a separate decision. Workday documents REST services for Workday data transactions and integration. [Workday REST APIs](https://developer.workday.com/documentation/GUID-86d66a6b-5d1a-40e4-af1b-ba4859f5df5a-enHYPHENus/WorkdayRESTAPIs)

## Atlassian Jira

- **Business value.** Hand eligible bug requests to an engineering team while preserving a traceable link for support. Evaluate the number of manual handoffs, orphaned links and duplicate issues.
- **Proposed flow.** Request system → Jira for an approved issue handoff using a safe title or summary and an approved correlation reference. Store the Jira issue reference. Jira → request system is a separate candidate for limited status visibility. Approve the field and state mapping and whose state governs the request lifecycle.
- **Deployment conditions.** Confirm Cloud or Data Center, exact Jira product and instance/version, permitted projects and issue types, credentials and scope, custom fields, network access, sandbox and supported correlation and change-notification methods. Review applicable vendor limits and delivery behavior before enabling callbacks or retries.
- **Delivery status.** Outbound handoff and inbound status are separate candidates. Jira Cloud documents issue APIs and webhooks, and Jira Data Center has a separate API contract. [Jira Cloud issue API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/) · [Jira Cloud webhooks](https://developer.atlassian.com/cloud/jira/platform/webhooks/) · [Jira Data Center REST API examples](https://developer.atlassian.com/server/jira/platform/jira-rest-api-examples/)

## ShiftLeft

- **Business value.** If this is the corporate code security tool, attach repeatable scan evidence to a remediation or exception request. Evaluate time to link evidence and rate of duplicate remediation requests from repeat scans. Confirm overlap with Sonatype and which system owns which evidence.
- **Proposed flow.** Confirmed tool → request system. Import an approved finding or scan reference, project and revision context, assessment time and a minimal status or severity result. Use the tool's approved result interface for the actual deployment.
- **Deployment conditions.** Resolve the corporate identity. Harness identifies ShiftLeft, Inc. as the former name of Qwiet AI and markets Harness SAST and SCA as its successor offering. Confirm which tenant and edition is in use, where results can be retrieved, which identifiers remain stable across scans, and what data can leave the scanning environment.
- **Delivery status.** Conditional candidate pending identity and interface verification. [Harness acquisition announcement](https://www.harness.io/press-and-news/harness-strengthens-its-application-security-business-with-acquisition-of-qwiet-ai) · [Harness SAST and SCA announcement](https://www.harness.io/blog/qwiet-ai-is-now-harness-sast-and-sca)

## SonarQube

- **Business value.** Attach a quality gate result to an engineering review or release request so reviewers can locate the analysis for the relevant revision. Evaluate the time to verify results and the rate of stale or mismatched evidence.
- **Proposed flow.** SonarQube → request system. Associate the project, revision or other verified analysis identifier, analysis time and quality gate outcome with an eligible request. Keep any workflow action that uses the outcome subject to the decision on approval rules.
- **Deployment conditions.** Confirm Server or Cloud, version or edition, project and revision mapping, callback authentication and reachability, an authorised lookup path, and freshness and supersession rules. SonarQube Server documents callbacks on analysis completion and on certain quality gate changes.
- **Delivery status.** Inbound evidence candidate. [SonarQube Server webhooks](https://docs.sonarsource.com/sonarqube-server/discovering/integrations/webhooks)

## Sonatype

- **Business value.** If the corporate product is Sonatype Lifecycle, link policy results to dependency remediation or exception requests. Evaluate the time to confirm policy status and the number of missing or stale assessment links. If the product is Nexus Repository, a distinct read-side candidate could verify an artifact reference for a promotion request.
- **Proposed flow.** Confirmed product → request system. Lifecycle could supply component coordinates or application/report references, assessment time and approved policy status. Nexus Repository could supply repository and component or asset metadata. Treat these use cases as separate candidates with separate authorities.
- **Deployment conditions.** Confirm product, version and tenant, licensed interfaces, project or repository mapping, scoped permissions, network path and sandbox. Sonatype documents that Lifecycle sends no webhook reattempt after a failed delivery. If reliable evidence is needed, verify and approve a reconciliation path against a vendor record such as a report or audit entry for the chosen event type.
- **Delivery status.** Conditional candidate pending product identification. [Sonatype Lifecycle webhooks](https://help.sonatype.com/en/lifecycle-webhooks.html) · [Sonatype Lifecycle report APIs](https://help.sonatype.com/en/report-rest-api.html) · [Nexus Repository search API](https://help.sonatype.com/en/search-api.html)

## Common integration lifecycle

Initial types use the exact interview lifecycle. Any future variant may only add to it and must retain all existing states. DEC-08 approves the common delivery invariants and DEC-10 approves composed connectors in one service deployment. Vendor-specific triggers, data, proofs and operating authority remain open under DEC-09 and DEC-14. [DEC-02](decisions.md#dec-02-interview-lifecycle-and-corporate-variants) · [Delivery contract](../Phase2-HLD/integration-delivery.md)

1. Identify the approved source, destination, operation and correlated business request. Validate the contract, trust, scope and approved data before applying inbound information or attempting delivery.
2. Keep business state and delivery or evidence status distinguishable. For an approved outbound effect, establish a durable obligation tied to the triggering business change. For inbound evidence, associate it with the correct authority, target and revision. Verify how duplicates and stale or out-of-order updates affect each operation.
3. Record attempts and outcomes, including whether the outcome is confirmed, retryable, permanently failed or unknown. Establish retry eligibility and limits from the approved policy and actual vendor contract. A missing confirmation can coexist with a successful remote effect. Use a confirmed vendor capability or reconciliation before repeating an ambiguous operation. [AWS Builders' Library on idempotent retries](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)
4. Recover pending work after interruption. Surface stuck or unresolved work with a correlation reference and approved follow-up path. Document replay and reconciliation evidence and the authority to initiate them. See [Q-09](quality-acceptance.md#q-09-reliable-integration-delivery) and [production risks](production-risks.md).

## Shared core options

The shared behavior to evaluate includes workflow invariants, validation, outcomes and the common integration lifecycle. Use-case-specific behavior can include approval policy and field rules. Vendor-specific behavior can include credentials, payloads and correlation capabilities. The options describe trade-offs for DEC-10.

| Option | Potential advantage | Cost or test implication |
| --- | --- | --- |
| Interfaces and base implementation classes | Gives implementations a common contract and can centralize an invariant lifecycle where every use case follows the same sequence. | Inheritance binds implementations to base class behavior. Check whether a vendor-specific lifecycle can fit without hidden overrides, bypassing required steps or incidental changes to other implementations. |
| Interfaces with composed behavior | Keeps workflow or delivery rules in reusable components and lets implementations choose policies and mappings at explicit boundaries. | More components may require explicit assembly and contract tests. Verify that required steps occur in every supported configuration. |
| Shared core as a dependency of each use-case implementation | Allows independent product teams or release schedules to reuse and test common behavior. This packaging choice can be used with either behavior option. | Increases version compatibility, upgrade and support work. Check that consumers can adopt fixes and evolve independently while conformance stays measurable. Compare with a shared core within one release. |

DEC-10 selects composed behavior and one modular release. Add a test approval policy, replace a fake connector, rerun lifecycle checks and report changed modules, contracts and deployments. [Q-08](quality-acceptance.md#q-08-changeability-and-integration-ease) · [Architecture](../Phase2-HLD/architecture.md)
