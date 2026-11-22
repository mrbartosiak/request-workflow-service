# Phase 5 backlog · target release gates

Use the approved qualification artifacts from the named environment. G8 controls deployment and activation. [Deferred qualification](deferred-qualification.md)

## PROD-07 Target build and operating gate qualification

**Needs** `OPS-02`, `OPS-03`, `OPS-04`, `OPS-05`, `PROD-01`, DFR-5/6. **Scope** Mandatory pins and approved quality/evidence gates. **Red** Candidate release blocks missing advisory review, exposed unresolved qualifying findings, missing approved coverage/complexity/boundary tests and contract regressions. **Green** Enable chosen gates against exact pins, released contract where required, and named exception/evidence owners. **Stop/handoff** Save reviewed gate policy/test outcomes, approved alternatives if replacing a pilot and rollout criteria. [Tooling choices](../../../Phase3-LLD/tooling.md) · [Operating gates](../../../Phase3-LLD/operations.md)

## PROD-06 Release recommendation

**Needs** `PROD-01`–`PROD-04`, `PROD-07`, `PERF-03`, applicable `PROD-05` instances and DFR-1–7 dispositions. **Scope** Exact production release candidate and previously approved scope only. **Red** Reject incomplete P1–P9, targeted restore/cut, native backup/failover/upgrade or mismatched topology evidence, unresolved required outcomes and unapproved live settings. **Green** Assemble a reviewed evidence bundle, rollout/rollback controls, named authorities and promotion recommendation scoped to the qualified release. **Stop/handoff** Record continued blocks and decision inputs for G8. [Verification](../../../Phase3-LLD/verification.md) · [Quality acceptance](../../../Phase1-conceptual/quality-acceptance.md)
