# Phase 3 owner approvals

## Before launching agents

Ask the owner to approve the Phase 2 HLD handoff, two independent full-scope executors with two dedicated reviewers, `xhigh` as very high, and the AI-edition execution scope. Ask the owner to explicitly accept the initial deferrals below or name decisions to settle before kickoff. Record the answer before launching. [Execution plan](phase-3-execution-plan.md) · [HLD handoff](../Phase2-HLD/operational-follow-ups.md)

| Initial deferral proposed for approval | Drafting scope while open |
| --- | --- |
| Production database engine and exact storage implementation | Develop engine-neutral constraints and candidate alternatives. Treat PostgreSQL as the intended retry-evidence candidate under the owner’s LLD brief and show where proofs depend on its behavior. Engine-specific selections remain subject to approval. [DEC-03](../Phase1-conceptual/decisions.md#dec-03-database-and-initial-vendor-delivery) |
| Vendor priorities and live delivery | Specify the common contract and fake connector evidence. Carry forward Workday, Jira, ShiftLeft, SonarQube and Sonatype assessments. Keep live product, privileges, triggers, evidence and commitments open. [DEC-09 follow-ups](../Phase2-HLD/product-follow-ups.md) |
| Exact product, API, security, and operations settings in the HLD follow-ups | Draft alternatives and mark dependent sections with their approval conditions. Carry forward approved constraints and use them in independent design work. [Product follow-ups](../Phase2-HLD/product-follow-ups.md) · [Operational follow-ups](../Phase2-HLD/operational-follow-ups.md) |

## Before adopting a material design choice

Present reviewed options with affected requirements and decision IDs, recommendation, trade-offs, implementation and verification impact, and consequences of deferral. Ask for an explicit choice. Record the owner’s decision, date, rationale, approved scope, affected artifacts and any limits. Apply this gate to new material changes to behavior, public contracts, data, reliability, risk or dependencies. [HLD decision record](../Phase2-HLD/decision-proposals.md)

| Decision group | Ask the owner to settle when needed |
| --- | --- |
| DEC-03, DEC-08, DEC-13 | Production engine if reopened, exact concurrency and atomicity mechanism, key and observation lifecycle, restore fencing, ID representation and non-reuse proof. Bring the PostgreSQL retry evidence environment and assumptions into the decision discussion. |
| DEC-04, DEC-05, DEC-11, DEC-12 | Identity and scope mapping, grants, freshness and outage treatment, access and redaction/retention/holds, history and cursor exposure, and any additional approval rules. |
| DEC-06, DEC-08, DEC-11, DEC-13 | Wire operations, field and encoding limits, type selection, validation/errors, replay responses, continuation bounds and identifier formats. |
| DEC-09, DEC-10 | Exact module and integration boundaries, worker resource limits and conformance fixtures. Any live vendor work requires approved tenant evidence, scope and safety proof. |
| Tooling choices | DTO source and mapping, API compatibility checks, schema-derived types and events, package-boundary tooling, and material dependencies. |
| DEC-07, DEC-14 | Acceptance profiles and gates, logging and admission thresholds, advisory policy, restore topology/RPO, resumption and operator authority. Ask separately before any DEC-07 performance run. |

An approved deferral allows conditional design within its stated scope. Show which artifacts or guarantees depend on it. Keep independent design moving while an approval is pending, and raise any deferral that prevents a usable Phase 4 handoff. [Product follow-ups](../Phase2-HLD/product-follow-ups.md) · [Operational follow-ups](../Phase2-HLD/operational-follow-ups.md)

## Phase 4 handoff

Present the signed-off AI edition, review record and outstanding decisions with the implementation tasks they affect. Ask the owner to accept the package and explicitly accept any remaining deferrals affecting development planning. Keep required but unrun evidence marked as planned. [Review and signoff](review-and-signoff.md#final-package) · [Phase obligations](../Phase1-conceptual/assumptions.md#phase-obligations)
