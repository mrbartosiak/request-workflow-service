# Conditional LLD responses to both reviews

**Revision** PH3-LLD-DRAFT-r2. Reviewer 1 assigned R1.1–R1.4 and Reviewer 2 assigned R2.1–R2.4 below. The revision remains conditional pending both re-reviews and owner selection. [Exact manifest](lld-draft-r2-manifest.md) · [Reviewer 1](lld-draft-r1-reviewer-1.md) · [Reviewer 2](lld-draft-r1-reviewer-2.md)

| Finding | Resolution and evidence |
| --- | --- |
| R1.1 · Pending scope confirmation | Stable scope attempt ID, idempotent prepare/abort and late-message closure. Audited internal resolver can match original-lineage evidence while public scope stays fenced. Both applicable confirmations and current scope proof gate publication output and send. Pre-handoff preparation remains separate. Exercise prepare loss and both completion orders. [Protocol](../../proof-resolution.md) · [Cut tests](../../proof-evidence.md) |
| R1.2 · Inbound after rewind | Check complete source/tenant/domain receipt and effect coverage, recover original verified effect/receipt, quarantine changed binding and defer incomplete scopes. Test acknowledged receipt loss, conflict and a ready independent source. [Admission](../../inbound-recovery.md) · [Restore](../../recovery.md) |
| R1.3 + R2.2 · Executable lifecycle races | Separate exhaustive domain checks from reachable HTTP eligibility. P6 uses actor/action-bound observations on identical starting facts with distinct keys. Denied context, mismatched action and stale/ordinary denied loser yield no effects. [E1](../../verification.md) · [P6](../../postgres-evidence.md) |
| R1.4 · D6 gate | Restore exposure requirement and missing-review block. Document exposed/unknown review/unexposed outcomes. Extra risk rules, deadlines, tool adoption and exception choices require separate approval. [Gates](../../operations.md) |
| R2.1 · Current detail revision | Add an optional current decimal-string revision in authorized detail and retain original revision on replay. Both are descriptive. Mask deleted/restricted details and reject either as token. [REST](../../rest.md) · [E2/E3](../../verification.md) |
| R2.3 · Proof writes and ownership | Make `adapter.jdbc.JdbcOutboxAppender` explicit. P5 allows idempotent proof, projection and release evidence with zero second business effect, obligation or reservation. [Wiring](../../wiring.md) · [P5](../../postgres-evidence.md) |
| R2.4 · Response evidence | Split commit, prepared, trusted handoff and positive client acknowledgment. Lost-acknowledged counts require positive client proof. Cut before enqueue, after enqueue with and without evidence, and after independent client receipt. [Records](../../witness-records.md) · [Cut tests](../../proof-evidence.md) |

All runtime scenarios remain planned. Approved owner constraints, recorded deferrals and choice status are preserved. [Decision status](../../decisions.md)
