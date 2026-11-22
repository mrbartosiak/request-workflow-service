# B13 handoff for B14, B15 and B17

Candidate `337a9d977cf32d8370287370e3d532cdb885e934`, tree `0ea4f8d3862ba26eed863a323150ccfac383937d`. [Assignment](assignment.md) · [Observation contract](../../../Phase3-LLD/observations.md) · [B12 guarded release](../B12/integration.md)

## Configure the synthetic route

- Apply `JdbcWorkflowMigrations.migrateActionContext()` as the schema owner. V007 retains the V006 grant values and permits independent typed `ROW_READ`, `FIELD_READ_BODY`, `FIELD_READ_NAME`, `CONTEXT_<action>` and `EXECUTE_<action>` entries. All of the added grants start closed. Use the B10 scope operator, original transaction witness and audited confirmation to enable or revoke them.
- Supply `ObservationCodec.Keys` in `SyntheticEffectiveAccessConfiguration.Settings`. The effective profile authenticates and validates the route before using the service. A valid request defers when observation configuration is unavailable. The standalone Create profile retains its existing configuration and routes.
- The synthetic body and name predicates honor the existing B10 field mask and require current assignment. A visible unassigned actor has a safe denial. A scoped approver can Accept when assigned, holds the separate body, context and execution grants, and resolves to a different stable person from the originating requester. Production directory and type-specific fact mappings remain with their original decision owners.

## Call the verifier for a new effect

Obtain the service from `SyntheticEffectiveAccessConfiguration.Components.actionContext()`. From the command's owned workflow transaction, arbitrate the receipt first. Call `verifyNewIntent(actor, ref, action, exactExpectedRevision)` for a new existing-request intent. It acquires the exclusive organization guard for that transaction, proves current scope and writer, establishes target visibility and permissions, and compares the observation to current facts and workflow eligibility. Keep the acquired guard through effect and receipt commit. A successful `Verified` carries the exact request snapshot, current guard and admission for the application command coordinator. Existing command transaction and scope proof failure handling still applies.

| Verification result | Wire disposition |
| --- | --- |
| `CONCEALED` | 404 `not_found` |
| `DENIED` | 403 `action_denied` |
| `MALFORMED` | 400 `invalid_input` for `expectedRevision` |
| `STALE` | 409 `stale_observation` |
| `ILLEGAL_STATE` | 409 `illegal_state` |
| Unproved authority | Safe service deferral |

The existing validator rejects plain decimal display revisions at the HTTP edge. The verifier evaluates token syntax and authenticity after current target and grant checks. Pass the exact received string through the intent digest. A committed exact replay follows B12's proof of the original receipt and its current guarded disclosure. New-intent verification belongs to the effect path. Keep detail, replay and result projections free of observations.

## Key ownership and time

The pinned Java 25 runtime provides AES-256-GCM with a 96-bit random nonce, 128-bit tag and action-context-specific associated data. The authenticated header carries the immutable format and key ID. The encrypted fixed-length payload binds the exact current coordinates and includes trusted issuance and expiry. The digest covers the stable actor, current organization and certified policy, target identity and type, action, writer ID and generation, request revision, assignment, visibility, required fact version and exact UTF-8 value. The synthetic fact version is the request revision. Tokens are valid from issuance up to the exclusive 15-minute expiry boundary.

Provision dedicated random 256-bit keys apart from operation signing and intent digest keys. Set one active key for sealing and retain the retiring verification key through the expiry of its final outstanding observation. Keep IDs immutable, rotate before 2^32 total seals with any key across owning processes, and exclude retired keys from future issuance. Operations owns aggregate issuance accounting and rotation before production activation. No corporate key custody or recovery protocol is enabled by this synthetic wiring.
