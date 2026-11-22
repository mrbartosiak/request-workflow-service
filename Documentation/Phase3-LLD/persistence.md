# Records, transactions and database guarantees

**Scope** A-04, F-01–09, Q-02–04/11–13, R-01/02/08/09, DEC-03/04/05/08/11/13. D1-R adopts ordered atomic state, history, receipts and witnesses. The record layout is logical. Production engine and exact schema remain open. [HLD](../Phase2-HLD/local-consistency.md)

| Record | Identity and integrity |
| --- | --- |
| `Request` | PK `ref` globally unique. Immutable organization, type, originating person, name and creation tuple. Check positive `revision` and `lastSequence`. Check publication pair present exactly in PUBLISHED. Unique `(namespace,publicationId)`, signed positive 64-bit. |
| `RequestBody` | PK/FK `ref`, protected current body and retention status. |
| `TransitionFact` | PK `(ref,sequence)`, contiguous positive indexes per request. Unique `(ref,revision)`. Append-only from/to, action, recorded instant, actor reference. |
| `TransitionReason` | PK/FK `(ref,sequence)`. Protected payload present at Delete/Reject commit, separately redacted. |
| `OperationReceipt` | Unique `(issuer,organization,principal,tokenId)` across all routes. Intent digest, result action/state/ref/revision, optional ID, original HTTP status, key generation and expiry. Protected control data, no command text. |
| `PolicyGuard`, `Assignment`, `DecisionEvidence` | Guard PK organization with policy version. Scope-bound grant/mapping rows, assignment and evidence rows with independent versions. |
| `PublishGate` | PK stable namespace. Hold exclusive through number reservation and durable command outcome. |
| `Outbox`, `Inbox`, `ExternalMapping` | Scoped identities per [delivery](integration.md) and [identifiers](identifiers.md). |

Initial `revision=1`, `lastSequence=1`. Every successful existing write increments revision using checked arithmetic. Increment sequence only for state changes. Validate request state equals history tail at verification and recovery. Encode strings with byte-preserving UTF-8. Represent internal counters as bounded integer types with no overflow wrap. Enforce scoped references and uniqueness in the database. Cross-record invariants requiring joint state use exclusive command paths, transactions, constraints where supported, and integrity audits.

`TransactionRunner` owns one command transaction. Prove writer admission and submitted access. Lock the key first, then exclusive organization `PolicyGuard`, then request/fact rows in stable order. Verify access, scope, observation, workflow and type policy. Prepare any required independent scope delta before reserving a publication. Take `PublishGate` last. Commit state/body/revision, origin binding, history/reason, receipt, applicable decisions, local witnesses and `OutboxAppendPort` writes together. Edits add no history. Rollback clears local effects and burns reserved IDs. After known commit, confirm required witnesses before result release. Protected release takes the organization guard shared. All visibility writes use it exclusively. [Witness records](witness-records.md) · [Wiring](wiring.md) · [Replay](retries.md)

**Approved PostgreSQL evidence strategy, unrun.** Use `READ COMMITTED`, unique key insertion and guard `FOR SHARE`/`FOR UPDATE`. Read the authorized representation in fresh statements after acquiring the shared guard. Hold guards through commit/rollback or protected handoff. Bound wait and statement time. Resolve on the certified primary. Conflicting same-revision mutations allow one commit, with stale observation for a permitted loser. The organization gate serializes mutations in that organization, so measure its cost. [PostgreSQL isolation](https://www.postgresql.org/docs/18/transaction-iso.html) · [Row locks](https://www.postgresql.org/docs/18/explicit-locking.html)

Conditional writes or scope-before-key locking require changed lock and failure evidence. Event sourcing adds projection, restore and compatibility obligations. The adopted design uses state and ordered history. A production engine must pass equivalent API, atomicity, ordering, uniqueness, exact-text, durability, access, replay and restore checks. [PostgreSQL scenarios](postgres-evidence.md) · [Open choices](decisions.md)
