# Composed integration delivery

**Scope** A-06, F-11, Q-08/09, R-03–05/10, DEC-03/08/09/10/14. Atomic obligations, isolated dispatch, proof-gated repeat and inbound dedupe are approved. D4-R adopts the module boundary, worker budget and fake fixture policy. Exact storage and live vendor priorities remain deferred. [HLD](../Phase2-HLD/integration-delivery.md) · [Vendors](vendors.md)

**Outbound.** An approved trigger writes `OutboundObligation` in the command transaction. `Outbox` PK is `effectKey=(destination,tenant,effectId)`. Record ref/revision, operation, schema version, permitted projection reference, schedule and status. Derive `effectId` from stable operation identity and trigger slot. `Attempt` PK is `(destination,tenant,effectId,attemptNo)` with FK to the full parent key. Keep claim token, generation, lease, authorization proof, correlation, timing, safe error and confirmation reference. Changed intent needs its own effect identity. [Command append](wiring.md)

`DispatchCoordinator` claims work, then under the organization guard proves active epoch, current scope, claim, grants, cancellation and required scope/publication confirmations. Commit an `ARMED` attempt before I/O. Prior revocation blocks send. Later revocation may leave uncertainty. `VendorConnector` sends outside transactions. Claim token and epoch guard finalization. Hold successors until predecessor proof. [Proof gates](proof-resolution.md)

| Evidence or outcome | Durable state and action |
| --- | --- |
| Committed pending obligation | Start in `READY` and claim to `CLAIMED`. Recover abandoned work using its attempt evidence. |
| Fresh authorized claim | `ARMED` before sending. An abandoned armed attempt without terminal proof is `UNKNOWN`. Async acceptance is `ACCEPTED_PENDING` and awaits verification. |
| Proven unsent or authoritative non-application with no possible late apply | Use `RETRYABLE` for transient cases. Keep the effect ID and bound backoff. |
| Confirmed exact intended effect | Use `CONFIRMED` and store the scoped remote mapping and confirmation proof. |
| Timeout, pause after final check, revoked in transmission or evidence gap | Hold in `UNKNOWN` and reconcile. An expired lease only blocks stale local finalization. A delayed worker can still send or apply. |
| Unsafe repeat or durable contract/credential problem | Record `QUARANTINED` or `FAILED_PERMANENT` and notify the owner with a safe trace. |
| Proven unsent and denied/cancelled | `SUPPRESSED`. An uncertain attempt must be resolved before suppression can assert no remote effect. |

An ambiguous write may repeat only if vendor single-effect behavior for the stable scoped ID covers every possible old and new arrival, or an independently verified remote fence prevents old and in-flight application **and** a fresh complete authoritative lookup proves no effect. Test both orders of a delayed old worker and replacement. An empty lookup alone or a lease expiry is insufficient. An authorized operator may reconcile, confirm, suppress or pursue separately approved compensation. Manual retry uses the same safety proof.

**Inbound.** Authenticate sender, tenant, integrity, mapping and target scope. `Inbox` PK is `(sourceSystem,tenant,identityDomain,sourceIdentity)`, with identity domain configured from trusted source guarantees. Compare declared type, target, version and semantic digest. Commit receipt and effect or a processing obligation before ACK. Repeats add nothing and conflicts quarantine. Reconcile hints and reject stale overwrite. Business actions use the ordinary command path with service scope, key and applicable observation. After rewind require source-scope inbox/effect proof before treating any miss as fresh. [Recovery admission](inbound-recovery.md)

**D4-R worker settings.** Four workers and four dedicated connections per instance. At most two concurrent attempts per tenant/destination cluster-wide, enforce in shared storage. Batch 8, lease 15 s, timeout 2 s, jitter 1–60 s and at most five proven-safe transients in 15 min. Respect trusted throttling hints through a destination limiter. Quarantine unproved ambiguity. Use a fake and a DB outbox before considering a broker relay. [Wiring](wiring.md) · [Conformance](verification.md)
