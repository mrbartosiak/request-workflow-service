# Phase 2 high-level design · AI clarity

**Status** Phase 2 HLD with the owner's 2026-11-22 design selections. Exact settings remain open where recorded. Phase 3 handoff requires separate owner approval. [Decision record](decision-proposals.md)

The request management backend uses Java 25 and Spring Framework 7.0.11. It exposes a REST interface and persists requests in a shared database. All initial internal request types follow the interview lifecycle. Interview delivery includes complete state-change history and paginated browsing. Database engine, integration priorities, and live vendor commitments are open. [Phase 1 index](../Phase1-conceptual/README.md) · [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Phase 2 plan](../Phase1-conceptual/phase-2-hld-execution-plan.md)

## Read or include by subject

| Module | Governs |
| --- | --- |
| [Architecture](architecture.md) | Component responsibilities, trust boundaries, shared core, extension and deployment alternatives |
| [Workflow and history](workflow-and-history.md) | Exact interview transitions, required inputs, history and numeric identifiers |
| [REST contract](rest-and-browsing.md) | Operations, observed revisions, early validation and responses |
| [Browse contract](browse-contract.md) | Filter semantics, field permissions and live cursor paging |
| [Access and approvals](access-and-approvals.md) | Authorization coverage, freshness, disclosure and corporate approvals |
| [Local consistency](local-consistency.md) | Atomic effects, request and ID concurrency, required database capabilities |
| [Retries and failure timing](retries-and-failure-timing.md) | Durable operation identity, replay races, authorization and uncertain commits |
| [Integration delivery](integration-delivery.md) | Durable remote work, inbound acceptance, safe retries and reconciliation |
| [Vendor assessment](vendor-assessment.md) | Workday, Jira, ShiftLeft, SonarQube and Sonatype fit and delivery conditions |
| [Acceptance evidence](acceptance-evidence.md) | Functional, performance, access, concurrency and changeability verification |
| [Performance test plan](performance-test-plan.md) | Prepared procedure and report template for a future opt-in run |
| [Operations and recovery](operations-and-recovery.md) | Logging, advisories, failure handling, restore and delivery safety |
| [Operating policy alternatives](operating-policy-alternatives.md) | DEC-14 choices for log failures, advisory gates, complexity and coverage |
| [Decision record](decision-proposals.md) | Owner selections, rationale and deferrals |
| [Product follow-ups](product-follow-ups.md) | Unresolved product, access, API and integration settings |
| [Operating follow-ups](operational-follow-ups.md) | Unresolved evidence and operating settings and handoff |
| [Traceability](traceability.md) | Mapping from Phase 1 source IDs to design and expected evidence |

Each module states its scope and status. **Required** identifies a source obligation or prior approved acceptance target. **Approved direction** records a 2026-11-22 selection. **Proposed** and **open** identify settings requiring approval. The brief governs interview behavior and the owner assumptions add corporate scope. [Interview brief, page 1](../tmp/pdfs/source-1.png) · [Interview brief, page 2](../tmp/pdfs/source-2.png) · [Phase 1 decisions](../Phase1-conceptual/decisions.md)

## Review provenance

The HLD builds on the signed-off C1-r2 and C2-R3 candidates and reviewed OC-r2 alternatives. The decision record governs the resulting selections and follow-ups. [Candidate 1 signoff](../tmp/phase2-hld-execution/review-1/candidate-review.md) · [Candidate 2 signoff](../tmp/phase2-hld-execution/review-2/candidate-signoff.md) · [Reviewed owner comparison](../tmp/phase2-hld-execution/owner-comparison/owner-comparison.md) · [Comparison signoff](../tmp/phase2-hld-execution/review-1/comparison-review.md)

The human-readable HLD is outside this authorized AI-edition run. Phase 3 requires separate owner approval and explicit acceptance of any deferrals affecting low-level design. The later interview submission must include documented assumptions, a root application README, and source code in a public GitHub or Bitbucket repository. Deliver the source package without a database, compiled classes, dependency binaries, or a bundled server. [Phase obligations](../Phase1-conceptual/assumptions.md#phase-obligations) · [Submission requirements](../Phase1-conceptual/assumptions.md#final-interview-submission)
