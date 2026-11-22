# Action-context observations

**Scope** F-02–05/08, Q-02/03/13, DEC-04/08/12. D3-R adopts action-specific context and observed preconditions for new existing-request writes. Production field grants and codec/key details remain open. [REST](rest.md) · [Identity](access.md)

`ActionContextService` selects current organization, target, action, state, revision and approved facts. Require visibility, `CONTEXT(action)`, `EXECUTE(action)` and access to every required fact. Check target proof before examining state for any response. Return `{ref,action,state,context,expectedRevision}` only via the context route. An authorized ineligible state returns 409 `illegal_state` without a token. Unavailable proof returns safe 503 for a disclosable target. A denied view uses ordinary 403 or 404. Detail, replay and command results issue no token.

| Action | Initial `context` fields in addition to top-level state |
| --- | --- |
| Edit, Verify, Accept | `body`. Add only declared, approved decision facts for the action/type, such as authorized approval evidence on Accept. |
| Reject, Delete, Publish | `name`. Add required permitted type facts where approved. Reason is new command input. |

`ObservationCodec` emits a bounded, authenticated encrypted opaque token. Bind format/key ID, issuer, stable principal, organization/scope, type, target ref, action, writer epoch, request revision, assignment and policy versions, declared material fact versions, issuance and expiry. Use a vetted authenticated-encryption primitive with purpose-specific keys and safe nonce and rotation rules, pinned and tested before enablement. The reference window is 15 minutes. Token confidentiality does not extend actor grants. Placement of type-specific facts and grants is gated by D2.

Under the exclusive mutation guard, check target, action, actor, epoch, expiry and bound versions against current records. Verify required fact freshness, workflow and requester comparison. A valid envelope for the wrong action/actor or changed/expired facts yields 409 `stale_observation` for an authorized disclosable new command. Denied/concealed targets use ordinary access results before token details. Plain display revisions fail token syntax at entry. Unproved authority defers. Reobserve for new intent. Create carries no observation.

Possession of the original observation bytes permits intent comparison for a trusted exact retry under the key policy and fresh disclosure. A replay does not authorize a new write with the token. Acquire the shared disclosure guard before projection and hold it through handoff. Delete/purge/revoke winners can remove fields and any token. Restore epoch advance prevents an old token from matching divergent same-numbered revisions. [Retries](retries.md) · [Release rule](access.md) · [Tests](restore-evidence.md)
