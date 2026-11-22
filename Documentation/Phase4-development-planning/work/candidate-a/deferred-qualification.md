# Seven deferred production and verification dispositions

The **owner** makes each disposition below with the named role supplying the technical assessment. Use existing documents and permitted synthetic evidence to propose a named target and bounded qualification. The owner approves access, changes and applicable safety limits before target-specific work. The resulting qualification evidence supports a later release or activation decision. [Handoff readiness](../../../Phase3-LLD/handoff-readiness.md)

| Gate | Decision and evidence criterion | Dependent work |
| --- | --- | --- |
| DFR-1 | Data and reliability leads compare PostgreSQL and qualified alternatives using local results and capabilities. Approve candidate engine, exact schema/storage, proof-store products and failure-domain topology for qualification, then require equivalent cut evidence for promotion. | `PROD-01`, `PROD-02`, `PROD-06` |
| DFR-2 | Security/identity lead proposes provider, stable human/alias and service mappings, org/type/action/field grants and purpose-specific key custody/rotation. Show effective revocation, stale proof and unresolved identity behavior. Default service/on-behalf-of Create remains denied. | `PROD-02`, `PROD-03`, `PROD-05`, `PROD-06` |
| DFR-3 | Privacy and operations leads propose retention windows, lawful hold/purge grants, archive and backup disposal, audit and payload visibility. Require post-rewind purge and protected restoration proof. | `PROD-04`, `PROD-06` |
| DFR-4 | Integration lead proposes vendor, trigger, tenant and least privileges from assessments and fake tests. Obtain owner consent before any real tenant contact. Require product-specific dedupe/lookup/fence, recovery and redaction evidence before enabling a live trigger. | `PROD-05`, `PROD-06` when live scope is selected |
| DFR-5 | Build/security leads propose exact production pins, compatible tools and mandatory gates after reproducible build, advisories, baseline and scored T3/T6 pilot results. | `PROD-01`, `PROD-02`, `PROD-03`, `PROD-07`, `PROD-06` |
| DFR-6 | Reliability/security leads propose budgets, spool and critical thresholds, evidence and operator authorities, topology, RPO and permitted loss based on local drills. Require promotion evidence on the proposed topology and named authorization for target recovery, stage promotion and exceptions. | `PROD-02`, `PROD-04` through `PROD-07` as scoped |
| DFR-7 | Verification lead records actual P/E/restore/connector evidence for the proposed release target. Owner separately approves the formal workload (**G4**) and each scoped execution (**G5**). Require signed evidence of valid throughput, per-operation latency and recovery before an acceptance claim. | `PERF-02` sends profile for G4, `PERF-03` needs G4 and G5, `PROD-06` needs applicable acceptance evidence |

Plan authorities and rollout gates separately from the selected target. [Phase 4 gate ledger](decision-gates.md) · [Qualification cards](backlog-production.md)
