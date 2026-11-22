# B22–B25 · Outbound obligations and safe repeat

### B22 · Atomic outbox append and versioned envelope

**Needs** B05 and B17. **Sources** Command connection and obligation identity. [Wiring](../Phase3-LLD/wiring.md) · [Delivery](../Phase3-LLD/integration.md) · [T5-R](../Phase3-LLD/tooling.md)

**Red** Enable a synthetic trigger, compare same-connection write identity and abort after append. Require zero new outbox/receipt/history and unchanged starting state after rollback, exactly one obligation on success/replay, distinct destination/tenant IDs and old envelope fixture readability. **Green** Application owns `OutboxAppendPort` and versioned permitted DTO. JDBC appender uses command manager/data source and scoped effect ID, trigger slot, projected reference and unique outbox constraints. **Stop** Capture transactional row deltas, compatibility, redaction allowlist and no network in command transaction. Hand worker schema and contract to B23/B34.

### B23 · Claim, arm and finalize across workers

**Needs** B18, B22 and B32. **Sources** Claim, ARMED boundary and worker transaction split. [Outbound](../Phase3-LLD/integration.md) · [Wiring](../Phase3-LLD/wiring.md)

**Red** Race claims across instances, revoke before arm, change epoch, expire a lease after arm and attempt stale finalization. Vary proof readiness and hold successors until predecessor proof. Assert one eligible arm, scoped attempt identity and no I/O while a DB transaction is open. Insert the same effectId and attemptNo under two approved destinations and require two independent attempt rows and recorder invocations. B24 verifies the full remote ledger. **Green** Worker JDBC adapter on worker manager and data source, guarded active-epoch/claim/scope/proof authorization and durable ARMED record before send. **Stop** Record state/tokens and independent pool/connection observations. Hand ARMED protocol to B24/B25/B31.

### B24 · Fake connector and ambiguous delivery disposition

**Needs** B23. **Sources** Unknown/confirmed/effect semantics and operator proof. [Delivery](../Phase3-LLD/integration.md) · [E6](../Phase3-LLD/verification.md)

**Red** Fake sends with lost replies, no apply, delayed old worker both orders, async acceptance, stale lookup, current revocation, confirmed effect, and unsafe repeat. Verify two destination-scoped effects for same effectId and attemptNo in the remote ledger. Require the same stable key's remote single-effect guarantee across possible arrivals or proved remote fence plus fresh authoritative absence. **Green** Implement status transitions, isolated fake remote ledger, exact proof verification, quarantine, reconciliation/audit and authorized recovery actions on synthetic scope. **Stop** Compare local attempts, external count, proof and exposure. Hand configurable fake cases to B25/B28.

### B25 · Scheduling and destination backpressure

**Needs** B23, B24 and B32. **Sources** Approved D4-R worker settings and isolation. [Worker settings](../Phase3-LLD/integration.md) · [Pressure](../Phase3-LLD/operations.md)

**Red** Force throttle, outage, lease expiration, high queue and occupied worker pools across two instances. Assert separate destination progress, shared two-attempt tenant/destination limit, eligible retry only and no REST pool starvation. **Green** Separate scheduler, four workers/connections per instance, batch 8, lease 15 s, 2 s I/O timeout, jitter 1–60 s and max five safe transients in 15 min. Implement trusted throttle hints and bounded resumption. **Stop** Record configured vs observed ceilings, progress and safe unknown/quarantine counts. Hand load instrumentation to B28/B31/B42.
