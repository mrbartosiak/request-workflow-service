# Quality acceptance criteria

**Phase 1 status.** `REQUIRED` denotes an outcome from the interview brief, owner assumptions, or an approved owner decision. `PROPOSED` denotes an acceptance method, operating policy, or additional gate awaiting human approval. Use [features](features.md) and [workflows](workflows.md) for the behavior under test and [decisions](decisions.md) for approval status.

## Q-01 Capacity and response latency

**REQUIRED.** The service handles **100 requests per second** with multiple application instances running. Every request completes in **strictly less than 3 seconds**. At that load, **at least 95% of requests for each core operation complete within 500 ms** under the agreed workload. Evaluate Create, Edit, state transitions, and browsing separately. Any measured request taking 3 seconds or longer, timing out, or missing a response fails the per-request time limit. Failure to process valid in-scope traffic at the required rate fails the capacity check.

**Approved measurement under DEC-07.** Independently pace 100 requests per second across at least two instances, measure end to end including queueing, and report per-operation results. Prepare the procedure and evidence template for a future opt-in run. Execution needs separate explicit authorization and is outside routine builds and steps. Exact profile and denominator details remain open. [DEC-07](decisions.md#dec-07-performance-evidence) · [Performance test plan](../Phase2-HLD/performance-test-plan.md)

## Q-02 Database-level idempotency across instances

**REQUIRED.** Within the approved replay scope, enforce idempotency at the database level across application instances. A recognized replay of a successful Create preserves the same durable request and creates no second request. Two independently intended creates with identical business fields each create a distinct request. A recognized replay of a successful Publish preserves the original numeric publication identifier. Apply the approved access policy before disclosing any prior outcome. [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-08](decisions.md#dec-08-retry-idempotency-and-error-contracts)

**PROPOSED evidence.** Send equivalent attempts serially and concurrently through instances sharing the database. Lose the response after commit and retry after restarting an instance. Compare persisted state and verify how the database enforces a single effect. Confirm the original durable Create record is the only effect of its operation, independently of what the REST interface exposes. For Publish, compare the initially assigned numeric identifier with the identifier disclosed on an eligible replay. Also test replay after the caller loses access to the prior outcome and conflicting input under the same operation identity. Compare response and durable effects with the approved replay, access and error contracts.

## Q-03 Concurrency and persistence invariants

**REQUIRED.** Concurrent outcomes must match a permitted ordering under the workflow, and publish operations on distinct requests must produce distinct numeric identifiers. Successful mutations have durable outcomes. Failed mutations leave no partial state change. Delete and Reject require a reason as input. The applicable data policy governs which fields persist and who can access them. [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-05](decisions.md#dec-05-deleted-data-and-retention) · [DEC-11](decisions.md#dec-11-history-and-browsing-behavior) · [DEC-13](decisions.md#dec-13-publication-identifier-contract)

**PROPOSED evidence.** Send pairs of commands from different instances, including Publish and Reject in `ACCEPTED`, Edit and Accept in `VERIFIED`, and Publish on separate eligible requests. Compare accepted actions and persisted outcomes with a permitted ordering. After successful mutations, restart the service and compare the durable outcomes, including numeric publication identifiers, with pre-restart evidence. Cause a representative mutation to fail before commit and verify the absence of partial changes. For reason-bearing actions, verify that the approved retention and access policy is applied.

## Q-04 Early request validation

**REQUIRED.** At the REST entry point, reject malformed input, absent mandatory fields and values that violate the approved field contract or confirmed storage constraints before attempting to persist them. Require a name and body for Create and a reason for Delete and Reject. Derive character restrictions from explicit business rules and actual storage constraints. [DEC-06](decisions.md#dec-06-name-editing-and-input-rules) · [DEC-08](decisions.md#dec-08-retry-idempotency-and-error-contracts)

**PROPOSED evidence.** Exercise Create, Edit, Delete and Reject. For invalid input, verify the approved validation response and zero mutation attempts. Verify that approved punctuation and multilingual text survive a round trip for fields the policies retain and allow the test caller to read. Verify reason validation independently of retention or visibility. Check reason round trips only where retention and access are approved. If PostgreSQL text is selected, test early rejection of embedded `U+0000` in fields stored as character data. [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-05](decisions.md#dec-05-deleted-data-and-retention) · [DEC-11](decisions.md#dec-11-history-and-browsing-behavior) · [PostgreSQL character types](https://www.postgresql.org/docs/current/datatype-character.html)

## Q-05 Request and error logging

**REQUIRED.** Log every incoming and outgoing request and log every error in descriptive, understandable terms. Missing records or error descriptions that leave the failure unclear fail this check.

**Evidence under DEC-14.** Verify protected bounded buffering, alerts and admission restriction before exhaustion. Compare logs with observed successful requests, early rejects, conflicts, errors, timeouts and outbound attempts. Test log-sink loss, recovery and synthetic sensitive values. Exact fields, thresholds, access and retention remain open. [Operating policies](../Phase2-HLD/operating-policy-alternatives.md) · [Q-11](#q-11-complete-state-change-history)

## Q-06 Security advisories

**REQUIRED.** Follow Spring Security Advisories and the Snyk Security Database. [Spring Security Advisories](https://spring.io/security) · [Snyk Security Database](https://security.snyk.io/)

**Approved direction under DEC-14.** Review direct and transitive dependencies against both sources on schedule and before release. Keep dated records, including zero findings. Block missing review and risk-qualifying applicable findings. Exact cadence, risk criteria, remediation and exception authority remain open. [Operating policies](../Phase2-HLD/operating-policy-alternatives.md)

## Q-07 Cyclomatic complexity and testing

**REQUIRED.** Keep cyclomatic complexity reliably low and use test-driven development in Phase 5. Verify the required feature behavior and report test coverage.

**Approved direction under DEC-14.** Gate agreed critical paths using complexity, coverage, module-boundary and regression evidence. The proposed cases include lifecycle, validation, access, replay, history and browsing, with database-backed concurrency and durability checks. Exact paths, thresholds and exception authority remain open. [Acceptance evidence](../Phase2-HLD/acceptance-evidence.md)

## Q-08 Changeability and integration ease

**REQUIRED.** Support new use cases and tools. Initial types use the exact interview lifecycle. Future variants may only add to it and must retain existing states. DEC-10 approves composition and DEC-12 approves initial scoped approvals. Specific extensions and further approval policy remain open. [DEC-02](decisions.md#dec-02-interview-lifecycle-and-corporate-variants) · [DEC-12](decisions.md#dec-12-approval-policy)

**Approved evidence under DEC-10.** Add a test approval policy and replace a fake connector. Rerun lifecycle checks and report changed modules, contracts and deployments. Exact fixtures remain open. [Architecture](../Phase2-HLD/architecture.md)

## Q-09 Reliable integration delivery

**PROPOSED verification for any approved live connector under DEC-09.** Exercise expected delivery, throttling or outage, duplicate and out-of-order input, an ambiguous remote outcome, and local restart around delivery. Check the local workflow invariant, the remote effect count and the recorded delivery status. Confirm that an unsupported or ambiguous remote outcome remains visible until resolved, that retried operations avoid duplicate remote effects where the vendor permits safe retry, and that an unavailable destination is distinguishable from a confirmed delivery. Keep failure and replay evidence, including reconciliation when retries are unsafe or notifications can go missing. Production behavior and SLOs require approval. See [common integration lifecycle](integrations.md#common-integration-lifecycle).

## Q-10 Operational recovery

**REQUIRED.** Restore verified service within **60 minutes of declaring a database recovery incident**. Measure from incident declaration to service resumption. Before resuming, verify workflow state, complete state-change history, and outstanding integration work where applicable. Report any lost acknowledged writes separately. [DEC-14](decisions.md#dec-14-quality-and-operational-policies)

**Approved direction under DEC-14.** Drill verified restore and staged resumption. Fence stale writers, check durable invariants, record declaration and verified resumption times, report any lost acknowledged writes and audit operator actions. Except for required thresholds, exact drill scenarios, alert settings, topology, recovery point and authority remain open. [Recovery](../Phase2-HLD/operations-and-recovery.md)

## Q-11 Complete state-change history

**REQUIRED.** Store complete history, starting at Start → `CREATED` and followed by ordered committed state changes. Failed attempts, equivalent replays and state-preserving edits add no state change. The initial endpoint needs a distinct grant and withholds actor/reason until exact grants are approved. [F-06](features.md#owner-approved-interview-features) · [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-05](decisions.md#dec-05-deleted-data-and-retention) · [DEC-11](decisions.md#dec-11-history-and-browsing-behavior)

**PROPOSED evidence.** Exercise Create → Verify → Accept → Publish, Create → Delete, Create → Verify → Reject and Create → Verify → Accept → Reject. Compare creation and committed changes with history. Check invalid actions, replays and body edits add no history. Verify after restart and check the distinct endpoint grant, actor/reason masking and deleted-data policy.

## Q-12 Paginated browsing and filters

**REQUIRED.** Provide paginated request browsing with a default page size of **10**, filtering by name, and filtering by state. Apply the approved ordering, matching, filter composition, access and retention rules. [F-07](features.md#owner-approved-interview-features) · [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-05](decisions.md#dec-05-deleted-data-and-retention) · [DEC-11](decisions.md#dec-11-history-and-browsing-behavior)

**PROPOSED evidence.** Use more than 10 visible requests and hidden matches. Verify the default size, stable fixed-set traversal, independent and AND-combined exact filters, no match and deleted visibility. Verify hidden filter fields cannot influence matching, counts or pagination. [Browse](../Phase2-HLD/browse-contract.md)

## Q-13 Access isolation

**REQUIRED.** The acceptance target is **zero unauthorized reads or changes** across the approved permission scenarios. Apply it to direct reads and changes, browsing, action context, the history endpoint and replay disclosure. [DEC-04](decisions.md#dec-04-identity-roles-and-access) · [DEC-12](decisions.md#dec-12-approval-policy)

**PROPOSED evidence.** Exercise allowed and denied operations with actors who have different permissions. Compare returned data and persisted state with the expected result for each scenario. Include a matching request outside the caller's browsing permissions and an actor who loses permission before retrying an earlier operation.
