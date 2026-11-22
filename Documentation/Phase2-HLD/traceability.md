# Requirements and evidence traceability

**Scope** Phase 1 source IDs mapped to design and expected evidence. **Status** DEC-07, DEC-10 and DEC-14 approve the evidence directions. Exact profiles, fixtures and gate settings remain open. [Phase 1 index](../Phase1-conceptual/README.md)

## Assumptions and functional scope

| Source IDs | Design and evidence |
| --- | --- |
| A-01/02/03, F-10 | [Architecture](architecture.md), [shared workflow](workflow-and-history.md), [extension evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| A-04, F-09 | [Local consistency](local-consistency.md), [retries](retries-and-failure-timing.md), [performance and regression evidence](acceptance-evidence.md) |
| A-05 | [Access and approvals](access-and-approvals.md), [Q-13 evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| A-06, F-11 | [Vendor assessment](vendor-assessment.md), [delivery](integration-delivery.md), [extension and connector evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| A-07 | [Operational recovery](operations-and-recovery.md), [risk map](#production-risks) |
| A-08 | [Decision record](decision-proposals.md) and [approval gate](../Phase1-conceptual/phase-2-hld-execution-plan.md#owner-approvals) |
| A-09, A-10, F-01/02/03/04/05/06 | [Lifecycle and history](workflow-and-history.md), [local consistency](local-consistency.md), [regression evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| F-07, F-08 | [REST](rest-and-browsing.md), [browse](browse-contract.md) and [access evidence](acceptance-evidence.md#regression-and-conformance-evidence) |

## Quality criteria

| Source IDs | Design and evidence |
| --- | --- |
| Q-01 | [Performance criteria](acceptance-evidence.md#performance) and [prepared opt-in test plan](performance-test-plan.md) |
| Q-02/03 | [Local consistency](local-consistency.md), [retry contract](retries-and-failure-timing.md), [multicopy evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| Q-04 | [REST validation](rest-and-browsing.md#validation-and-response-behavior), [input tests](acceptance-evidence.md#regression-and-conformance-evidence) |
| Q-05/06 | [Logging and advisory evidence](operations-and-recovery.md#observability-and-security), [policy options](operating-policy-alternatives.md) |
| Q-07/08 | [Architecture](architecture.md), [complexity, coverage and extension evidence](acceptance-evidence.md#regression-and-conformance-evidence), [gate options](operating-policy-alternatives.md) |
| Q-09 | [Vendor boundary and delivery proof](integration-delivery.md#alternatives-and-proof) |
| Q-10 | [Verified restore and rehearsals](operations-and-recovery.md#database-incident-recovery) |
| Q-11 | [Workflow and history](workflow-and-history.md), [transition and history evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| Q-12 | [Browse](browse-contract.md), [authorization and browse evidence](acceptance-evidence.md#regression-and-conformance-evidence) |
| Q-13 | [Access and approval gates](access-and-approvals.md), [access tests](acceptance-evidence.md#regression-and-conformance-evidence) |

## Production risks

| Source ID | Risk, design and verification |
| --- | --- |
| R-01 | [Uncertain local commit and restart](retries-and-failure-timing.md#concurrent-and-failure-sequences) |
| R-02 | [State and ID races](local-consistency.md#race-sequences) |
| R-03 | [Load, dependency pressure and recovery](operations-and-recovery.md#production-fault-evidence) |
| R-04 | [Throttling, remote uncertainty and duplicates](integration-delivery.md) |
| R-05 | [Contract drift, scope and credentials](integration-delivery.md#outbound-contract) |
| R-06 | [Early validation and required reasons](rest-and-browsing.md#validation-and-response-behavior) |
| R-07 | [Log gaps, volume and sensitive fields](operations-and-recovery.md#observability-and-security) |
| R-08 | [Wrong scope, stale membership and revocation races](access-and-approvals.md#authorization-races) |
| R-09 | [Rollout and persisted data compatibility](operations-and-recovery.md#production-fault-evidence) |
| R-10 | [Trusted backup and external effect reconciliation](operations-and-recovery.md#database-incident-recovery) |

DEC-01 through DEC-14 are individually mapped to design, requirements and deferral in [decision record](decision-proposals.md).

[Assumptions](../Phase1-conceptual/assumptions.md) · [Features](../Phase1-conceptual/features.md) · [Quality](../Phase1-conceptual/quality-acceptance.md) · [Risks](../Phase1-conceptual/production-risks.md) · [Decisions](../Phase1-conceptual/decisions.md)
