# Phase 5 backlog · inbound and extension

## INT-04 Inbound authentication, durable dedupe and version

**Needs** `APP-02`, `INT-01`. **Scope** Fake callback, approved-source fixture, scoped identity and mapping, command boundary before ACK. **Red** Exercise exact duplicate, changed type in tenant-wide domain, changed body/version, stale/out-of-order notice, wrong source/tenant and lost ACK. Verify one original receipt/effect or obligation, conflict quarantine, correct ACK and no stale overwrite. **Green** Implement inbound port, full scoped identity and semantic digest plus effect/obligation persistence using ordinary service command policy. Reserve external mappings only after proved Create and authorization. **Stop/handoff** Record both processes' ACK, mapping, quarantine and effect counts. Recovery admission follows in `INT-05`. [Inbound contract](../../../Phase3-LLD/integration.md) · [External mappings](../../../Phase3-LLD/identifiers.md#external-mappings) · [E6](../../../Phase3-LLD/verification.md)

## INT-05 Recovery admission and missed events

**Needs** `INT-04`, `INT-03`, `REC-01`. **Scope** Rewound inbox proof and fake source reconciliation. **Red** Restore missing receipt including lost-ACK variant. Assert an incomplete source scope defers without success or added effect. Recover with complete originals, compare changed-binding quarantine and allow an independently proved unaffected source. Force a missed notice and stale reconciliation read. **Green** Implement source/tenant/domain coverage gate and idempotent coupled reconstruction with safe matching and fresh-absence cases. **Stop/handoff** Save before/after receipt/obligation/remote counts and completeness proof for recovery drills. [Inbound restore rows and two-instance case](../../../Phase3-LLD/inbound-recovery.md)

## INT-06 Extension and connector substitution

**Needs** `APP-07`, `INT-03`. **Scope** Test-only composite type policy and alternate fake connector. **Red** Require an extra approved Accept fact. Deny when the fact is missing and verify shared lifecycle and grants still apply. Swap an alternative with stable vendor dedupe and rerun exact E1–E4 workflows. **Green** Supply test profile and composed connector with the same approved ports. **Stop/handoff** Report touched modules, contracts, deployments, migrations and regression results. Preserve each initial type and lifecycle. [E5](../../../Phase3-LLD/verification.md) · [Composition and seams](../../../Phase3-LLD/wiring.md)
