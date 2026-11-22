# Transaction witnesses and independent proof

**Scope** A-04/05, F-05/09/11, Q-02/03/10/13, R-01/02/08/10, DEC-03/04/05/08/13/14. Logical records for D1-R/D2-R. Exact storage, deployment and operators remain open. [Atomic writes](persistence.md) · [Recovery](recovery.md)

| Record | Identity, linkage and authority |
| --- | --- |
| Independent publication reservation | PK `attemptId` unique across epochs. Immutable epoch, ref, namespace, positive ID. Unique `(namespace,id)`. One PENDING/COMMITTED reservation per ref. ABORTED values stay burned. |
| `PublicationWitness` | PK local attempt identity. Bind certified epoch, ref, namespace/ID, receipt identity and resulting revision. Unique successful ref and publication pair. Commit with Publish on the command connection. |
| `PublicationConfirmation` | Local verified projection keyed by witness attempt and exact ref/ID. Rebuild from authoritative confirmation after restore. Update under exclusive organization guard. |
| Independent scope journal | PK `(organization,sequence)`, unique `scopeAttemptId` with epoch, digest, versions and PENDING/COMMITTED/ABORTED delta. Preserve late-attempt closure and contiguous verified checkpoints. |
| `ScopeAuthorityWitness` | PK `(organization,sequence)`. Bind `scopeAttemptId`, epoch, versions and exact delta digest. Commit on the connection of the effective change. |
| `ReleasePrepared` | Scoped operation/key digest, minimum durable result refs, revision, epoch and release correlation. Append independently before a success handoff is attempted. |
| `HandoffProved` and `ClientAcknowledged` | Separate trusted evidence keyed by release correlation. Handoff evidence verifies enqueue. Client evidence independently confirms receipt of the correlated result. Record either only when evidenced. |
| Hold/purge ledger | Scope, immutable payload key/version, decision, ordering, authority and effective point. Preserve holds and irreversible purge facts across rewind. Reapply before release. |

`TransactionRunner` writes local witnesses with their effect. Independent journals prepare before the local effect. After commit the internal resolver confirms matching witnesses from trusted original lineage, including while the public scope is closed. Proven rollback closes attempts and burns numbers. Hold unproved gaps fenced. [Publication](publication-journal.md) · [Resolver](proof-resolution.md)

Report commit, preparation, evidenced handoff and client acknowledgment separately. Commit may be ambiguous or proved. A proved commit can have no preparation evidence, or `ReleasePrepared` with handoff unknown. `HandoffProved` records release while client receipt is unknown. `ClientAcknowledged` establishes positively acknowledged success. Count lost acknowledged writes only from positively acknowledged effects missing on verified recovery. A restored miss cannot prove prior abort or lack of release. [Cut tests](proof-evidence.md) · [Recovery](recovery.md)
