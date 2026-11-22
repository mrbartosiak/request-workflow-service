# Phase 3 LLD · Candidate 2

**Revision** C2-LLD-r2 · **Status** Independent executor proposal for dedicated re-review. Deployment and implementation remain subject to the [owner approval gates](../../owner-approvals.md).

**Baseline** Signed HLD-owner-r3 with Phase 3 kickoff and identifier requirements dated 2026-11-22 (Europe/Warsaw). PostgreSQL is the evidence candidate. Production storage, live vendor work and nondelegated material choices remain open. [Kickoff](../../owner-kickoff.md) · [HLD](../../../Phase2-HLD/HLD-AI-clarity.md) · [Identifiers](../../owner-identifier-requirements.md)

| Include by subject | Contract |
| --- | --- |
| [Domain](domain.md) · [REST](rest.md) | State transitions, input, routes and wire outcomes |
| [Access](access.md) · [Observations](observations.md) | Trusted actors, policy locks and the context for each action |
| [Browse and history](browse-history.md) · [Retention](retention.md) | Authorized reads, live cursors, complete history and redaction |
| [Records](records.md) · [Transactions](transactions.md) | Logical store constraints, common ordering and atomic effects |
| [Retries](retries.md) · [Identifiers](identifiers.md) · [Recovery](recovery.md) | Scoped replay, publication order and divergent-restore fencing |
| [Publication journal](publication-reconciliation.md) · [Authority continuity](authority-recovery.md) | Independent association and effective policy proof across a divergent restore |
| [Delivery](delivery.md) · [Delivery evidence and vendors](vendors.md) | Atomic delivery, remote proof, inbound processing and vendor readiness |
| [Spring wiring](spring.md) · [Tooling](tooling.md) | Module responsibilities, runtime composition and tooling decisions |
| [PostgreSQL evidence](postgresql-evidence.md) · [Restore and disclosure evidence](restore-evidence.md) · [Acceptance](acceptance.md) · [Operations](operations.md) | Deterministic failure, short smoke profile and release/recovery evidence |
| [Proposals](proposals.md) · [Traceability](traceability.md) · [Verified sources](sources.md) | Material decisions, source coverage and exact review scope |

**Review** [Manifest](review-manifest.md) · [r1 findings and r2 changes](r2-response.md). **Status terms** Required and approved reflect the sources. Proposed defines this candidate's executable design where selection is open. Delegated indicates a reversible configurable value within kickoff limits. Deferred identifies a later choice and the work that depends on it.
