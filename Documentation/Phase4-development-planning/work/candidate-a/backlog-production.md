# Phase 5 backlog · conditional target qualification

Use approved qualification environments and synthetic data where feasible. Each card proves its selected target. G8 controls deployment and activation. [DFR gates](deferred-qualification.md)

## PROD-01 Engine, schema and persistence adapter

**Needs** `REC-04`, DFR-1/5. **Scope** Approved candidate engine/driver and exact migration/mapping qualification. **Red** Apply the P1–P8, local portions of P9 and migration oracles to incompatible transactions, text/lock semantics, old schema, enforced uniqueness and invalid counters. **Green** Qualify target storage with equivalent deterministic tests, initialization and rollback/roll-forward. **Stop/handoff** Save migrations, driver/version and sanitized row/lock data. Pass integrated proof-dependent cases to PROD-02. [Database requirements](../../../Phase3-LLD/persistence.md) · [P1–P9](../../../Phase3-LLD/postgres-evidence.md)

## PROD-02 Target proof and recovery topology

**Needs** `PROD-01`, DFR-1/2/5/6. **Scope** Qualified independent stores/custody and named recovery rehearsal. Use synthetic business actors for these topology cases before corporate actor integration. **Red** Inject rewind, old writer/egress, incomplete scope, missing publication or inbox proof and held/purged payload prerequisites. Fail if no actual independent durable evidence supports promotion. **Green** Connect target proof adapters. Complete P1–P9, targeted restore and confirmation cuts on the exact engine, driver and independent topology, including native backup/failover and representative upgrade. Prove fencing, failure boundaries and staged resumption with verified 60-minute result and separately reported acknowledged loss. **Stop/handoff** Provide full signed case matrix, approved RPO/loss comparison and named operator audit. Any incomplete case remains a promotion blocker. [Recovery](../../../Phase3-LLD/recovery.md) · [Proof tests](../../../Phase3-LLD/proof-evidence.md)

## PROD-03 Corporate actor, grant and key qualification

**Needs** `PROD-02`, DFR-2/5. **Scope** Approved provider/mappings, authentication and grant administration and custody. **Red** Test issuer/tenant spoof, unresolved mappings, rotation and expiry, stale external freshness and revoke/reassign across command/release. Assert zero unauthorized reads/changes. **Green** Qualify approved provider integration, action/field matrix, purpose-specific key lifecycle and fail-closed effective-point proof. **Stop/handoff** Retain reviewed E4/P8 and authority proof with named rollout roles and retained-key verification. [Access and external mirror](../../../Phase3-LLD/access.md) · [Observations](../../../Phase3-LLD/observations.md)

## PROD-04 Retention and operational privacy

**Needs** `PROD-02`, `PROD-03`, DFR-3/6. **Scope** Approved hold, purge, archive and backup disposal processes. **Red** Wrong duty/target cannot act, prior hold blocks purge, archived/rewound retired payload remains fenced, and auditable idempotent selection affects one immutable payload version. **Green** Qualify scoped commands, protected retention and backup disposal workflow. **Stop/handoff** Save authorization/cleanup audit and restore proof. Irreversible operations need approved scope and authority. [Retention](../../../Phase3-LLD/retention.md)

## PROD-05 One selected vendor per qualification instance

**Needs** `PROD-03`, `INT-05`, `INT-06`, DFR-2/4/6. **Scope** Instantiate one card per approved vendor/use case/tenant. **Red** Fail on broad scope, drift, stale lookup or lost secure application evidence, unsafe repeat, invalid input ACK and data leakage. **Green** In approved tenant, prove permitted projections and exact scoped identities, contract/reconciliation and safe outcome states. Verify both delayed old-sender orders. **Stop/handoff** Carry E6 target effect counts, on-call ownership and reviewed thresholds to G8. Keep live calls disabled wherever selection is pending. Assess Workday routing, Jira issue handoff, ShiftLeft finding links, SonarQube revision evidence and Sonatype report reconciliation using accepted scope and measures. [Five vendor rows](../../../Phase3-LLD/vendors.md) · [Delivery safety](../../../Phase3-LLD/integration.md)

[Target release gates](backlog-release-gates.md)
