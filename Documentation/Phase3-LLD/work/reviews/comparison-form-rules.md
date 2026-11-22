# Single owner form rules

**Scope** A-08 and all six owner approval groups. Use these rules with the decision slate in the same persistent form. Options are proposals until explicitly selected and recorded. [Decision slate](comparison.md) · [Approval gates](../../owner-approvals.md)

## How selections combine

- **Approve recommendation** selects that group's R baseline. **Approve with named alternatives** selects the same baseline with the stated component replacements. **Defer** leaves that group open and selects its D bounds. Exclude R/alternatives when the same group is deferred. A blank group gives no approval.
- Select at most one value per component. Independent codes in different components may combine. Record unknown or conflicting selections as open for the affected component and retain only the compatible explicit choices. The form must never silently choose between alternatives.
- For D5, record R, A or D for each T1–T6. D5-R applies the six listed recommendations unless explicitly overridden by item. D5-D selects all six deferrals and excludes individual conflicting choices. Blank standalone tooling items remain open.

| Group | Selectable components and valid combinations |
| --- | --- |
| D1 | Transaction strategy is one of the default, D1-L or D1-S. Key strategy D1-K, Publish allocation D1-I and Create reference D1-C each replace a different part and may combine with a selected strategy. D1-D defers all mechanisms including the single-namespace construction, while binding DEC-13 guarantees continue. |
| D2 | Directory choice D2-M and payload policy are independent. For payload, choose timed retention by default, D2-E terminal minimization or D2-A protected archive. D2-E and D2-A cannot combine. |
| D3 | D3-T routes, D3-E error envelope and D3-H history shape are independent. D1 and D2 dependencies still apply. |
| D4 | D4-M module layout, D4-W worker limit and D4-B transport each replace a different part. Keep atomic append, proof-gated sending and full destination/tenant identity in all combinations. |
| D5 | Each T item is independent. Apply the wire baseline rule below to T3. |
| D6 | D6-G changes spool thresholds only. D6-C changes the critical branch floor only. Either can combine with the other or D6-W rehearsal order. |

## Dependent approvals

An unanswered prerequisite leaves the same dependencies conditional as an explicit deferral.

- **D1-D + D3-R (or D3 alternative).** Route style, validation, exact filters and other independent shape can be approved. Key intake, issuer endpoint, replay/fence/expiry contracts and generation behavior remain conditional. D1-K supplies its own alternate intake and status rules if selected. [Wire](comparison-wire.md) · [Client UUID protocol](comparison-client-keys.md)
- **D2-D + D3-R (or D3 alternative).** Independent route/shape decisions can be approved. Production action context, per-field visibility, protected output, authorization-dependent errors and replay release remain conditional pending access/retention/current-authority decisions. Binding lifecycle, masks and authorized browse semantics still apply. [Access](comparison-access.md)
- **D3-D + T3-R.** Use a seeded OpenAPI fixture to pilot the diff. A mandatory production breaking-change gate requires an approved released OpenAPI baseline. T3-R does not select the deferred wire. [Tooling](comparison-tooling.md)
- **D4-D + T6-R.** Use the signed fixture graph for a boundary-test pilot. Production package gates need the approved graph. Tool pilots do not select deployments or runtime dependencies. [Delivery](comparison-delivery.md) · [Tooling](comparison-tooling.md)

Record the exact comparison revision, selected base/group and alternative/item codes, explicit conditions, decision date and rationale. Record unresolved or deferred components, their implementation effect, and the evidence or separate approval needed to reopen them. [Review manifest](comparison-manifest.md)
