# Owner decision register

The project owner recorded Phase 2 selections on **2026-11-22 (Europe/Warsaw)**. Phase 3 handoff requires separate approval. [Outcomes, rationale and follow-ups](../Phase2-HLD/decision-proposals.md)

## DEC-01 Optional interview features

**Approved 2026-11-21.** Include complete state-change history and browsing by name and state with default page size 10. [F-06/F-07](features.md#owner-approved-interview-features)

## DEC-02 Interview lifecycle and corporate variants

**Approved 2026-11-21.** Every initial type uses the exact interview lifecycle. Future variants are deferred. Any approved variant may only add to the lifecycle and must retain all existing states. [Workflows](workflows.md)

## DEC-03 Database and initial vendor delivery

**Deferred 2026-11-21 and reaffirmed 2026-11-22.** “Keep database and integration priorities open”. SQL or NoSQL remain eligible. PostgreSQL is a later retry-test candidate. Live scope remains open. Keep all five assessments and the source-only requirement. [Integrations](integrations.md)

## DEC-04 Identity, roles, and access

**Prior target approved.** Zero unauthorized reads or changes. **Direction approved.** Stable corporate identity, role/group grants scoped by ownership, assignment and request boundaries, plus separate field, context and service grants. Order local revocation with writes and required dispatch authorization. Recheck before disclosure and deny if required freshness is unavailable. [Q-13](quality-acceptance.md#q-13-access-isolation)

## DEC-05 Deleted data and retention

**Direction approved.** Preserve complete ordered lifecycle facts, restrict deleted payloads by default and govern body, reason and replay retention/redaction separately.

## DEC-06 Name editing and input rules

**Direction approved.** Immutable name, nonblank mandatory text and REST-edge validation. Preserve supported punctuation and multilingual text. Exact bounds and formats remain open. [REST](../Phase2-HLD/rest-and-browsing.md)

## DEC-07 Performance evidence

**Prior typical target approved.** At 100 rps, at least 95% within 500 ms per core operation. **Method approved.** Prepare independently paced end-to-end tests across at least two instances with per-operation results. Run only on explicit request outside routine builds or steps. [Q-01](quality-acceptance.md#q-01-capacity-and-response-latency) · [Plan](../Phase2-HLD/performance-test-plan.md)

## DEC-08 Retry, idempotency, and error contracts

**Direction approved.** Require observed revisions and authorized versioned context for existing writes. Use scoped keys, atomic minimal receipts, replay authorization, expired-key blocking and database resolution of uncertain commits. Commit durable delivery obligations, gate remote retries on proof, quarantine unresolved effects and deduplicate inbound events.

## DEC-09 Vendor identities and commitments

**Open as reaffirmed.** Exact products, priorities, live scope, data authority, credentials and operational commitments require later approval. Keep the five assessments and common contract. [Assessment](../Phase2-HLD/vendor-assessment.md)

## DEC-10 Shared core and extension structure

**Approved.** Ship composed core and extensions as separate modules in one service release. Bound delivery in that deployment off the REST path. Add a test approval policy, replace a fake connector, rerun lifecycle checks and report changed modules, contracts and deployments. [Architecture](../Phase2-HLD/architecture.md)

## DEC-11 History and browsing behavior

**Direction approved.** Exact case-sensitive name, exact current state, AND, newest-first live cursors, filter-field authorization before matching and default size 10. Store Start → `CREATED` then ordered committed changes. Deliver distinct-grant history and withhold actor/reason until exact grants are approved.

## DEC-12 Approval policy

**Direction approved.** Scope Accept and Reject under the existing lifecycle. Require another authorized person for Accept. Authorize Verify and Publish independently. Additional separation, delegation and quorum remain open. [Approvals](../Phase2-HLD/access-and-approvals.md#approval-decisions)

## DEC-13 Publication identifier contract

**Direction approved.** Opaque request reference at Create, distinct unique numeric ID at effective Publish, gaps allowed and no ordering guarantee. Exact formats and recovery proof remain open. [Identifiers](../Phase2-HLD/workflow-and-history.md)

## DEC-14 Quality and operational policies

**Prior target approved.** Verified restore within 60 minutes. **Direction approved.** Protected bounded logs with alerts and admission restriction before exhaustion. Scheduled and prerelease Spring/Snyk risk-based gates. Critical-path test gates. Restore and staged resumption drills, fencing, invariant checks, loss reporting and operator audit. [Q-10](quality-acceptance.md#q-10-operational-recovery)
