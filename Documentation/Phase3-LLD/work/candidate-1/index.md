# Phase 3 LLD candidate 1

**Revision** PH3-C1-r2. **Status** Independent AI-clarity Markdown candidate submitted for dedicated re-review. Material design choices are proposals pending the reviewed comparison and owner approval. Governing baseline is signed HLD-owner-r3 plus the later owner kickoff and identifier directions. [Sources and conflict](sources.md) · [Review manifest](manifest.md) · [Finding responses](revision-notes.md) · [Owner approval gates](../../owner-approvals.md)

## Read by concern

| Concern | Module |
| --- | --- |
| Interview lifecycle, domain and extension points | [Domain](domain.md) |
| Unversioned commands, validation and response | [REST](rest.md) |
| Decisions by person and current permissions | [Access](access.md) |
| Exact browse with masking and complete history | [Queries](queries.md) |
| Record identity, atomic effects and lock order | [Persistence](persistence.md) |
| Scoped keys, authorized replay and uncertainty | [Retries](retries.md) |
| Body and reason retention choices, holds and purge | [Payload retention](retention.md) |
| Increasing signed 64-bit IDs and scoped mapping | [Identifiers](identifiers.md) |
| Old backups, observations, identity and safe resumption | [Recovery](recovery.md) |
| Outbound obligations, inbound dedupe and reconciliation | [Integrations](integration.md) · [Five vendor assessments](vendors.md) |
| Java 25, Spring 7.0.11 and package wiring | [Wiring](wiring.md) |
| Six tooling evaluations | [Tooling](tooling.md) |
| Concise production-engine cases, quality and performance | [PostgreSQL evidence](postgres-evidence.md) · [Functional evidence](verification.md) · [Performance smoke and acceptance](performance.md) |
| Logging, advisory, quality and operating policy | [Operations](operations.md) |
| Owner choices and requirement evidence | [Decision proposals](decisions.md) · [Traceability](traceability.md) |

**Reading convention** Required and approved constraints carry governing references. Exact class names, wire settings, store layouts, fixture and release choices marked proposed define a concrete review target. Delegated values are defaults for comparison and can be adjusted by the owner. No production engine or live integration selection is implied. Runtime evidence remains planned. The owner’s newer DEC-13 requirement governs publication ordering and post-restore ID non-reuse. [Owner record](../../owner-identifier-requirements.md)

**Phase boundary** This candidate provides Markdown design and evidence specifications. The broader phase plan calls for a separate human-readable LLD, later implementation planning and TDD. Before adoption, complete both dedicated candidate reviews, material-decision comparison and owner decisions, then the assembled LLD reviews. [Execution plan](../../phase-3-execution-plan.md) · [Phase obligations](../../../Phase1-conceptual/assumptions.md#phase-obligations)
