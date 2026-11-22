# Acceptance and design evidence

**Scope** Intended acceptance evidence. **Status** Fixed thresholds and the DEC-07, DEC-10 and DEC-14 directions are approved. Detailed profiles, fixtures and gate settings remain open. [Phase 1 quality acceptance](../Phase1-conceptual/quality-acceptance.md) · [Decision record](decision-proposals.md)

## Performance

**Required.** Sustain 100 requests per second across multiple service instances. Every measured request completes in strictly less than 3 seconds. At that load, at least 95% of requests for each core operation complete within 500 ms under the agreed workload. A missed response, timeout, or any result taking 3 seconds or more fails the per-request criterion. Process the expected valid traffic at the offered load. Report each Create, Edit, Verify, Accept, Reject, Delete, Publish and Browse operation separately.

**Approved method.** Independently pace 100 requests per second across at least two instances. Measure end to end and report per operation. The [performance test plan](performance-test-plan.md) provides the procedure and report template for a separately authorized run. The profile remains open.

## Regression and conformance evidence

| Requirement | Observable test outcome |
| --- | --- |
| F-01 through F-06, Q-03, Q-11 | Test legal and invalid state/action pairs for each initial type, terminal behavior, body edits, reason input and numeric assignment. History starts at `CREATED` and records committed changes in order after restart. Invalid transitions, state-preserving edits and replays add no history entry. |
| Q-02, Q-03 | Test across instances: matching/conflicting keys, independent identical Creates, concurrent Publishes on distinct requests, Publish/Reject, Edit/Accept and two Edits with one revision. Exercise missing or stale tokens, receipts/expiry, rollback, lost response and uncertain commit. Change only a bound decision fact and require re-observation before any new effect. After restore, reuse a revision counter on divergent facts and verify the old token fails on a new command. |
| Q-04 | Reject malformed, missing, blank mandatory or unrepresentable values at entry, and reject name edits. Verify zero mutation attempts. Round-trip approved punctuation and multilingual text where access and retention allow. If PostgreSQL text is selected, include embedded U+0000. |
| F-07, Q-12 | Test default 10, subsequent pages, ties, exact case-sensitive and combined filters, no match and terminal visibility. Hidden rows or filter fields cannot affect matching or pagination. Test changed grants between pages and invalid continuation scope. |
| Q-13 | Check zero unauthorized reads or changes across detail, actions, browse, history and replay. Deny self-Accept with a grant, after reassignment and through another login for the same person. Allow another eligible person. Verify independent Verify/Publish grants. Deny history without its grant and mask stored actor/reason in authorized history. Revoke context between selection and release. Suppress protected data and token. Test grant changes before commit and disclosure. Denials add no business writes or history. |
| Q-08 | DEC-10 approves adding a test approval policy, replacing a fake connector and rerunning lifecycle checks. Report changed modules, contracts and deployments, including dependent configurations or migrations. Exact fixtures remain open. |
| Q-09, when live connector approved | Inject throttling, duplicate, stale and missing messages, unknown remote outcomes, and restart or restore. Verify local and remote effect counts, status, reconciliation and permitted replay. |

Retain scenario, versions, timing, expected result, client observations and correlated durable effects. Use database-backed evidence for durability and multicopy invariants.

**Required under Q-07.** Keep complexity low, verify feature behavior, report coverage and use TDD in Phase 5. Gate agreed critical paths on complexity, coverage, module boundaries and regressions under DEC-14. Exact thresholds and exception authority remain open. [Operating policies](operating-policy-alternatives.md) · [Traceability](traceability.md)
