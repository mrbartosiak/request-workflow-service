# Approved logging, advisory and testing policies

**Scope** DEC-14 operating and release directions. **Status** The owner approved these directions on 2026-11-22. Exact settings, exceptions and authorities remain open. [Quality acceptance](../Phase1-conceptual/quality-acceptance.md) · [DEC-14](../Phase1-conceptual/decisions.md#dec-14-quality-and-operational-policies)

| Policy | Approved direction | Settings and evidence |
| --- | --- | --- |
| Log sink outage | Continue while protected bounded buffering preserves required request and error records. Alert and restrict admission before exhaustion. | Approve limits, fields, access, retention and threshold settings. Exercise sink outage, redaction, admission and recovery. Record any gaps against Q-05. |
| Advisory reviews | Review resolved direct and transitive dependencies against Spring and Snyk on schedule and before release. Block a missing review or a risk-qualifying unresolved applicable finding. | Approve review cadence, severity/exposure criteria and remediation deadlines. Record dated reviews, including zero findings, applicability and disposition. |
| Critical-path quality gates | Gate agreed critical paths using complexity, coverage, module-boundary and regression evidence. Keep TDD in Phase 5 and report test coverage. | Approve exact paths, limits and metrics. Review critical lifecycle, access, retry and delivery risks as candidate scopes. |

**Governance.** Record each breach with evidence, impact, owner and corrective action. Any exception needs a separately approved authority, scope, controls, expiry and follow-up. A gate exception leaves a missed required outcome recorded. Audit recovery operator actions as specified in the recovery design.

[Operating follow-ups](operational-follow-ups.md) · [Operations and recovery](operations-and-recovery.md) · [Acceptance evidence](acceptance-evidence.md) · [Alternatives considered](../tmp/phase2-hld-execution/owner-comparison/acceptance-and-operations.md#dec-14-operating-policies)
