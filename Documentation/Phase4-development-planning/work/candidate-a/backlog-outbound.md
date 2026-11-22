# Phase 5 backlog · atomic append and fake outbound delivery

## INT-01 Transactional outbox and versioned envelope

**Needs** `PG-04`. **Scope** Application-owned outbox append, JDBC connection ownership and synthetic trigger slot. **Red** Roll back after append and prove zero state/receipt/witness/outbox changes. Assert duplicates use same effect key, cross-tenant/destination effect IDs stay distinct and allowlisted versioned envelopes read retained old records. **Green** Append on the Spring-bound command connection with typed `OutboundObligation`, explicit envelope schema and mappings. Preserve full key and version. **Stop/handoff** Attach compile, rollback and schema compatibility reports. Live triggers remain gated. [Connection ownership](../../../Phase3-LLD/wiring.md) · [Outbound identities](../../../Phase3-LLD/integration.md) · [T5-R](../../../Phase3-LLD/tooling.md)

## INT-02 Fake worker claim, arm and finalize

**Needs** `INT-01`, `PRF-02`. **Scope** Separate worker pool, fake remote ledger, durable attempts and current authorization. **Red** Force claims, revoked-before-arm, expired claim, stale finalization, abandoned ARMED, async acceptance, withheld Publish proof and destination isolation. Assert scoped attempt PK, current proof before I/O, no stale finalization and valid independent progress. **Green** Implement durable claim/arm/finalize and lease, destination budgets, pending verification and bounded worker time. Persist ARMED under guard and send outside transactions. **Stop/handoff** Capture attempts and fake remote counts by destination, tenant and effect. [Outbound state table and worker settings](../../../Phase3-LLD/integration.md) · [E6 fixtures](../../../Phase3-LLD/verification.md)

## INT-03 Outbound ambiguity and safe reconciliation

**Needs** `INT-02`. **Scope** Fake lost responses, throttling, remote outcomes and repeat eligibility. **Red** Force lost-before/after apply, both delayed-worker orders, stale/incomplete lookup, lease expiry, restart, permanent failure and revoked in-flight owner. Assert repeat is legal only with complete scoped dedupe guarantee, or verified remote fence plus fresh complete proof of absence. **Green** Implement bounded retry, ledger reconciliation, async verification, safe permanent/quarantine/suppress outcomes and audited operator fixture. **Stop/handoff** Attach E6 remote/attempt counts and a repeat-decision table. Verify unrelated destination progress. `PROD-05` owns vendor target proof. [Ambiguous outbound writes](../../../Phase3-LLD/integration.md) · [E6](../../../Phase3-LLD/verification.md)
