# B29–B31 · Rewind-safe operation

### B29 · Recovery epoch and hard exclusion

**Needs** B04, B11, B16 and B23. **Sources** Epoch, certified lineage and egress fencing. [Recovery](../Phase3-LLD/recovery.md) · [Identifier authority](../Phase3-LLD/identifiers.md) · [Scope authority](../Phase3-LLD/authority-continuity.md)

**Red** Hold an old transaction, outbound claim and response egress while restoring privately. Demand proof old transactions cannot commit and stale preserves no write/key/release/arming rights after generation advance. Require increasing signed 64-bit generation with stop at exhaustion. **Green** Script physical exclusion tests on isolated fixture, attestation interfaces, validation of lineage and monotonically certified writer epoch. Distinguish potentially in-flight remote effects for reconciliation. **Stop** Evidence captures fence before epoch, writer and egress safeguards and closed public state. Hand restore fixture interfaces to B27/B30/B31/B37.

### B30 · Retention, hold and irreversible purge

**Needs** B10, B15 and B29. **Sources** Reference windows and independent hold/purge proof. [Retention](../Phase3-LLD/retention.md) · [Scope continuity](../Phase3-LLD/authority-continuity.md)

**Red** With simulated time, test 30-day deleted body and 90-day reason eligibility, both hold/purge orders, uncertain ledger commit and cleanup failure. Restore old payload and prove no exposure after durable purge. Require complete unchanged facts and visible held payload only under designated synthetic policy. **Green** Synthetic fixture hold/purge authority, versioned independent ledger, guarded release and idempotent physical cleanup. **Stop** Capture ledger ordering, timestamps, mask and zero post-purge exposure. Hand policy and fixtures to B31/B39. G4 gates real authority and disposal.

### B31 · Verified staged recovery coordinator

**Needs** B12, B18, B25, B27, B29 and B30. **Sources** Backup and logs rehearsal, scope proof, mapping and release/ack evidence. [Recovery](../Phase3-LLD/recovery.md) · [Proof cuts](../Phase3-LLD/proof-evidence.md) · [Witnesses](../Phase3-LLD/witness-records.md)

**Red** Against old image, attempt to resume with invalid schema/history, an authority or namespace gap, missing mapping, old receipt, inbound uncertainty or queued unproved remote effect. Verify permissible current read, safe write then dispatch sequencing. **Green** Synthetic containment/runbook coordinator and verifiers for authoritative checkpoint/log continuity, restore proofs, state/history/receipt/mapping checks and staged resumption. Classify uncertain commit, prepared, handoff-proved and client-acknowledged separately. **Stop** Report resumption time and only positively acknowledged effects lost, plus incomplete evidence. Hand timings and control path to B37–B44/B55.
