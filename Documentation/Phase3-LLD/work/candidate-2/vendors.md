# Vendor assessments and integration conformance

**Scope** A-06, F-11, Q-08/09, R-04/05, DEC-03/09/10. **Status** Signed HLD vendor facts and assessments are carried forward. Live priorities and commitments remain open. [HLD assessment and primary citations](../../../Phase2-HLD/vendor-assessment.md)

| Tool | Business value and candidate flow | Ease and live proof gate |
| --- | --- | --- |
| Workday | Read minimal approved worker/org evidence for HR routing. Measure routing accuracy or reassignment reduction. | Conditional medium. Confirm Staffing API version, per-field domain scope, identity mapping and entitlement. Fall back to manual routing for unproved evidence. |
| Jira | Create linked issue for eligible engineering handoff with safe summary and correlation. Track manual handoffs, lost links and duplicates. | Moderate integration, hard ambiguity proof. Confirm edition, project, least privileges, throttling and webhook identity. An empty Cloud search after a lost creation response gives no proof of absence. |
| ShiftLeft | Attach permitted scan/finding/revision evidence to remediation or exception requests. Measure evidence-link latency and duplicates. | Conditional on tenant product identity (Qwiet/Harness lineage), version, retained finding IDs, bearer scope and reconciliation lookup. Prefer approved read-side use initially. |
| SonarQube | Attach branch/project/revision-bound quality gate reference. Measure evidence-link latency and stale matches. | Conditional on Server/Cloud, verified callbacks, precise analysis identity and authoritative Web API reconciliation. |
| Sonatype | Link Lifecycle report/application/component/policy evidence for dependency exceptions. Nexus artifact lookup is separate. | Conditional on exact product, overlap with ShiftLeft, scopes and retained report evidence. Lifecycle webhook failures need approved independent reconciliation for completeness. |

No new vendor capability is assumed. For every candidate require tenant confirmation of product/edition, sandbox, stable mappings, privileges, allowed data, rate and retention, contract drift detection, authoritative read/at-most-one proof (where needed) and business ownership before any live work. [Delivery](delivery.md) · [HLD product follow-ups](../../../Phase2-HLD/product-follow-ups.md)

## Fake connector and extension evidence

Use controllable barriers, injected clocks and in-memory fake vendor effect records whose independent counters and durable application records are inspected. Trigger one fixture-only obligation, after Publish on one configured fixture type. Replace the fake binding with a second implementation that supplies the same contract and one controllable capability change (stable-key deduplication enabled versus absent); verify only adapter/wiring and fixtures change. Add an Accept policy requiring a local evidence fact with version binding and revive eligibility by updating it. Re-run full lifecycle and retry checks across all types. Report edited modules, contracts, migration impact, deployment changes and untouched core files. The fixture introduces no live trigger. [Spring wiring](spring.md) · [Observations](observations.md)

| Case | Verify durable outcome |
| --- | --- |
| Failure before business commit; crash after commit before dispatch | Zero eligible work before commit; exactly one obligation picked up after restart. |
| Pause old worker after final authorization, expire lease, replacement lookup empty, then release old worker. Reverse case after old write applied and response lost. | With verified stable-key proof, at most one remote effect in both orders. Without proof, quarantine old ambiguity and no second send. |
| Revoke before authorization or after authorization during transmission | First case zero sends. Second retains uncertainty and reconciles safely. |
| Timeout, rate limit, permanent drift and stalled ordered successor | Safe bounded retry only when no effect or proof, visible quarantine and no unrelated destination blockage. |
| Inbound duplicate, changed digest under same identity, late/older, missing notification, wrong tenant, restart or restore | One dedup receipt/effect, collision quarantine without extra effect, no older overwrite, covered reconciliation checkpoint, no unauthorized effect. Renew only when safe. |

For live acceptance repeat with tenant-proven contracts and approved scenarios. Collect timing, client and durable records, remote effect counts, lease/cancellation transitions and reconciliation proof. [Q-09](../../../Phase1-conceptual/quality-acceptance.md#q-09-reliable-integration-delivery)
