# B09–B12 · Effective scope proof and guarded release

### B09 · Scope prepare, abort and witness

**Needs** B05 and B06. **Sources** Exact delta, attempt ID, ordered journal and local effective point. [Authority](../../../Phase3-LLD/authority-continuity.md) · [Preparation](../../../Phase3-LLD/proof-resolution.md#scope-preparation) · [Witnesses](../../../Phase3-LLD/witness-records.md)

**Red** Drop prepare reply before effects, then close before delayed prepare. Require idempotent exact repeats, conflicting digest denial, no reopening and closed scope on untrusted rollback or gaps. **Green** Stable attempt IDs, independent durable prepare/terminal closure adapter, protected scoped delta and Spring-bound witness insertion under the exclusive guard. **Stop** Capture local and independent record diffs, scope/sequence/generation and rollback proof. Hand verified protocols to B10/B11.

### B10 · Effective grant, assignment and visibility updates

**Needs** B06 and B09. **Sources** Shared/exclusive ordering, current authority and retention ordering. [Access](../../../Phase3-LLD/access.md) · [Continuity](../../../Phase3-LLD/authority-continuity.md) · [Retention](../../../Phase3-LLD/retention.md)

**Red** Revoke grant, move assignment and update security fact while a protected action waits or releases. Verify prior writer or release wins under the guard and current field membership drives projection. **Green** Versioned grant/assignment/fact update service using the ordered scope journal and fresh proof, restricted to synthetic roles, with fail-closed checkpoints. **Stop** Capture versions and permit/deny ordering. Hand current grant/fact readers to B12/B13–B15/B19–B21.

### B11 · Audited resolver and confirmation projection

**Needs** B09. **Sources** Privileged resolver, original-lineage proof and gap handling. [Resolver](../../../Phase3-LLD/proof-resolution.md#resolver-and-gates) · [Continuity](../../../Phase3-LLD/authority-continuity.md)

**Red** With public scope closed, a permitted operator fixture resolves a named authentic witness. Reject mismatched or rewound evidence, preserve gaps and prove zero new workflow or dispatch effects. **Green** Read certified original lineage through proof ports. Verify attempt, epoch and exact digest, apply idempotent terminal resolution and guard projection rebuild. **Stop** Demonstrate safe audit, operator scope and no public protected data. Hand reusable verifier to B12/B16/B29–B31.

### B12 · Guarded result replay and release evidence

**Needs** B08, B10 and B11. **Sources** Exact replay/expiry/fencing, disclosure phase and evidence states. [Retries](../../../Phase3-LLD/retries.md) · [Disclosure](../../../Phase3-LLD/access.md) · [Proof cuts](../../../Phase3-LLD/proof-evidence.md)

**Red** Race expiry/revoke before release. Compare untrusted, missing, purged and concealed past receipts via response and lookup counters. Force buffer failure and separate commit, preparation, trusted enqueue and positive client acknowledgment. **Green** Complete `DisclosureGate` shared fresh proof through bounded handoff, classify denial/unknown/retry safely, and add minimized independent release records with authorization and evidence-based states. **Stop** Capture normalized response and header comparisons, witness counts and no repeat effects. Hand shared result and read release service to B14–B21, B31 and B40.
