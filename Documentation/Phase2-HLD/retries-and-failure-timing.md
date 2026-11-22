# Retries and failure timing

**Scope** Client replay and outcome resolution across instances. **Status** DEC-04/05/08 approve scoped keys, atomic minimal receipts, fresh authorization and expired-key blocking. Exact key lifetime, markers, wire errors and disclosure fields remain open. [Q-02](../Phase1-conceptual/quality-acceptance.md#q-02-database-level-idempotency-across-instances) · [Follow-ups](product-follow-ups.md)

## Approved command direction

Require a key on each mutation, scoped across routes to trusted issuer, organization and stable principal. Grant changes preserve key scope. A successful key binds action, target, semantic inputs and original preconditions, including the observed revision. Changed intent under a retained key conflicts. Distinct keys may create distinct requests with identical fields. Server-issued operation resources add expiry control and an extra round trip.

Arbitrate keys atomically with business effects. Store minimal original facts such as references, publication ID and result revision. Reconstruct under current grants and retention. An exact replay uses original preconditions and reads the durable effect. A different key starts a new command. Definitively uncommitted attempts leave no success binding. Uncertain failures may hide a commit. Use a new key for changed intent. Persisting deterministic failures can preserve stale denials.

Expired keys cannot repeat effects. If comparison facts were purged, a known key yields an expired outcome when authorized and causes no effect. Never reconstruct purged text. Retain minimal retired-key markers or use verifiable expiry. Evict markers only when old keys remain detectably expired or the scope closes safely. After restore, fence affected retry ranges if prior key evidence is missing. Periods and exact expiry mechanism still need approval. [REST responses](rest-and-browsing.md) · [Retention](workflow-and-history.md)

## Concurrent and failure sequences

| Sequence | Outcome under the approved direction |
| --- | --- |
| Same key and same intent in flight | The database orders attempts. If one commits, the other retrieves the committed result subject to fresh access. If the first rolls back, a contender may proceed only after current authorization and eligibility checks. Bound any wait within the service budget and give a retryable unknown/pending result when it expires. |
| Same key and different intent in flight | If either commits, the other has zero effects. Authorize original and submitted actions and targets before disclosing a conflict. If all roll back, there is no successful binding to compare. |
| Commit succeeds, response lost or instance restarts | A retry resolves the durable key and reads the original outcome without rerunning the action. Return only currently authorized fields, including the original Publish ID when permitted. |
| Failure before commit | The key outcome, request effects, history and approved delivery obligation roll back together. A retry with the same intended inputs may compete for a single effect if currently eligible. |
| Database timeout during commit or lost acknowledgment | Treat outcome as unknown. Query the durable authority using the same key after it can resolve the previous transaction. An absent result on a stale replica or during unresolved failover is insufficient to re-execute. Report retryable uncertainty and preserve the same key. |
| Permissions or scope change while a command waits | Re-evaluate under the effective policy at the mutation boundary. A revoked actor cannot acquire fresh effects by resending. A committed effect stands if policy ordered it before revocation. |
| Permissions change after commit, before replay or disclosure | Check fresh disclosure against original target, operation and permitted fields. Return a concealed denial or approved redaction and avoid indicating earlier success via key or response differences. New authorization cannot resurrect purged restricted data. |

For competing mutations with different keys, the current state and caller preconditions determine eligibility at the shared commit boundary. Record key conflict, uncertain outcome and authorization failure as safe operational classes. [Local consistency](local-consistency.md) · [Access races](access-and-approvals.md#authorization-races) · [Decision proposals](decision-proposals.md)
