# B13–B15 · Non-Publish workflow commands

### B13 · Action context and observation token

**Needs** B03, B08, B10 and B12. **Sources** Context fields, grants, facts and observation contract. [Observations](../Phase3-LLD/observations.md) · [REST](../Phase3-LLD/rest.md) · [Access](../Phase3-LLD/access.md)

**Red** Use action-specific context grants, deny when a material field is inaccessible, reject visible ineligible and deleted/concealed targets with correct safe outcomes. Reject wrong action/actor/ref/epoch, expired token, plain display revision and changed fact for a new effect. **Green** Protected context route with current proof and shared release phase, and approved vetted AEAD codec purpose, bounds, key ID/rotation and exact bound scope/version tuple in synthetic environment. **Stop** Capture permitted contexts, sealed token behavior, no disclosure and stale/denial precedence. Hand observation verifier to B14/B15/B17.

### B14 · Edit, Verify and Accept

**Needs** B02, B12 and B13. **Sources** Allowed transitions, rechecked authorization, state/observation order and atomic writes. [Domain](../Phase3-LLD/domain.md) · [Access](../Phase3-LLD/access.md) · [Persistence](../Phase3-LLD/persistence.md) · [Scope continuity](../Phase3-LLD/authority-continuity.md) · [Confirmation gates](../Phase3-LLD/proof-resolution.md#resolver-and-gates)

**Red** Call real routes with approved contexts. Deny same-human Accept under alternate logins or reassignment, missing Verify grant and wrong action/changed observations. Permit a separate eligible approver. Require equal distinct Edit revision advance, zero Edit history, stable name and exact retry result. For an eligible change to access or masks, withhold scope confirmation after commit. Assert one durable effect and witness. Keep protected release, replay and the dispatch admission port closed until original proof arrives. Confirm eligibility without an extra effect. **Green** Compose policy and lifecycle effects under guards and fresh context checks. For a state, body or fact change that alters access or masks, prepare the ordered scope delta before effects, write its witness with state and receipt on the command connection, and confirm the original lineage before protected release. A pure revision-only edit needs no scope delta. **Stop** Verify before/after revision, origin, body, history, receipts and authorization negatives. Hand command path and eligible states to B15/B17/B35.

### B15 · Delete and Reject

**Needs** B12, B13 and B14. **Sources** Reason rules, immediate Delete mask and journaled scope effects. [Domain](../Phase3-LLD/domain.md) · [Retention](../Phase3-LLD/retention.md) · [Authority](../Phase3-LLD/authority-continuity.md)

**Red** Exercise required reasons, Delete from CREATED, Reject from VERIFIED/ACCEPTED, terminal conflicts and replay. Force Delete versus the guarded projection/context component, assert immediate concealment and inaccessible pre-delete results. Exercise the real detail route in B36 after B19. **Green** Prepare applicable scope deltas before effects. Commit protected reasons, terminal fact, changed visibility, receipt and applicable scope witness on the command connection. Require original-lineage confirmation before protected release and enforce approved deleted metadata and result omission. **Stop** Capture reason row, hide/mask results and contiguous state history. Hand terminal fixtures to B17/B19–B21/B30.
