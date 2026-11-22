# Action observations

**Scope** F-02–05/08, Q-02/03/13, DEC-04/08/12. **Status** Action-bound, fresh proof is approved. Exact contents and token format are proposed. [HLD observations](../../../Phase2-HLD/rest-and-browsing.md#observed-revision-contract)

`ActionContext` returns ref, action, state, authorized decision context below and an opaque `expectedRevision` if the operation, visibility and all required context grants pass and the current state permits the action. If policy, fact authority or decision fields are unavailable return the safe denial or availability outcome. For an otherwise eligible viewer of an ineligible action return ILLEGAL_STATE with no token. Detail and business outcomes never mint action tokens under this candidate.

| Action | Minimum visible decision context |
| --- | --- |
| Edit | Current body and state |
| Verify | Name, body, state and required verification evidence projection |
| Accept | Name, body, state and required approval evidence projection. Compare stable person keys server-side. |
| Reject | Name, body, state and required approval evidence projection |
| Delete | Name, body and state |
| Publish | Name, body, state and required publication evidence projection |

All views include the action-specific, approved consequential policy facts where relevant, as safe projections rather than raw identity/evidence records. Type policy declares versioned dependencies and permitted context projection. If a consequential fact cannot be observed by the actor as approved decision context, withhold the token and deny the action. A type extension must update declarations and tests before enablement. [Access](access.md)

`ObservationCodec` issues an authenticated encrypted token containing format, issuer, organization, principal, scope, target ref, action, writer generation, request revision, scope policy version, required fact versions (assignment, approvals, vendor evidence as applicable), expiry and canonical context contract version. Use a vetted standard authenticated-encryption primitive through the JDK, with separate keys/purposes and unique nonces per key, bounded parsing and key-identification inside the envelope. Key/nonce lifecycle is an approval and startup gate. No client interpretation. A bounded initial value of 15 minutes is proposed. Recheck all binding fields and versions under the transaction's locks for a new command. Reject stale or expired but valid observations with STALE_OBSERVATION. Validate fact freshness even if versions match. Time-driven eligibility changes are checked at effect time.

A matching receipt can replay with identical original token bytes after that token expires or its observation generation is fenced, subject to current grants and operation-key policy. Early validation bounds token syntax. New execution then verifies authenticity and age, while exact replay compares original opaque token bytes to the stored intent digest. Fetch fresh context before deciding on a new command. [Retry timing](retries.md) · [Recovery](recovery.md)
