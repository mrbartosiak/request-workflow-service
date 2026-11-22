# Request Management Service · High-Level Design

Build an internal REST backend for HR tickets, bug reports, and other requests that require approval. The service uses Java 25 and Spring Framework 7.0.11. Every initial request type follows the same lifecycle, with complete state-change history and paginated browsing.

## Architecture

Ship the shared core and its extensions as separate modules in a single service release. The core owns lifecycle rules and invokes request-type policies and vendor adapters through defined interfaces. Request types can supply validation, approval, or routing rules through those interfaces. Any future workflow variant requires approval, may only add to the shared lifecycle, and must retain every existing state. Applying a variant to existing requests also requires approval.

| Boundary | Responsibility |
| --- | --- |
| REST | Authenticate, validate input early, route commands and queries, and return authorized representations. |
| Command processing | Check scope, authorization, the caller's observed revision, and lifecycle eligibility. Apply the command through one database commit boundary. |
| Queries | Enforce request and field visibility across detail, browsing, history, and replay results. |
| Shared database | Order effects across application instances and enforce durable receipts, history, identifier uniqueness, and delivery obligations. |
| Integration processing | Run in the same service deployment with its own resource limits. Dispatch approved work outside the REST request path. Adapters handle vendor contracts, credentials, mappings, and correlation. Any inbound workflow action goes through normal command processing. |

The database may be relational or document-based. It must prove atomic durable writes, authoritative outcome lookup, uniqueness across instances, concurrent mutation control, permission-aware queries, and recovery. PostgreSQL is a candidate for retry testing. The engine and specific concurrency mechanism remain open.

## Request lifecycle and API

| From | Action | To | Condition or effect |
| --- | --- | --- | --- |
| Start | Create | `CREATED` | Require a nonblank name and body. Assign an opaque request reference. |
| `CREATED` | Delete | `DELETED` | Require a nonblank reason. |
| `CREATED` | Verify | `VERIFIED` | Require Verify permission. |
| `VERIFIED` | Accept | `ACCEPTED` | Require Accept permission and a person other than the originating requester. |
| `VERIFIED` | Reject | `REJECTED` | Require a nonblank reason. |
| `ACCEPTED` | Reject | `REJECTED` | Require a nonblank reason. |
| `ACCEPTED` | Publish | `PUBLISHED` | Assign a unique numeric publication ID in the successful commit. |

Only the body can be edited, and only in `CREATED` or `VERIFIED`. An edit preserves the state. The name is immutable. `DELETED`, `REJECTED`, and `PUBLISHED` are terminal. Reject unlisted state changes. The publication ID is distinct from the request reference and unique across all initial types. Gaps are allowed. Treat publication IDs as unordered.

Validate mandatory text and confirmed storage constraints at the REST boundary before attempting a mutation. Preserve supported punctuation and multilingual input. Expose unversioned operations for create, detail, body edit, the named lifecycle actions, browsing, history, and access to the current action context. Delete is a lifecycle action with a reason. Exact paths, wire fields, error codes, and request type selection remain open. Distinguish validation and authorization failures, conflicts, and uncertain outcomes without revealing protected request or receipt data.

### History, deleted data, and browsing

- **History.** Record Start → `CREATED`, then one entry per committed state change in per-request order, including when timestamps tie. Failed attempts, body edits, and replays add no state-change entry. The initial REST history endpoint requires its own grant and masks fields. Withhold actor and reason until their field grants are approved.
- **Deleted data.** Keep the terminal marker and the complete ordered lifecycle facts. Restrict deleted payloads by default. Govern retention and redaction separately for bodies, reasons, and replay data, including during recovery. Delete and Reject still require a reason when the action is submitted.
- **Browsing.** Match the name exactly and case-sensitively against validated stored text. Match the current state exactly. Combine supplied filters with AND. Default to 10 results and return an empty page when nothing matches. Sort newest first by immutable creation order with a unique stable reference as the tie-breaker. Use an opaque live cursor bound to caller, scope, filters, sort order, and page size. Reauthorize each page. Changes in state or visibility can change which requests appear on later pages.
- **Filter permissions.** Apply row and retention visibility first. A row whose filter field the caller may not query cannot contribute to matches, counts, page contents, or cursor behavior. Apply output masking separately and recheck permission before disclosure.

## Access and approval

Authenticate stable corporate identities. Combine role and group grants with organization, request type, ownership, assignment, and target scope. Require separate grants for sensitive fields, action context, and service identities. Derive identity and scope from trusted sources. Apply permission checks to direct reads, writes, browsing, history, replay, and integrations, and conceal existence when the target is outside the caller's visibility.

Use scoped Accept and Reject grants on the existing workflow. For a human Create, store the originating person's trusted identity with the request. At Accept, compare that identity with the actor's stable person identity, even after reassignment, ownership changes, or use of another login. Deny Accept when distinct people cannot be established. Verify and Publish each require independent authorization.

Order effective local permission changes with database writes. An earlier revocation blocks the mutation. Recheck current grants immediately before protected disclosure, including after a wait. Revocation after commit can suppress the response or a later replay while the committed effect stands. If the service cannot establish the required permission freshness, it denies protected work. Exact identity mappings, freshness windows, and the permission matrix require separate approval.

## Concurrent writes and retries

Every new write to an existing request requires the revision the caller observed for that action and target. An eligible caller can obtain an authorized view of the relevant decision context. Bind the revision to material context, including independently versioned facts such as assignment or approval evidence. Check those versions, authorization, and lifecycle eligibility together at the point of effect. Reject stale observations and deny or defer when necessary context or version proof is unavailable. After a conflict or replay, obtain current authorized context before forming a new command.

On success, commit the request change, revision, minimal operation receipt, and any applicable history entry, publication ID, approval facts, or outbound obligation together. Create stores the complete request and the originating requester binding where applicable. A rollback leaves no partial business effects or eligible dispatch. Acknowledge success after durable commit. Use authoritative or sufficiently consistent reads for workflow, authorization, and replay. Competing commands based on the same revision cannot both win. For example, Publish and Reject cannot both transition the same accepted request. If an edit beats Accept, Accept must use the newly observed revision. Separate requests can publish concurrently with distinct IDs.

Give every mutation a caller key scoped to the trusted issuer, organization, and stable principal across routes. A successful key binds the action, target, semantic input, and original preconditions. Two distinct keys can create separate requests with identical content. The shared database arbitrates competing uses of one key.

| Situation | Behavior |
| --- | --- |
| Same key, same intent | A contender can read the committed result after reauthorization. After a definitive rollback, an eligible contender can apply the original intent. If the wait exceeds the request budget, report that the outcome is still pending. |
| Same key, changed intent | Reject reuse of a successfully bound key. Reveal the conflict only after authorizing both the original and submitted actions and targets. |
| Response lost or commit uncertain | Resolve the outcome at the authoritative database using the same key. Treat a missing result on a stale replica or during unresolved failover as uncertainty. Apply the original intent again only after the authoritative database establishes a definitive rollback or absence and current checks pass. |
| Committed effect replayed | Return the original effect, including the original publication ID and result revision when permitted, using the current field grants and retention rules. The result revision is historical. |
| Receipt expired or redacted | Reject known expired keys without repeating effects. Treat a known key as expired if its comparison facts were purged. Use verifiable expiry or minimal retirement markers. Remove markers only while expiry remains detectable or the scope closes safely. Reconstruct only retained data. Fence affected retries after a restore if key knowledge is incomplete. |

## Integration delivery

When a live connector is approved, persist its outbound obligation atomically with the triggering local change. Keep a stable effect identity for the destination and operation, and track delivery separately from request state. Recover pending work after restart. A locally successful request confirms the local commit. Confirm vendor completion with evidence of the effect, including for asynchronously accepted jobs. Bound transient retries, spread them with jitter, respect vendor limits, isolate unhealthy destinations, and track unknown outcomes.

Check permitted data, cancellation, and applicable grants before dispatch. Order dispatch authorization with effective local revocations. An earlier revocation stops the send. If authorization wins and revocation races transmission, track the uncertain effect and reconcile it. Each connector must specify whether dispatch also depends on the originating actor's current grant.

Repeat an uncertain remote write only under one of these conditions:

1. The vendor guarantees at most one effect for the stable identity across possible arrivals, whichever attempt reaches it first.
2. Older workers can no longer send, in-flight attempts can no longer apply, and a current, complete lookup at the authoritative source excludes a previous effect.

A worker can resume after losing its lease, and an old write can take effect before a newer fence. Both cases need proof under the same rule. Quarantine unresolved work for authorized reconciliation. An operator can confirm or suppress it, or use separately approved compensation. Any directed retry follows the same proof requirement.

Authenticate inbound messages, verify their scope, target, and mapping, and commit the receipt and effect or processing obligation before acknowledging them. Deduplicate using a trustworthy tenant-scoped event or source-record identity and compare target, revision, and semantic content. On conflicting reuse, keep the first record, quarantine the discrepancy, and prevent a second effect. Reconcile missing, stale, or out-of-order evidence against an approved authoritative source.

### Vendor candidates

The following uses are candidates. Priorities and live commitments are open. Confirm exact product, tenant, version, permitted scope, and operating ownership in a sandbox before enabling any connector.

| Tool | Candidate use and readiness check |
| --- | --- |
| Workday | Read a worker reference and permitted organization data for HR routing. Check worker linkage, field entitlement, and freshness. Use manual routing when evidence is unavailable. [Staffing API guide](https://developer.workday.com/documentation/GUID-6c547385-848b-4005-ab2c-632f1d6b5688) |
| Atlassian Jira | Hand off an eligible bug with a safe summary and correlation reference. Treat status import as a separate use case. Check edition, project scope, and correlation in a sandbox. Cloud webhook retries are finite and use stable tenant-scoped IDs. Cloud search can lag, and stronger reconciliation requires known issue IDs. An empty search after a lost creation response is insufficient for retry. [Cloud webhooks](https://developer.atlassian.com/cloud/jira/platform/webhooks/) · [Search and reconciliation](https://developer.atlassian.com/cloud/jira/platform/search-and-reconcile/) |
| ShiftLeft | Link scan findings and revision evidence to remediation or exceptions. Confirm whether the deployment uses the Qwiet API or the Harness SAST and SCA offering. Verify stable finding identities, token scope, and reconciliation reads. [Harness product information](https://www.harness.io/blog/qwiet-ai-is-now-harness-sast-and-sca) · [Findings API](https://docs.shiftleft.io/sast/api/walkthrough) |
| SonarQube | Link quality-gate evidence to the correct project, branch, and revision. Confirm the edition, credentials, callback authentication, and authoritative lookup. [Server webhooks](https://docs.sonarsource.com/sonarqube-server/discovering/integrations/webhooks) |
| Sonatype | Use Lifecycle reports for dependency policy evidence or Nexus Repository for artifact references, depending on the deployed product. Lifecycle does not retry failed webhooks. Prove completeness against a report or audit source. [Lifecycle webhook limits](https://help.sonatype.com/en/lifecycle-webhooks.html) · [Nexus search](https://help.sonatype.com/en/search-api.html) |

## Acceptance and operations

- **Performance.** Sustain independently paced **100 requests per second across at least two instances**. Every request must complete in **strictly less than 3 seconds**. At that load, **at least 95% of requests for each core operation must complete within 500 ms**. A timeout or missing response fails the per-request limit. Successful processing of valid traffic must sustain the required rate. Report Create, Edit, each lifecycle transition, and Browse separately.
- **Performance test preparation.** Use the agreed data and workload to prepare eligible requests, observed revisions, and keys. Measure end-to-end client timing from scheduled arrival, including queueing. Report offered, completed, and valid throughput, outcomes, threshold shares, and latency distributions. Label replay, contention, and fault profiles separately and inspect durable results. Prepare the tests for a separately authorized run outside routine builds and steps.
- **Access and correctness.** Require **zero unauthorized reads or changes** in approved scenarios across detail, writes, browsing, history, and replay. Test self-Accept through reassignment and alternate logins, revocation during waits or before disclosure, hidden filter fields, and default masking. Exercise every legal transition and invalid action, early input rejection, concurrent writes and publication, response loss, expired replay, and both vendor retry orders. Retain versioned scenarios, observations, and correlated durable outcomes.
- **Changeability and release quality.** Add a test approval policy, replace a fake connector, and rerun lifecycle checks. Report changes to modules, contracts, and deployments. Keep complexity low, verify features, report coverage, and use test-driven development in Phase 5. Gate agreed critical paths on complexity, coverage, module boundaries, and regression evidence.
- **Logging and advisories.** Log every incoming and outgoing request and describe every error in investigable terms. Use protected, bounded buffering during log-sink disruption. Alert and restrict admission before capacity is exhausted. Review direct and transitive dependencies against Spring security advisories and Snyk on schedule and before release. Block a missing review or an unresolved applicable finding that meets the agreed risk criteria. Record breaches and audit any approved exception with its scope, controls, and expiry. Keep missed requirements visible even when an exception is granted. [Spring advisories](https://spring.io/security) · [Snyk Security Database](https://security.snyk.io/)

### Database incident recovery

Restore verified service within **60 minutes of declaring a database recovery incident**. Drill verified restore and staged resumption. Fence obsolete writers and pause unsafe delivery. Check workflow state, complete history, authorization, publication ID uniqueness and non-reuse, replay knowledge, and applicable integration work before resuming. Ensure a pre-restore observation token cannot match different facts on a divergent restored timeline. Keep affected writes or retries fenced while safety is unproven.

Compare acknowledged writes with restored outcomes and report lost acknowledged writes separately. Reconcile external effects around the recovery point before repeating any affected work. Audit the operator, authority, action, and result for pause, resumption, and reconciliation. Include faults such as uncertain commits, unavailable backups, dependency pressure, backlog, log-sink loss, and rollout or rollback with existing records in the planned exercises. Recovery topology, allowed loss, and exact resumption criteria require approval.

## Open decisions and dependencies

| Decision | What remains open | What depends on it |
| --- | --- | --- |
| DEC-03 · Database | Engine selection and proof of durability, atomic writes, uniqueness, and recovery. | Engine-specific persistence, retry tests, and restore design. |
| DEC-04 · Access | Identity source, stable person mappings, role and field matrix, per-action context, service identities, disclosure rules, revocation effective point, freshness windows, outage behavior, and scenarios. | Permission configuration and access evidence. |
| DEC-05 · Retention | Periods, visibility, redaction, purge, legal holds and authority, and handling in backups and restores for body, reason, and replay data. | Data lifecycle, replay, and restore rules. |
| DEC-06 · Inputs | Size, whitespace, encoding, normalization, format, storage limits, and initial type selection or default. | Exact REST validation and fixtures. |
| DEC-07 · Performance | Workload mix and outcome denominators, data volume and skew, instance count above the minimum, topology and resources, dependencies, warm-up, duration, and separate run authorization. | Acceptance runs and capacity evidence. |
| DEC-08 · Writes and retries | Routes, wire fields, errors, semantic key comparison, lifetime and expiry mechanism, concurrency mechanism and conflict budgets, context versions and restore generation. | Client contracts, persistence design, and compatibility tests. |
| DEC-09 · Vendors | Priorities, live scope, products and versions, data authority, mappings, credentials, ownership, sandbox proof, triggers, retry windows, revocation rules, and compensation. | Live connector design and recovery evidence. |
| DEC-10 · Layout and conformance | Exact module layout, delivery resource budgets, sample policy, fake connector, and fixtures. | Deployment sizing and repeatable extension evidence. |
| DEC-11 · History and browse | Actor and reason grants, additional metadata, continuation syntax and lifetime, page cap, and text settings. | History representation, paging contract, and access tests. |
| DEC-12 · Approvals | On-behalf-of identity mapping, approver grants, further duty rules, delegation, quorum, and any separate approval records. | Approval matrix and any later corporate variant. |
| DEC-13 · Identifiers | Wire size and display, external mappings, any corporate namespace, and proof of non-reuse after recovery. | Allocator, client contract, and restore tests. |
| DEC-14 · Operations | Log fields, access, retention, buffer and admission limits, advisory cadence and risk thresholds, remediation and exceptions, gate metrics, recovery topology and point objective, drill and alert criteria, and named operator authority. | Release gates, recovery qualification, and operating procedures. |

Phase 3 requires separate approval of the handoff and explicit acceptance of any deferrals affecting low-level design. The interview submission is source and documentation in a public GitHub or Bitbucket repository, with documented assumptions and a root application README. Exclude the database, compiled classes, dependency binaries, and bundled servers from the submission.
