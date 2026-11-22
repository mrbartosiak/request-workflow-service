# B41–B44 · Operations and release evidence

### B41 · Structured request, response and error evidence · E7

**Needs** B08. **Sources** Mandatory ingress/egress and error logs, redaction and protected access. [Operations](../../../Phase3-LLD/operations.md) · [Q-05](../../../Phase1-conceptual/quality-acceptance.md#q-05-request-and-error-logging)

**Red** Compare a known synthetic request/event set with log counts, including early validation/authentication rejects, unknown outcomes, timeouts and a synthetic outbound recorder invocation. Seed payload, URI, key/token and secret sentinels. **Green** Correlated safe start/outcome/error records with UTC, duration, route/destination alias, phase and catalogued description. Isolate restricted audit lookup and exclude protected raw values. **Stop** Save count reconciliation and sentinel scan. Hand recorder to B42 and B48. B42 integrates the fake-worker invocation.

### B42 · Protected spool, fault admission and drain · E7

**Needs** B25 and B41. **Sources** D6-R buffering and worker pressure. [Operations](../../../Phase3-LLD/operations.md) · [Worker isolation](../../../Phase3-LLD/integration.md)

**Red** Cut sink, exhaust spool headroom, pause delivery, restore sink and verify gap telemetry, ordered evidence and bounded restarts. Force lock/pool saturation and verify safe retries, destination isolation, alert/reserve/stop routing policy and durable outcomes. **Green** Reference encrypted 128 MiB per-instance persistent spool and edge reject capacity, 60% alert and 80% restriction with headroom and fallback, safe metrics and gradual resume. Wire protected outbound recording at real fake send. **Stop** Record ingress/outbound/effect versus log counts, explicit gaps, restart ordering and R-03/R-07 responses. Hand limits to B44/B49/B55. Production thresholds require G7.

### B43 · Security advisory and critical quality controls · E7

**Needs** B01, B02 and B45. **Sources** D6-R advisory and quality direction, owner sources. [Operations](../../../Phase3-LLD/operations.md) · [Q-06/Q-07](../../../Phase1-conceptual/quality-acceptance.md#q-06-security-advisories)

**Red** Supply missing/outdated inventory, exposed unresolved high, justified unexposed critical and seeded complex/under-covered critical method. Assert expected dispositions using reference proposals. **Green** Reproducible resolved direct/transitive inventory, weekly and prerelease Spring Security Advisories and Snyk records with dated no-findings entries, exposure triage, quality report and owner escalation. Compare reference complexity ≤10 and critical branch coverage ≥90% with documented paths. Use approved local tools or recorded manual review where tooling is pending. **Stop** Attach versions, coverage and case outputs. Compulsory gates, exact thresholds, exception authority and production tools need G6/G7. Hand evidence to B48/B55/B56.

### B44 · Rollout, compatibility and timed restore rehearsal · E7

**Needs** B28, B37, B38, B39, B40 and B42. **Sources** Existing-record compatibility and backup/log first. [Operations](../../../Phase3-LLD/operations.md) · [Recovery](../../../Phase3-LLD/recovery.md)

**Red** Start with an existing schema, prior receipt/obligation/envelope versions, restored grants and old published IDs/mappings. Exercise rollout and eligible rollback, outage pressure, backup/log recovery, stale writer and unproved remote work. **Green** Run migration and verified backup/log rehearsal with declaration through verified service timing, fencer checks and per-stage verification. Compare warm failover on RTO, RPO, split-brain risk and cost for later selection. **Stop** Report ≤60 min result or failure, time to fence/restore, incomplete proofs, and positively acknowledged losses separately. No fixture timing is production qualification. Hand result to B55.
