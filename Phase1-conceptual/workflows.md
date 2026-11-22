# Request workflows

## Interview lifecycle

The state diagram on page 2 of the interview brief governs this table. `Start` represents creation of a new request.

| Current state | Action | Resulting state | Required condition or effect |
| --- | --- | --- | --- |
| Start | Create | `CREATED` | A name and body are supplied. |
| `CREATED` | Delete | `DELETED` | A reason is supplied. |
| `CREATED` | Verify | `VERIFIED` | — |
| `VERIFIED` | Reject | `REJECTED` | A reason is supplied. |
| `VERIFIED` | Accept | `ACCEPTED` | — |
| `ACCEPTED` | Reject | `REJECTED` | A reason is supplied. |
| `ACCEPTED` | Publish | `PUBLISHED` | Assign the request a unique numeric publication identifier at publication. |

`DELETED`, `REJECTED`, and `PUBLISHED` are terminal states. Reject any transition absent from the table or attempted without its required input. A failed attempt has no state change. In particular, an already accepted request may still be rejected, and only a created request may be deleted.

## Body editing

Allow a body edit only in `CREATED` or `VERIFIED` when it takes effect. A successful edit preserves state. DEC-06 fixes the name as immutable and requires nonblank mandatory text, with exact validation settings still open. [Decision register](decisions.md#dec-06-name-editing-and-input-rules)

## Access and workflow checks

For a new action, check workflow eligibility and actor authorization independently. DEC-04 uses scoped role/group grants. DEC-12 gates Accept and Reject with scoped grants, requires another authorized person for Accept and independently authorizes Verify and Publish. Exact grants, freshness and further approval policies remain open. [Access and approvals](../Phase2-HLD/access-and-approvals.md)

## Retries and concurrent actions

A recognized replay of an originally successful logical action preserves the original business effect across service instances. Any changes from that action take effect once, with one record per state change. Enforce idempotency at the database level. Apply the applicable access policy before disclosing an earlier result. For example, when the result of a successful Publish is disclosed on replay, it includes the original publication identifier. Any newly initiated Publish against a `PUBLISHED` request is rejected under the interview workflow.

Evaluate new actions on existing requests against their observed revision, current state and required conditions at effect time. Concurrent successes must form a legal sequence. DEC-08 approves scoped keys and atomic receipts. Exact wire, expiry and receipt settings remain open. [Replay](../Phase2-HLD/retries-and-failure-timing.md)

## Corporate workflows

Use the interview lifecycle for initial corporate requests, including HR tickets, bug reports, and other internal requests. Corporate variants are deferred. Future variants may only add to the shared lifecycle and must retain all existing states. Specific extensions and additional approval policies still require owner approval. [DEC-02 and DEC-12](decisions.md)
