# Phase 3 owner decision slate

**Revision** PH3-OC-r2. **Status** Proposed for Reviewer 1 signoff before owner presentation. Each recommendation is an explicit approval request. Both candidate signoffs cover design and planned evidence. [Review manifest](comparison-manifest.md) · [Approval gates](../../owner-approvals.md)

## One-form choices

Use one form for D1–D6. Select **approve recommendation**, **approve with named alternatives**, or **defer group**. Select one value per component and record any approval limits. D5 has six independently selectable choices. Unanswered items stay open. The form must carry the explicit combination and dependency rules. [Form rules](comparison-form-rules.md)

| Group | Recommendation the owner can approve | Alternatives and bounded deferral |
| --- | --- | --- |
| D1 · Transactions, retries and identifier proof | D1-R. Ordered transactions, signed keys, one shared initial Publish namespace and per-attempt witness. PostgreSQL evidence only. | D1-L conditional writes, D1-S scope-before-key, D1-K client keys, D1-I reserved ranges or D1-C derived Create ref. D1-D defers adoption and permits design and PostgreSQL proof planning. [Transactions](comparison-consistency.md) · [Identifiers](comparison-identifiers.md) |
| D2 · Identity, retention and disclosure | D2-R. Locally ordered grants with current-authority proof, timed protected payload retention and masked history. | D2-M verified mirror, D2-E terminal minimization or D2-A protected archive. D2-D keeps protected corporate scope and policies open with synthetic fixtures only. [Access and retention](comparison-access.md) |
| D3 · Public wire and browsing | D3-R. Named action routes, action-bound context, stable Problem Details, live keyset and masked history. | D3-T one transition route, D3-E compact errors, or D3-H shorter history item. D3-D permits contract fixtures and defers the client baseline. [Wire contract](comparison-wire.md) |
| D4 · Delivery and module boundaries | D4-R. Application-owned atomic outbox append, separate worker pools, fake connector proof. | D4-M five modules, D4-W per-instance writer cap or D4-B broker relay. D4-D keeps ports and fake scenarios while deferring worker/storage details. [Delivery](comparison-delivery.md) |
| D5 · Tooling | D5-R. Explicit Java contracts and mapping, CI-only API diff and test-only boundary pilots. | T1–T6 each offer a recommendation, alternate trial or manual route, and an explicit deferral. D5-D defers tool adoption and keeps manual checks. [Tooling choices](comparison-tooling.md) |
| D6 · Evidence, gates and recovery | D6-R. Short authorized smoke when runnable, protected logging and risk gates, backup/log rehearsal first. | D6-G alternate spool thresholds, D6-C alternate coverage floor or D6-W warm rehearsal first. D6-D keeps test plans pending approved settings. [Evidence and operations](comparison-operations.md) |

**Scope already settled.** Shared interview lifecycle, complete history, browse default 10, current authorization, distinct-person Accept and independent Verify and Publish bind. Publish IDs are positive signed 64-bit, lossless decimal strings, unique across initial types and increasing within explicitly defined stable namespaces. D1-R proposes one shared initial namespace. Required performance, access and recovery outcomes stay fixed. The owner delegates reasonable retention, browsing and recovery tuning within approved policies. [Kickoff](../../owner-kickoff.md) · [Identifiers](../../owner-identifier-requirements.md)

**Restore decisions.** D1 needs both increasing assignment at durable commit and proof that a previously published request keeps its original ID. D2 separately requires proof of current access before restored disclosure or writes. An old key can be fenced while a surviving exact receipt remains eligible under current grants. [Identifier proofs](comparison-identifiers.md) · [Access proof](comparison-access.md)

**Default deferrals.** Production database and storage, live vendor priority/scope, production proof-store topology, RPO, permitted loss and named operational authority remain open. The separately authorized quick check is unmeasured because the workspace lacks a runnable target. Record adopted alternatives, date, rationale, scope, linked artifacts and remaining blockers. After exact final-package review, ask separately for Phase 4 handoff. [Performance readiness](performance-readiness.md) · [Handoff gates](../../owner-approvals.md#phase-4-handoff)

[Candidate 1 signoff](candidate-1-r2-signoff.md) · [Candidate 2 signoff](candidate-2-r2-signoff.md) · [Signed HLD decisions](../../../Phase2-HLD/decision-proposals.md)
