# B26–B28 · Inbound and extension

### B26 · Inbound identity, atomic effect and acknowledgment

**Needs** B18 and B22. **Sources** Trusted source identity, mapping and atomic ACK. [Inbound contract](../Phase3-LLD/integration.md) · [Mappings](../Phase3-LLD/identifiers.md#external-mappings)

**Red** Reject untrusted sender, wrong tenant, stale overwrite and failed processing commit with no positive ACK. A trusted identical committed repeat receives an ACK with zero added rows/effects. A changed supported type or semantics under the same scoped identity durably quarantines the discrepancy, preserves the original and gives safe non-success. An equal identity under a certified separate domain can remain distinct. **Green** Verified sender and event envelope, scoped `Inbox` constraints, arbitration, protected mapping/semantic digest and ordinary authorized command/effect path or processing obligation. **Stop** Record ACK/commit order, durable identity and one effect or quarantine. Hand event fixture and authoritative ledger contracts to B27/B28/B31.

### B27 · Inbound continuity and restore admission

**Needs** B26, B29 and B32. **Sources** Trusted scope coverage including lost ACK and prior effect proof. [Inbound recovery](../Phase3-LLD/inbound-recovery.md) · [Restore sequence](../Phase3-LLD/recovery.md)

**Red** Restore backup missing committed receipt, retry on another process with absent proof, then trusted prior binding and complete absence. Include lost ACK, changed body/type, independent source continuity and already possible remote sends. **Green** Require original receipt/effect proof and scope coverage. Rebuild a proved compatible prior effect and ACK the exact repeat with zero duplicate effect. Quarantine proved semantic conflicts with safe non-success. Admit a fresh atomic effect only on complete coverage and proved absence. Defer or quarantine incomplete coverage with no positive ACK. **Stop** Capture receipt/effect/obligation/quarantine/remote counts and ACK under each branch. Hand scoped gate to B28/B31 and production B54.

### B28 · Swap fake connector and add an approval policy

**Needs** B20, B21, B25 and B27. **Sources** Additive change constraint and fake conformance. [E5/E6](../Phase3-LLD/verification.md) · [Composition](../Phase3-LLD/wiring.md) · [Five vendor assessments](../Phase3-LLD/vendors.md)

**Red** Use a test-only policy requiring a permitted additional Accept fact. Verify old state matrix still applies, extra fact invalidates old context, and second fake's stable idempotency preserves remote counts and hold semantics. Exercise missed notice, async confirmation, duplicate/stale events, restart and dependency isolation. **Green** Compose test additions through existing ports and fixture configurations. **Stop** Rerun E1–E4 and fake E6, report touched modules/contracts, release count, migration impact and vendor capability assumptions. Hand E5/E6 evidence to B48/B54.
