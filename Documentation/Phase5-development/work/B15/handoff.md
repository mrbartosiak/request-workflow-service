# B15 terminal commands and projection handoff

Candidate `e0f256cc9f2d2f095818257310a67cf18a4c1a72`, tree `4ac9a409269f82502037a2721a341ace2aa4d6ea`. [Assignment](assignment.md) · [Candidate evidence](evidence-e0f256c.md)

## Terminal commands

The effective synthetic profile serves `POST /requests/{ref}/actions/delete` and `POST /requests/{ref}/actions/reject`. Run `JdbcWorkflowMigrations.migrateTerminalRequestActions()` and give the command role `INSERT` on `requests.transition_reason` alongside its B14 privileges. Use the B12 guarded replay ports and B13 observation keys. The existing validator accepts exact UTF-8 reasons up to 2,048 bytes and rejects missing, null, malformed or blank reasons before receipt arbitration.

Delete advances CREATED to DELETED. Reject advances VERIFIED or ACCEPTED to REJECTED. Each writes one fact, one protected reason and one result receipt with the state and revision. The fact records the request revision and the contiguous transition sequence independently. Reject retains current row visibility under the synthetic policy. Delete writes the new field mask and exact scope witness in the same transaction. The public command defers while the original Delete witness is pending. The audited resolver confirms that attempt and projects the checkpoint. [Lifecycle](../../../Phase3-LLD/domain.md) · [Authority confirmation](../B12/integration.md)

## B17 and B19–B21

- B17 can arbitrate Publish on ACCEPTED under the same organization guard. A Reject winner makes a previously issued Publish observation stale for a still-disclosable target. Use the independent publication proof and obligation path for Publish.
- B19 and B21 can call `CurrentAccess.permittedFields` under their shared organization guard for the synthetic deleted visibility decision. It returns an empty set without current row scope and typed `DELETED_METADATA`. The current allowed deleted projection is `REF` and `STATE`, subject to the retained mask. B20 owns a separate history grant and its masked projection.
- Delete replay also requires the typed `RESULT_READ_DELETE`, current Delete context and execution grants and the four minimum receipt fields. The signed original key and exact intent can recover those fields after observation expiry. The same authorized key with changed input can yield a conflict. An authorized active key with no retained receipt encounters the terminal-state conflict. Action context remains concealed. Receipts for Create and other pre-delete actions are concealed once the target is deleted. [Retry contract](../../../Phase3-LLD/retries.md) · [Deleted metadata](../../../Phase3-LLD/queries.md)

## B30 protected retention

`requests.transition_reason` uses `(ref, sequence)` and a scoped foreign key to the matching terminal fact. The payload is exact UTF-8, starts as `RETAINED` and can support a future `PURGED` state with a null reason. Delete preserves the retained body and original name in storage under the adopted retention model. Its state and certified field mask suppress both at public release. The terminal response encodes only `ref`, `action`, `resultRevision` and `state`. The reasons and protected fields remain available to the separately authorized retention slice. [Retention](../../../Phase3-LLD/retention.md)
