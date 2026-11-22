# Phase 3 low-level design · AI clarity

**Revision** PH3-LLD-r2. **Status** Owner-approved reference design, assembled revision submitted for both reviews. The owner selected D1-R, D2-R, D3-R, D4-R, T1-R through T6-R and D6-R on 2026-11-22 (Europe/Warsaw). Phase 4 handoff requires separate owner acceptance. [Owner selections](owner-design-selections.md) · [Decision record](decisions.md) · [Exact manifest](work/reviews/lld-r2-manifest.md)

The company-internal backend uses Java 25 and Spring Framework 7.0.11. All initial types implement the approved interview lifecycle and shared access and history rules. The newer owner identifier requirements govern monotonic Publish assignment, exact decimal-string wire identity and post-restore non-reuse. PostgreSQL is the retry-evidence candidate. Production engine, exact storage, live vendor scope, proof-store topology, RPO and operator authority remain deferred. [Signed HLD](../Phase2-HLD/HLD-AI-clarity.md) · [Owner kickoff](owner-kickoff.md) · [Owner identifiers](owner-identifier-requirements.md)

## Include by subject

| Subject | Modules |
| --- | --- |
| State machine, body changes and ordered history facts | [Domain and workflow](domain.md) |
| Entry validation, routes and adopted result/error contract | [REST](rest.md) |
| Access, action-specific context and masked reads | [Access](access.md) · [Observations](observations.md) · [Browse and history](queries.md) |
| Atomic effects, arbitration and replay | [Persistence](persistence.md) · [Witness records](witness-records.md) · [Retries](retries.md) · [Retention](retention.md) |
| Identifiers and effective authority through rewind | [Identifiers](identifiers.md) · [Publication journal](publication-journal.md) · [Authority continuity](authority-continuity.md) · [Proof resolution](proof-resolution.md) · [Recovery](recovery.md) |
| Transaction connection ownership, delivery and extension | [Spring wiring](wiring.md) · [Integration](integration.md) · [Inbound recovery](inbound-recovery.md) · [Five vendor assessments](vendors.md) |
| Six build and verification-tool evaluations | [Tooling](tooling.md) |
| Functional, database, restore and performance verification | [Functional](verification.md) · [PostgreSQL](postgres-evidence.md) · [Restore](restore-evidence.md) · [Proof cut tests](proof-evidence.md) · [Performance](performance.md) |
| Operations, requirements and handoff | [Operations](operations.md) · [Traceability](traceability.md) · [Phase 4 readiness](handoff-readiness.md) · [Source verification](sources.md) |

**Reading convention** Required marks interview and owner obligations. Approved marks the selected reference design. Proposed marks implementation or tuning details still requiring validation or approval. Deferred production settings remain open. The owner delegates reasonable retention, browse and recovery tuning within approved policy. Unselected alternatives remain documented in the reviewed comparison. [Decision slate](work/reviews/comparison.md) · [Remaining gates](handoff-readiness.md)

**Evidence status** Source and package checks are static. The workspace has no runnable backend, service build or supplied endpoint. The checked Java is 21, and PostgreSQL tools and Docker were unavailable in the checked paths. The authorized quick run and database, restore, connector and integration evidence remain planned. Formal load profile and execution require separate approval. Capacity, access and recovery criteria remain binding. [Readiness](work/reviews/performance-readiness.md) · [Acceptance](../Phase2-HLD/acceptance-evidence.md)

**Delivery boundary** Phase 3 covers AI-clarity Markdown. The broader plan includes a human LLD, later development planning and TDD. The eventual interview submission documents assumptions and provides a public GitHub or Bitbucket source repository with its root README. The final submission is source-only. [Phase obligations](../Phase1-conceptual/assumptions.md#phase-obligations)
