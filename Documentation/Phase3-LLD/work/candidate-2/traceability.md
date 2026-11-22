# Source-to-design evidence map

**Scope** All Phase 1 A-, F-, Q-, R- and DEC-series IDs plus the binding Phase 3 identifier refinement. **Status** Links point to design and planned, unrun evidence. [HLD trace](../../../Phase2-HLD/traceability.md)

| Source IDs | Design | Planned evidence |
| --- | --- | --- |
| A-01, A-02, A-03, F-10, Q-08, DEC-02/10 | [Domain](domain.md) · [Spring](spring.md) | [All-type lifecycle and extension](vendors.md#fake-connector-and-extension-evidence) |
| A-04, F-09, Q-02/03, R-01/02, DEC-03/08 | [Records](records.md) · [Transactions](transactions.md) · [Retries](retries.md) | [PostgreSQL races and commit windows](postgresql-evidence.md) |
| A-05, Q-13, R-08, DEC-04/12 | [Access](access.md) · [Observations](observations.md) · [Authority continuity](authority-recovery.md) | [E4](acceptance.md) · [Policy and key races](restore-evidence.md) |
| A-06, F-11, Q-09, R-04/05, DEC-09 | [Delivery](delivery.md) · [Five assessments](vendors.md) | [Local and remote counts](vendors.md#fake-connector-and-extension-evidence) |
| A-07, R-03/07/09/10, Q-05/06/10, DEC-14 | [Operations](operations.md) · [Recovery](recovery.md) | [E7](acceptance.md) · [Old-backup drill](recovery.md) |
| A-08 | [Proposals](proposals.md) · [Owner gates](../../owner-approvals.md) | [Exact review scope](review-manifest.md) and eventual owner record |
| A-09, F-01/02/03/04, Q-03/04, R-06, DEC-06 | [Domain](domain.md) · [Entry validation](rest.md) | [E1/E2](acceptance.md) |
| F-05, R-02/10, DEC-13 and Phase 3 identifier requirement | [Identifiers](identifiers.md) · [Publication journal](publication-reconciliation.md) · [Recovery](recovery.md) | [Cross-instance order](postgresql-evidence.md) · [Old backup and replay](restore-evidence.md) |
| A-10, F-06/07, Q-11/12, DEC-01/05/11 | [Browse and history](browse-history.md) · [Retention](retention.md) | [E1/E3](acceptance.md) · [Restore proof](recovery.md) |
| F-08 | [REST](rest.md) · [Spring](spring.md) | [E1–E5 through public routes](acceptance.md) |
| Q-01, DEC-07 | [Paced smoke and required acceptance](acceptance.md#quick-opt-in-smoke) | Per-operation timing and capacity reports after authorized run |
| Q-07 | [Tooling](tooling.md) · [Release gates](operations.md#advisories-and-test-gates) | [E7](acceptance.md), Phase 5 TDD and test coverage report |

[Assumptions](../../../Phase1-conceptual/assumptions.md) · [Features](../../../Phase1-conceptual/features.md) · [Quality](../../../Phase1-conceptual/quality-acceptance.md) · [Risks](../../../Phase1-conceptual/production-risks.md) · [Decisions](../../../Phase1-conceptual/decisions.md) · [Owner refinement](../../owner-identifier-requirements.md)
