# Phase 1 conceptual specification

This AI-optimized edition describes a company-internal request management backend for approval-dependent work such as HR ticketing and organization-wide bug handling. It records the required interview behavior, the owner's enterprise assumptions, testable quality criteria, and choices awaiting owner approval.

Interview delivery includes complete state-change history and paginated browsing. Initial corporate request types share the interview lifecycle. Database selection, integration priorities, and live vendor delivery remain open. [Decisions](decisions.md)

## Specification files

| File | Authority |
| --- | --- |
| [Assumptions](assumptions.md) | Product scope, technology assumptions, phase obligations, and final submission requirements. |
| [Features](features.md) | Interview requirements, owner-approved additional features, and enterprise scope. |
| [Workflows](workflows.md) | Interview states, legal transitions, and action rules. |
| [Domain model](domain-model.md) | Conceptual entities, relationships, and boundaries. |
| [Quality acceptance](quality-acceptance.md) | Quality requirements and the evidence needed to demonstrate them. |
| [Integrations](integrations.md) | Business value and readiness for each enterprise integration. |
| [Production risks](production-risks.md) | Failure modes and the expected resilience outcomes. |
| [Decisions](decisions.md) | Owner decision questions, status, and recorded outcomes when available. |

## Source precedence and status

1. The interview brief defines the mandatory submission baseline and identifies optional interview features. The state diagram governs interview lifecycle behavior. [Interview brief, page 1](../tmp/pdfs/source-1.png) · [Interview brief, page 2](../tmp/pdfs/source-2.png)
2. The owner's assumptions add enterprise requirements and phase obligations. Apply these alongside the interview baseline. If requirements appear to conflict, record the conflict in the decision register for the owner to resolve. [Owner assumptions](../output/Assumptions-initial-HumanMade.txt)
3. A **source requirement** comes from the brief or the owner. **Owner-approved** identifies a decision recorded in the register, including the commitment to history and browsing from the brief's optional feature list. A **pending proposal** awaits owner approval. Treat earlier generated proposals as background. [Approved features](features.md#owner-approved-interview-features) · [Decision register](decisions.md)

The interview-brief images and owner notes are workspace provenance. The final interview submission calls for a separate repository-root `README.md` and a public repository link at delivery. [Submission requirements](assumptions.md#final-interview-submission)
