# Operations, security and recovery

**Scope** Logging, advisory review and recovery. **Status** Q-05/06/10 set required outcomes. DEC-14 approves buffered logging, advisory gates and verified restore/staged resumption drills. Thresholds, topology and authority details remain open. [Quality acceptance](../Phase1-conceptual/quality-acceptance.md) · [Production risks](../Phase1-conceptual/production-risks.md)

## Observability and security

**Required.** Log every incoming and outgoing request and describe every error in understandable, investigable terms. Follow Spring Security Advisories and the Snyk Security Database. [Spring advisories](https://spring.io/security) · [Snyk database](https://security.snyk.io/)

**Proposed.** Use timestamp, direction, route/destination alias, correlation, operation, outcome, duration and safe error class. Exclude credentials, raw keys, payloads, HR details and unredacted query values. Record early rejects, conflicts, errors, timeouts and each outbound attempt. Verify coverage against traffic using sensitive-data and sink-failure tests. [Workflow history](workflow-and-history.md) · [Operating policy alternatives](operating-policy-alternatives.md)

Use protected bounded buffering during log-sink disruption. Alert and restrict admission before exhaustion. Review direct and transitive dependencies on schedule and before release. Block missing review and risk-qualifying applicable findings. Record reviews, ownership, fixes and approved exceptions. Scope vendor credentials and verify peers and mappings. Exact security safeguards and rotation checks follow connector approval. [Operating policies](operating-policy-alternatives.md)

## Database incident recovery

**Required.** Restore verified service within 60 minutes of declaring a database recovery incident. Measure from incident declaration to service resumption. Before resuming verify workflow state, complete state-change history and outstanding integration work where applicable. Report lost acknowledged writes separately. Recovery point objectives and allowed data loss require their own decisions.

**Approved drill direction.** Exercise verified restore and staged resumption. Fence stale writers, verify invariants, report any lost acknowledged writes and audit operators. Use the sequence below to plan the drills. Exact checkpoints and named authority remain open.

1. Record declaration, impact and authority. Pause unsafe writes and delivery and fence obsolete writers during failover. Establish the last trusted recovery point and protect evidence of acknowledgments and vendor effects.
2. Restore a trusted data set and verify existing request readability, workflow and history integrity, authorization mapping, publication ID uniqueness and allocator safety, operation identities/retention, and delivery/inbound processing checkpoints.
3. Compare acknowledged operations with restored effects. Fence affected retries and publications until known-safe key and ID handling is proved. Before writes resume, verify pre-restore observation tokens cannot match different facts on a divergent restored history. Propose invalidating the affected observation generation if versions can rewind. Keep writes fenced where safety is unproven. Preserve eligible replay under its separate receipt policy. Report lost acknowledged writes separately.
4. Reconcile work around the recovery point with authoritative external records. An external effect absent from a restore remains possible. Keep unsafe redelivery paused pending approved resolution and record why. Confirm service resumption authority and any separately approved criteria for restoring integration dispatch.
5. Record timing, user impact, invariant checks, acknowledged losses and unresolved work. Audit the operator, action, authority and result for pause, resume and reconciliation. Exercise proposed restart, uncertain commit, missing backup, delivery backlog and sink loss under agreed profiles.

Topology remains open. Backup restore alone reduces running cost. Warm failover can shorten outages and adds cost and divergence risk. Either choice must demonstrate verified resumption within 60 minutes. [Q-10](../Phase1-conceptual/quality-acceptance.md#q-10-operational-recovery)

## Production fault evidence

Observe latency, saturation, queue and lock waits, conflicts, delivery age, unknown effects, stale evidence and log gaps. Exercise bounded retries, destination isolation, gradual resumption and rollout/rollback with prior valid records. Preserve history, IDs, operation keys and pending work across compatible instances. Record fault, signal, user impact, recovery action and durable before/after evidence. Approve thresholds and stage criteria under DEC-14. [Phase 1 risks](../Phase1-conceptual/production-risks.md) · [Acceptance evidence](acceptance-evidence.md)
