# B32–B36 · Two-instance PostgreSQL verification

### B32 · Deterministic real-engine harness

**Needs** B04, B05 and B08. **Sources** Two processes, real engine and deterministic barriers. [PostgreSQL harness](../../../Phase3-LLD/postgres-evidence.md) · [Environment](environment.md)

**Red** Detect wrong server/schema, nonindependent process/pool, stalled latch, false blocking and failure controls on release classpath. **Green** Launch A/B, independently verify prepared environment, clocks and actor scopes, inject key/precommit/prerelease barriers and capture primary/independent proof reads. **Stop** Save process identifiers, separate pool evidence, `pg_blocking_pids` check and reproducible barrier command. Hand harness to B33–B40.

### B33 · Key contention and intent isolation · P1/P2

**Needs** B12, B14 and B32. **Sources** P1/P2 and expiry/denial contract. [PostgreSQL cases](../../../Phase3-LLD/postgres-evidence.md) · [Keys](../../../Phase3-LLD/retries.md)

**Red** Freeze A after key claim, require B demonstrably blocks, release A and compare matching results. Force B timeout and same-key retry. Race cross-route changed intent and concealed original. **Green** Add deterministic cases and verify A/B 201 with shared identity, timeout 503, 409 only if both sides disclosable, otherwise ordinary denial and no hints. **Stop** Compare receipts, refs, history, status/Location and two fresh-key distinct creates. Hand fixtures to B34/B39.

### B34 · Commit, response and retry uncertainty · P3–P5

**Needs** B17, B22 and B32. **Sources** P3–P5 and connection ownership. [PostgreSQL](../../../Phase3-LLD/postgres-evidence.md) · [Wiring](../../../Phase3-LLD/wiring.md)

**Red** Cause precommit rollback, rollback after append and reserve, response loss after commit/restart, and JDBC cuts before versus after real commit. Hide proof until 503 and continue with original intent/key on B. **Green** Save barrier tests for both outcomes. Verify no partial effect, burned ID, one original result and obligation, zero additional sends, proof-pending hold, and expiry even after receipt purge. **Stop** Separate HTTP, exact durable state, witness and connection evidence. Hand fixture cuts to B38/B40.

### B35 · State and namespace races · P6/P7

**Needs** B14, B15, B17 and B32. **Sources** Both winner orders and numeric assignment. [P6/P7](../../../Phase3-LLD/postgres-evidence.md) · [Identifiers](../../../Phase3-LLD/identifiers.md)

**Red** Force both orders for Publish/Reject, Edit/Accept, Verify/Delete, Edit/Edit, plus two publishes across organizations/types. Use separate valid observations and keys. Vary independent facts, rollback and values above 2^53 through exhaustion. **Green** For P6 require one committed effect and zero loser effects, with authorized stale or ordinary denial. For P7 require two successful distinct refs/IDs and strictly increasing assignment in actual commit order. In rollback variant require one successful Publish and permanent burn of the earlier reserved ID. **Stop** Save primary and journal histories, reason/receipt invariants, exact HTTP ID and replay comparisons. Hand evidence to B48/B51.

### B36 · Revocation and release races · P8/E3/E4

**Needs** B19, B20, B21 and B32. **Sources** P8 and forced disclosure order. [PostgreSQL cases](../../../Phase3-LLD/postgres-evidence.md) · [Functional](../../../Phase3-LLD/verification.md)

**Red** Revoke before lock settles and after commit before handoff. Delete sole visible lookahead during browse, and race Delete against detail/context. **Green** Run both orders, verify governed action effect, masked response, exact cursor presence or absence and no replay or unauthorized token hint. **Stop** Compare access, headers, bodies, tokens, before/after rows and observed release ordering. Hand privacy/concurrency proof to B48/B52.
