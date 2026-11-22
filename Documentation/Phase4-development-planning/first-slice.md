# B05–B08 · Persistent Create and replay

### B05 · Local records and transaction connection

**Needs** B04 and G1-U fixture use disposition. **Sources** Request/body/fact/receipt/guard invariants, JDBC connection ownership. [Records](../Phase3-LLD/persistence.md) · [Wiring](../Phase3-LLD/wiring.md)

**Red** Migrate empty and prior test schemas and force mismatched scope, duplicate receipt/reference, invalid counters, invalid publication pair and a fault mid-write. Require constraints, exact UTF-8 strings, and rollback across the actual bound connection. **Green** Explicit PostgreSQL fixture migrations and JDBC mappers for the initial request/receipt/guard records, integrity checks and `TransactionRunner` using the command data source. **Stop** Capture catalog checks, connection identity, commit and rollback row diffs. Hand schema/transaction and migration ownership to B08/B09/B16/B22.

### B06 · Synthetic identity, stable principals and release guard

**Needs** B03, B05 and G1-U. **Sources** Current scoped access, key issuance permission, fenced release and Create ref contract. [Access](../Phase3-LLD/access.md) · [Identifiers](../Phase3-LLD/identifiers.md) · [Authority continuity](../Phase3-LLD/authority-continuity.md)

**Red** Reject wrong issuer/audience/organization, unproved scope, stale epoch and unmapped service Create. Resolve aliases to stable people. Exercise a Create permission check and reject provisional-ref exposure. Assert collisions retry within budget, exhaustion closes, and a reserved ref/creation tuple survives workflow rollback and restart. **Green** Synthetic actor/role fixture, independently certified immutable scope genesis and writer epoch, identity allocator ports backed by isolated durable proof, real PostgreSQL organization shared/exclusive guard and bounded guarded handoff interface. **Stop** Record actor corpus, release boundary, independent reservation and denial evidence. Hand actor/authority contracts to B07–B13.

### B07 · Signed operation-key issuance and exact intent digest

**Needs** B03 and B06. **Sources** Key scope, expiry and purpose-specific exact digest. [Retry contract](../Phase3-LLD/retries.md) · [REST](../Phase3-LLD/rest.md)

**Red** Exercise key issue allowed/denied, altered signature, wrong scope/epoch, trusted clock outage and expired key. Compare equivalent JSON member orders, changed original input bytes and changed action/target/token. **Green** Synthetic-purpose keys, `OperationKeyIssuer`, signed claims validation and length-delimited versioned `IntentDigest`. Apply 24 h reference lifetime, trusted time and configured admission margin. **Stop** Record bounds and verification cases, no raw key in diagnostics. Hand claims and digest/version contract to B08/B12/B14/B33.

### B08 · First runnable, durable Create with exact retry

**Needs** B02, B05, B06 and B07. **Sources** Create and receipt contract, key-before-guard and release evidence. [Domain](../Phase3-LLD/domain.md) · [REST](../Phase3-LLD/rest.md) · [Persistence](../Phase3-LLD/persistence.md) · [Retries](../Phase3-LLD/retries.md) · [Witnesses](../Phase3-LLD/witness-records.md) · [Access](../Phase3-LLD/access.md) · [Authority continuity](../Phase3-LLD/authority-continuity.md)

**Red** Route one authorized Create through real JDBC, restart, then replay the exact intent. Assert the same HTTP 201/Location/ref and exactly one request, body, creation fact and receipt. Two fresh keys with identical fields yield two distinct refs/receipts. A changed intent yields a conflict only with both grants, or ordinary concealment. Before commit, a forced rollback yields zero local effects. A postcommit withheld handoff preserves one effect. Withdraw genesis/epoch proof or expire the key before lookup and assert a closed or expired response and zero receipt lookups. Verify no provisional ref reaches responses, policy deltas or mappings. **Green** Coordinate Create with key arbitration before exclusive guard, atomic request/body/fact/receipt, trusted persisted original result and guarded bounded success release including minimal independent release preparation. Authenticate and prove submitted scope, trusted writer/immutable independent synthetic genesis and key freshness before arbitration. Recheck clock and both intents' applicable grants before result/conflict release. Commit originating person and initial assignment together. The reservation stays isolated until commit. Resolve commit uncertainty on certified lineage. Refuse a fresh effect when proof of absence is unavailable. Advance changing effective scope through B09–B12. **Stop** Artifact contains reviewed HTTP and DB evidence, identity reservations, retry and guarded release checks. Hand runnable build and proof contracts to B12/B13/B32/B41.
