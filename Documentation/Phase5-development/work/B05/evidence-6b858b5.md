# B05 · Local records and command transaction

**Executor disposition, 2026-11-22 (Europe/Warsaw)** Candidate satisfies the B05 stop criteria and is ready for independent review. GPT-6.2 Sol xhigh (56) executed the assignment. The assigned independent reviewer is GPT-6.2 Astra high (60). [Assignment](assignment.md) · [Ticket](../../../Phase4-development-planning/first-slice.md#b05--local-records-and-transaction-connection)

| Revision | Exact identity |
| --- | --- |
| Accepted plan manifest SHA-256 | `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6` |
| B04 base commit | `8f509e7fa2b538e9de99af2ca516c47a45d1d771` |
| B05 candidate commit on `codex/b05-jdbc` | `6b858b54dbba6501475591d443e4d4f70a42a01d` |
| Candidate tree | `76ee601bb26b593b93818793ba5554121c867b2a` |
| Approved G1-U request SHA-256 | `defd48fd651b4aabb0b514bae34670ca2f8cb11031375f28bd48540f2fefb485` |

[G0 kickoff](../g0-kickoff.md) · [G1-U approval](../B04/g1-u-approval.md) · [Integrated B04](../B04/integration.md)

## Baseline and negative control

- On the B04 base, the workflow-only fixture created the B04 marker schema and authenticated using SCRAM with server and client UTF-8. The SELECT-only observer attempted `SELECT ref FROM requests.request LIMIT 1` and received SQLSTATE `42P01`. The process returned exit 1 for the missing B05 storage capability after confirming the owned postmaster 27276 had stopped. The read-only probe's SHA-256 is `525ad8d23b2e8a0e2c3d556d1bfce1dbe8c701e0f6648c4e4494830bbd1f9ad6`. [Baseline source](../../../tmp/phase5-development/b05-fixture/baseline/B05Baseline.java) · [Baseline log](../../../tmp/phase5-development/b05-fixture/logs/b05-baseline-01.log)
- A candidate-phase regression probe caught a duplicate-key error inside the callback and returned. PostgreSQL still had an aborted transaction, yet the initial Spring/JDBC runner returned success. The probe failed on the expected `25P02` and stopped owned postmaster 22604. The final runner verifies the session can still execute a statement immediately before commit. Both final runs detected the aborted state and preserved the authoritative rows. [Regression red](../../../tmp/phase5-development/b05-fixture/logs/b05-aborted-red-01.log)

## Decisive verification

The two final runs used the qualified Windows x64 PostgreSQL 18.7 binaries and Java Temurin `25.0.5.1+2-LTS`. The server reported `180007`. The client reported pgJDBC 42.7.14 and UTF-8 on both sides. Driver and Checker Qual 3.55.1 JAR SHA-256s matched the B04 approved artifacts `73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c` and `857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288`. The qualified cache was copied into B05 scratch with isolated settings and Maven 3.9.16 `-o -B -C`. [B04 tool and driver evidence](../B04/evidence.md) · [Reproduction](../../../tmp/phase5-development/b05-jdbc/fixtures/postgresql/README.md#run-the-b05-local-storage-and-transaction-checks)

| Oracle | Observed result |
| --- | --- |
| Empty installation and prior upgrade | The empty B05 namespace and the separately populated V001 schema each reached `schema_migration=[1,2]` with 76 catalog constraints. The prior guard retained policy version 7, both runs retained a synthetic B04 marker row, and reapply preserved migration history and catalog definitions. The snapshot-to-fact FK was initially deferred. |
| Isolation and separate identities | The schema was owned by synthetic migration identity `b04_admin`. The runner used `b04_workflow` and observed `READ COMMITTED` on one physical connection. The separate observer ran as `b04_observer` and used a different backend. Command DDL, updates, deletes, schema-history access and observer insertion failed with `42501`. Nested, ambient and prebound use of the runner, and storage access outside the correct bound data source, all failed. |
| Scoped records and uniqueness | Cross-organization body, fact and receipt writes and a missing predecessor fact failed on the expected FK with `23503`. Scoped lookups returned no row in the other organization. Duplicate ref, creation tuple, receipt key, fact revision and publication tuple failed with `23505`. Positive guard and receipt counters, positive/consistent request and fact counters and invalid publication combinations failed with `23514`. A valid Publish snapshot and original receipt round-tripped `9223372036854775807`, with uniqueness across two organizations. |
| Exact text and values | The request name, body and protected fact actor each round-tripped the same 59 UTF-8 bytes, including leading/trailing whitespace, Polish, Japanese, a combining accent, emoji and a newline. Scoped mappers read back the domain snapshots, original receipt, exact digest bytes, nanosecond instants and guard values. |
| Success and precommit fault | The separate observer saw no uncommitted rows. Counts in guard/request/body/fact/receipt order were `[0,0,0,0,0] → [2,1,1,1,1]` for empty and `[1,0,0,0,0] → [3,1,1,1,1]` for prior. The positive and faulted transactions each opened one physical command connection. The connector and repeated in-transaction probes confirmed the same backend and xid after request, body, fact and receipt writes. Fault injection after those writes saw the provisional values inside the command, then left the entire observer snapshot byte-for-byte equal to the pre-fault snapshot. |
| Commit phase and caught SQL error | Committing the request without its matching fact raised `CommitOutcomeUnknownException` with deferred constraint `request_history_tail` and SQLSTATE `23503`. The observer verified no residual rows. The runner detected the caught-statement aborted state as `25P02` before commit and again left the same durable rows. |

| Final run | Postmaster | Committed backend/xid | Faulted backend/xid | Teardown |
| --- | --- | --- | --- | --- |
| `b05-empty-02` | 30604 | 19084 / 770 | 36700 / 771 | Exit 0 at 16:10:06 UTC, status 3, 55480 free |
| `b05-prior-02` | 27092 | 28368 / 772 | 30908 / 773 | Exit 0 at 16:10:18 UTC, status 3, 55480 free |

The repeat cleanup for `b05-prior-02` returned exit 0 with status 3 and port 55480 free. Inspection found no initialization password files. Native diagnostics and data directories remain under each synthetic B05 run in private scratch. B05 used only 55480 through a workflow-only entry point on the B04 launcher. [Workflow fixture](../../../tmp/phase5-development/b05-jdbc/fixtures/postgresql/QualifyPostgresql.java) · [Repeat cleanup](../../../tmp/phase5-development/b05-fixture/logs/b05-prior-02-cleanup.log)

Offline Maven `clean verify` passed 286 existing tests in 11 projects, including 156 domain tests, 126 HTTP-boundary tests and four bootstrap tests. The two opt-in PostgreSQL runs supplement the Maven tests. The adapter JAR contained the four JDBC classes and two migration files. Fixture setup and fault injection reside in the fixture launcher and adapter test classes. [Full offline reactor log](../../../tmp/phase5-development/b05-fixture/logs/b05-final-offline-verify.log)

## Successor handoff

- **B06.** Use `requests.policy_guard(organization, policy_version)` and `PolicyGuardStore` for persisted guard lookup and insertion. This adapter provides those records. B06 owns `FOR SHARE`/`FOR UPDATE`, guarded handoff and independently certified scope and writer proof. Grant the command role the minimum UPDATE privilege PostgreSQL row locks require on the guard. Keep the proof connection and credentials separate. [Access](../../../Phase3-LLD/access.md) · [Wiring](../../../Phase3-LLD/wiring.md)
- **B08.** Wire `JdbcTransactionRunner`, `JdbcWorkflowRecords` (`RequestStore` and `PolicyGuardStore`) and `JdbcReceiptStore` (`ReceiptStore`) to the exact same command data-source instance. A successful runner return follows the owned Spring commit. A commit-phase exception is conservatively unknown and needs certified-lineage resolution before a new effect. Use the immutable B02 request/body/fact and persisted original receipt values. Derive the original Create Location from the committed ref after authorization. Reserve/arbitrate the key before the guard and before provisional ref allocation. The V002 receipt table currently stores only complete successful results; B08 owns the additive reservation mechanism or an evolution of the row with completion enforced at commit. Keep cross-route uniqueness on issuer, organization, principal and token ID.
- **B09.** Add forward migrations to `db/workflow` and register them in `JdbcWorkflowMigrations` as schema owner. V001 is the guard, V002 owns the five local workflow records and their integrity constraints. `requests.schema_migration` is migration-owned. Use explicit least-privilege grants on new tables and writable columns. Scope/assignment changes, local witnesses and advanced integrity verification belong to their dependent tickets. The request FK checks the declared history tail and facts enforce linked predecessor states. Command paths plus integrity verification must keep the greatest history index and fact revision consistent with the snapshot. [Records](../../../Phase3-LLD/persistence.md) · [Scope proof](../../../Phase4-development-planning/scope-proof.md)
- **B16.** V002 reserves the shared namespace value `initial-requests` and a positive signed BIGINT. It enforces the pair in PUBLISHED and uniqueness across organization/type. The publication gate, reservation journal, proof confirmation and release belong to the Publish chain. Persisted expiry and occurrence timestamps use epoch-second plus nanosecond so retention range comparisons can use the ordered pair without rounding. [Identifiers](../../../Phase3-LLD/identifiers.md)
- **B22.** A command-side appender must use the same command data-source instance and Spring-bound JDBC access inside `TransactionRunner`. A worker uses the separately authorized worker data source. Add the outbox migration and least-privilege grants under the schema owner. The B05 fixture establishes the local system for later atomic witness and outbox append coverage. [Outbound](../../../Phase4-development-planning/outbound.md) · [Wiring](../../../Phase3-LLD/wiring.md)

The local Create write-set fault covers the storage component of P3. The commit-phase failure validates one local classification and authoritative rollback path relevant to P5. The two-service, key arbitration and retry, HTTP release, commit cuts, reservation burn, witness/append, recovery and production scenarios remain with their planned tickets. [P1–P9](../../../Phase3-LLD/postgres-evidence.md)

## Raw log hashes

| Artifact | SHA-256 |
| --- | --- |
| [B04-base red](../../../tmp/phase5-development/b05-fixture/logs/b05-baseline-01.log) | `16af0d0fe87f7f965a415aca2a568344701c2f32ffdcabb7a98a394f23ed1e68` |
| [Caught SQL error red](../../../tmp/phase5-development/b05-fixture/logs/b05-aborted-red-01.log) | `19136b06f26fe486c1bde9e4418f2b28c7c805f0a7cda6dbbc2ba025df5d2978` |
| [Empty schema green](../../../tmp/phase5-development/b05-fixture/logs/b05-empty-02.log) | `d6aa75b3836d060aa6dc569e0105022c82afa99912074b0a9215a71df123f680` |
| [V001 upgrade green](../../../tmp/phase5-development/b05-fixture/logs/b05-prior-02.log) | `9e975f6509986cf15d37b8de63f6bde7cada22a01dc534bf94e737e557cdac83` |
| [Repeat cleanup](../../../tmp/phase5-development/b05-fixture/logs/b05-prior-02-cleanup.log) | `a37a603ed28460e9f9b1231573b6c2295922022300337835efb39c60ad7915d0` |
| [Offline verify](../../../tmp/phase5-development/b05-fixture/logs/b05-final-offline-verify.log) | `740e01c39b1d6ca518259108701f87ed6680d9f6e750230b4d05f9608ca4acc8` |
