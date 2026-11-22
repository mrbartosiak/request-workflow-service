# Durable publication associations

**Scope** F-05, Q-02/03/10, R-01/02/10, DEC-08/13. D1-R selects the outside-restore association proof and target fencing. Provider and topology remain deferred. [Identifiers](identifiers.md) · [Owner requirements](owner-identifier-requirements.md)

`PublicationJournal` is the independently durable publication high-water and association authority. Under `PublishGate`, `reserve(namespace, epoch, ref, attemptId)` durably writes PENDING with the newly burned ID. An attempt ID is unique across writer generations. Reject an uncertified epoch, fresh attempts for refs with PENDING/COMMITTED bindings, and counter exhaustion. Repeat of the same authority message is idempotent. ABORTED retains a burned number. COMMITTED permanently binds the original pair. Initial enablement and migration attest complete associations and preserve checkpoints. [Records](persistence.md)

The command connection commits `PublicationWitness(attemptId,epoch,namespace,ref,id,receiptIdentity,resultRevision)` with state, history, receipt and obligation. After durable commit, `confirm` accepts only trusted original-lineage witness or equivalent commit proof. Confirmation and `PublicationConfirmation` projection are idempotent. Update the projection under the exclusive organization guard. Protected output and dependent dispatch require confirmation plus current scope and applicable scope confirmation. With current scope but missing publication proof return 503 `outcome_unknown` on the same key. [Resolver and gate order](proof-resolution.md) · [Queries](queries.md) · [Dispatch](integration.md)

| Failure window | Durable decision |
| --- | --- |
| Reserve request or reply lost | Stop that local attempt. Discard late replies. The independent journal can retain PENDING. A confirmed local rollback may close that attempt even before a delayed reserve arrives; closed attempts cannot reopen. |
| Local rollback from a known live connection or equivalent terminal proof | Mark ABORTED using proof tied to that attempt. There is no published association/effect. Never allocate the number again. |
| Local COMMIT loses acknowledgment or confirmation is unavailable | Remains PENDING until a matching trusted committed witness or affirmative rollback proof. The restored database's missing row is insufficient to abort. |
| COMMITTED association, restored target still ACCEPTED, missing, or diverged | Restore the original effects as one verified recovery action from trusted complete evidence, including receipt and obligation as applicable, or fence that target's writes and associated dispatch. No fresh Publish or synthetic reassignment. |
| Unresolved PENDING after divergent restore | Fence that ref even on a fresh key and observation. Definite abort permits a fresh publication using a higher number. If journal coverage is unproven, fence the whole affected namespace. |

While gated, any degraded read needs independent authorization proof and approved policy. A fresh Publish on a fenced but disclosable target returns 503 `authority_unavailable`. Otherwise ordinary concealment applies. Preserve numerical high-water even for ABORTED reservations. [Recovery](recovery.md) · [PostgreSQL evidence](postgres-evidence.md)
