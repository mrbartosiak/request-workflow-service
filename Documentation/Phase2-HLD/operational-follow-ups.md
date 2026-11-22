# Evidence and operating decisions still open

**Status** The owner approved operating directions and authorized tracking these settings on 2026-11-22. Apply the approved outcomes when planning evidence. Approve remaining settings before relying on the related gates. [Decision record](decision-proposals.md)

| Follow-up decision | Settings to approve | Dependent work |
| --- | --- | --- |
| **DEC-07 Performance profile and execution** | Approve traffic shares and validity/failure/retry denominators, dataset size/skew, instance count above the approved minimum, environment, resources, dependencies, warm-up and measurement length. Ask for a run separately. | Performance profiles, valid acceptance runs and capacity evidence. [Performance test plan](performance-test-plan.md) |
| **DEC-14 Logging and admission thresholds** | Approve safe fields, access and retention, buffer limits, alert and admission thresholds, fallback details and authority. | Logging design, sink-loss tests and operational runbook. [Operating policies](operating-policy-alternatives.md) |
| **DEC-14 Advisory risk and exceptions** | Set cadence, severity/exposure criteria, remediation timelines and exception authorities, controls and expiry. | Release checklist and auditable release gate. [Operating policies](operating-policy-alternatives.md) |
| **DEC-14 Critical-path test gates** | Agree exact critical paths, complexity ceilings, coverage metrics and floors, module-boundary and regression checks and exceptions. | Gate configuration and qualifying evidence. [Acceptance evidence](acceptance-evidence.md) |
| **DEC-14 Restore, failover and authority** | Approve topology, recovery point objective and any permitted data loss, drill scenarios, alert thresholds, staged resumption checkpoints and named authority for pause, resume, replay, reconciliation and compensation where applicable. | Fault exercises, backup/failover design and runbooks. The existing verified restore-time target remains 60 minutes from declaration. [Recovery](operations-and-recovery.md) |
| **Phase 3 handoff** | Approve the reviewed HLD for low-level design and explicitly accept any deferrals affecting that work. | Low-level design remains pending a separate owner decision. [Owner gate](../Phase1-conceptual/phase-2-hld-execution-plan.md#owner-approvals) |
