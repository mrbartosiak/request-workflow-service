# Acceptance and quick performance evidence

**Scope** Q-01–04/07–13, F-01–11, DEC-07/10/14. **Status** Required thresholds and methods below, proposed fixtures. No backend run is reported here. [HLD acceptance](../../../Phase2-HLD/acceptance-evidence.md)

| Evidence ID | Required observable outcome |
| --- | --- |
| E1 Lifecycle | Exhaustive state/action table for every type, body edits and validations, reason-bearing actions, terminal states, ordered creation/history including ties and restart. Failed attempts, edits and replays append no state change. |
| E2 Validation | Missing, empty/blank, wrong media/type, duplicate props, malformed UTF-8, invalid path/query, unknown fields, prohibited characters, and size cases make zero store mutation calls. Punctuation and multilingual values round-trip under grant/retention policy. |
| E3 Browse and history | Over 10 visible plus hidden rows, exact UTF-8 name/state and intersection, empty result, ties and keyset traversal. Vary hidden row/fields and compare pages/cursor existence. Revoke mid-page sequence; actor/reason stay masked and history requires distinct grant. |
| E4 Access | Zero unauthorized reads/changes on each approved surface and scope. Verify alias/self-Accept and reassignment, separate Verify/Publish, grants changed before effect/release, stale mirror, revoked context and denied keyed disclosures. |
| E5 Transactions and recovery | Run the real PostgreSQL matrix and older-backup drill, audit fencing, expired keys, factual replay, increasing assignments and separate known acknowledged loss. |
| E6 Extension and remote | Fake replacement and test policy impact report, local/delivery fault cases, remote counters and staged resumption. Repeat relevant proofs for any live connector. |
| E7 Operations | Sink failure, sensitive-data scan, advisory gate, complexity/coverage/regression, compatible rollout/rollback, approved response and verified restore drills. |

[E1](domain.md) · [E2](rest.md) · [E3](browse-history.md) · [E4](access.md) · [E5](postgresql-evidence.md) · [E6](vendors.md) · [E7](operations.md)

## Quick opt-in smoke

Owner authorized a simple quick check. Proposed profile uses two Java 25/Spring 7.0.11 workers and a real PostgreSQL writer. Record hardware, versions, topology and authority setup. Seed fixture types, two eligible principals, per-operation target queues, 1000+ eligible browse rows and hidden matches. Prepare matching observations and keys outside timed load with enough lifetime. Use independent targets and deterministic rank/filter mixes. Disable fake vendor network traffic. Cycle eight operations and alternate the instance assignment for each operation on successive cycles. Pace 100/s for 10 s warm-up and 30 s measurement, offering 3000 measured requests, 375 per core operation (Create, Edit, Verify, Accept, Reject, Delete, Publish, Browse). Time from scheduled send through full response or timeout, including queueing. Retry traffic is outside this smoke. Compare expected status, offered/served counts and durable samples. [Owner scope](../../owner-kickoff.md) · [HLD performance method](../../../Phase2-HLD/performance-test-plan.md)

Record each operation's offered, valid, error, timeout, under-3 s and at-most-500 ms counts, p50/p95/p99/max, instance split and missed dispatches. Count misses/timeouts as failures and use all valid offered requests as the latency denominator. Report queue/pool/lock pressure and any observed breach. Smoke only exercises preprepared independent-target traffic for a short window. Broader environment, authority, concurrency and performance acceptance workload needs approval and an explicit run.

**Required acceptance** 100 rps across at least two instances, every request strictly below 3 s including failure responses, at least 95% within 500 ms per core operation, and expected valid traffic processed. Zero unauthorized reads or changes in approved scenarios and verified service restore within 60 minutes from declaration remain separate acceptance conditions. **Status** Planned. Smoke execution and measurements require an executable app, test datastore and qualifying runtime. [Q-01](../../../Phase1-conceptual/quality-acceptance.md#q-01-capacity-and-response-latency) · [Q-10](../../../Phase1-conceptual/quality-acceptance.md#q-10-operational-recovery)
