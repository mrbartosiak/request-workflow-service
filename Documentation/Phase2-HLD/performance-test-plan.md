# Performance test plan

**Status** DEC-07 authorizes preparation. Execution is opt-in and requires a separate explicit request. Exclude these tests from routine builds and steps. Measurements remain pending a future run. [DEC-07](../Phase1-conceptual/decisions.md#dec-07-performance-evidence) · [Q-01](../Phase1-conceptual/quality-acceptance.md#q-01-capacity-and-response-latency)

## Pass criteria

Sustain independently paced **100 requests per second** across **at least two running instances**. Process the expected valid traffic at the offered rate. Every measured request must complete in **strictly less than 3 seconds**. A timeout, missing response or request at 3 seconds or above fails that criterion. At the agreed load, **at least 95% of requests for each core operation complete within 500 ms**. Report Create, Edit, Verify, Accept, Reject, Delete, Publish and Browse separately. [Acceptance evidence](acceptance-evidence.md)

## Prerequisites for a future run

Use a runnable release with wired authentication, shared database, request/error logging and at least two instances. Seed a production-representative approved synthetic data set with all states, enough visible records for browsing and authorized/denied actors. Prepare request-flow data with valid observed revisions and keys. Drive independent client arrivals, collect all outcomes and confirm the generator can offer the target load without hiding queued requests. Record the instance split, dependencies, measurement environment and versions. DEC-07 still needs agreement on workload distribution, data volume and skew, run length, warm-up, topology/resources and how failures and retries affect aggregate reporting. [Profile settings](operational-follow-ups.md)

## Procedure after explicit execution approval

1. Record the approved profile and collect baseline readiness and load-generator checks. Warm up as agreed, then sustain 100 offered requests per second independently of responses across the instances. Measure elapsed client time from scheduled arrival through the complete response, including client and service queueing. Capture late, timed-out and missing responses.
2. Use the agreed mix of ordinary lifecycle and browse operations. Exercise identical retries and hot-request contention in separately labeled profiles, including competing valid actions and stale reads. If approved, run dependency impairment and isolated delivery/backlog profiles and label their results separately. Record error categories and distinguish normal replays or expected conflicts from invalid processing and saturation.
3. Compare offered and completed rates, valid throughput and timings with Q-01. Check the authoritative durable outcomes for race/retry profiles. Retain the raw run and per-operation report. Report failures against each applicable criterion, including any missing results.

## Evidence template

**Run** Date, authorization, release/data versions, measured interval, warm-up, generator placement and configuration, request mix, instance count/resources, database topology, dependency/logging conditions and outcome denominators.

| Operation or total | Offered/completed | Valid completed | Outcomes by class | At or under 500 ms | Maximum and p50/p95/p99 | Timeouts/missing | Criteria result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| One row per operation, then total | Pending run | Pending run | Pending run | Count and share | Client observed | Include every missing response | Pass/fail by required criterion |

Attach sanitized workload inputs, environmental evidence, raw timing/outcome data, durable invariants checked, any timing/coverage gaps and the review result. [Q-01 trace](traceability.md#quality-criteria)
