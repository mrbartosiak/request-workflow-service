# Phase 5 backlog · operation and quality evidence

## OPS-01 Protected logging and admission

**Needs** `PG-04`. **Scope** Ingress/egress/error records, protected spool and pressure summaries. **Red** Exercise pre-auth, early rejection, success, conflict, timeout and synthetic sensitive fields. Compare emitted records to request/error counts. Break sink and fill to thresholds. **Green** Implement scoped safe descriptions, UTC/correlation, encrypted bounded spool, edge reject reserve, alert/restrict/drain path and observability. Use 128 MiB and 60/80% fixture references. **Stop/handoff** Report no secrets/payloads, admission behavior and gaps when reject logging cannot continue. [Log fields and D6 references](../../../Phase3-LLD/operations.md) · [Q-05](../../../Phase1-conceptual/quality-acceptance.md#q-05-request-and-error-logging)

## OPS-02 Fault, overload, and upgrade smoke

**Needs** `OPS-01`, `APP-07`, `INT-03`, `INT-04`. **Scope** E7 bounded HTTP/worker pressure, log sink failure and compatible migration rehearsal. **Red** Simulate queue/pool/lock/authority pressure, failing destination/log sink and schema upgrade with previously valid request, receipt, history, mapping and pending work. Oracle detects missing logs, wrong read/rollback and retry amplification. **Green** Verify bounded admission/retries and staged return, add outgoing log coverage, exercise forward and supported rollback paths on representative retained data. **Stop/handoff** Attach inbound/outbound log counts, pressure baselines, before/after durable values and affected migration cases. [Pressure and recovery](../../../Phase3-LLD/operations.md) · [E7](../../../Phase3-LLD/verification.md)

## OPS-03 Advisories and critical coverage baseline

**Needs** `APP-07`, `INT-03`, `REC-03` and any approved local measurement tools. **Scope** Documented operating gates and candidate critical paths. **Red** Exercise gate evaluator on missing review, applicable unresolved high exposed finding, unexposed critical tracked, and fixture complexity/coverage breaches. **Green** Record resolved direct/transitive versions and dated Spring Security/Snyk reviews weekly and before release, including zero findings. Evaluate method complexity ≤10, critical branch coverage ≥90%, and scenario coverage. **Stop/handoff** Supply findings, evidence and candidate owners. Dependency adoption, thresholds, exception owners and production gates depend on DFR-5/6. [D6 gates and examples](../../../Phase3-LLD/operations.md) · [E7](../../../Phase3-LLD/verification.md)

## OPS-04 T3 API-diff pilot

**Needs** `APP-07` for paired behavior evidence, and owner-approved pilot pin/provision if required. Seeded spec-diff setup can start after `HTTP-01`. **Scope** Candidate oasdiff pilot against approved released YAML or labeled fixture baseline. **Red** Route removal and decimal string-to-number changes fail comparison, and an approved safe addition passes. **Green** Exercise a reproducible pilot invocation at pull request and release, paired with behavior tests for role and mask. **Stop/handoff** Supply each checkpoint's result, baseline identity, limitations and manual diff fallback. Compulsory CI requires DFR-5 and a released baseline. [T3-R](../../../Phase3-LLD/tooling.md)

## OPS-05 T6 package-boundary pilot

**Needs** `FND-01` and owner-approved pilot pin/provision if required. **Scope** Test-only candidate ArchUnit proof against the module graph. **Red** Detect Java 25 domain-to-Spring, forbidden cross-adapter edge and cycle while valid bootstrap paths pass. **Green** Import classes and run compatible package checks at pull request and release, or evaluate the JDK/build fallback. **Stop/handoff** Supply graph, impact/advisories and each checkpoint's result. Compulsory CI requires DFR-5. [T6-R](../../../Phase3-LLD/tooling.md) · [Wiring module table](../../../Phase3-LLD/wiring.md)
