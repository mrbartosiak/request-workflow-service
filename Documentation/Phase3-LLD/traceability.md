# Requirements to design and evidence

**Scope** All A-01–10, F-01–11, Q-01–13, R-01–10 and DEC-01–14. P cases are [database verification](postgres-evidence.md). E cases are [functional and operating verification](verification.md). [Phase 1 IDs](../Phase1-conceptual/README.md) · [Signed HLD trace](../Phase2-HLD/traceability.md)

| Assumption or feature | Design | Evidence / expected result |
| --- | --- | --- |
| A-01 A-02 A-03 F-10 | [Core and wiring](wiring.md), [domain](domain.md) | E1/E5. Java 25/Spring 7.0.11 build and extension preserves lifecycle. |
| A-04 F-09 | [Persistence](persistence.md), [replay](retries.md) | P1–P9. Two real instances, single durable effects and bounded waiting. |
| A-05 | [Identity and grants](access.md) | E4/P8. Scoped roles/groups and stable requester comparison. |
| A-06 F-11 | [Vendor assessment](vendors.md), [integration](integration.md) | E5/E6. Five assessed vendors and isolated fake proof. |
| A-07 | [Operations](operations.md), [recovery](recovery.md) | E7 and risk table below. |
| A-08 | [Adopted decisions](decisions.md) | Apply recorded selections and verify the exact package. Phase 4 handoff is separate. |
| A-09 A-10 F-01 F-02 F-03 F-04 F-05 F-06 | [Lifecycle](domain.md), [identifiers](identifiers.md), [history](queries.md) | E1/E2/E4/P6/P7/P9. Exact initial lifecycle and protected history. |
| F-07 F-08 | [REST](rest.md), [browse](queries.md) | E2/E3/E4. API, paged filters and masking. |

| Quality | Design contract | Evidence |
| --- | --- | --- |
| Q-01 | [Performance](performance.md) | Authorized quick smoke remains unrun. Sustained acceptance targets and method bind. The formal workload and run require separate approval. |
| Q-02 Q-03 | [Persistence](persistence.md), [replay](retries.md), [allocation](identifiers.md), [association](publication-journal.md) | P1–P9 with HTTP, local effects and original-lineage proof. |
| Q-04 | [REST](rest.md) | E2, zero DB mutation attempts for invalid input. |
| Q-05 Q-06 Q-07 | [Operating gates](operations.md), [tooling](tooling.md) | E7 verifies log completeness, dated advisory records, complexity, coverage and regression cases. |
| Q-08 Q-09 | [Wiring](wiring.md), [delivery](integration.md) | E5/E6 swaps the fake, adds a policy and tests ambiguous outcomes. |
| Q-10 | [Restore](recovery.md), [proof cuts](proof-evidence.md) | P9/E7 verifies service by 60 min and counts lost client-acknowledged effects separately. |
| Q-11 Q-12 Q-13 | [History/browse](queries.md), [access](access.md), [payload retention](retention.md) | E1/E3/E4/E7/P8/P9 verifies masks, hidden-row pagination, hold/purge order and authorization. |

| Risk | Design and evidence |
| --- | --- |
| R-01 uncertain local outcome | [Replay](retries.md), P3–P5. |
| R-02 concurrent state and allocation | [Persistence](persistence.md), [IDs](identifiers.md), P6–P7. |
| R-03 load and recovery | [Budgets and logging](operations.md), E7 and opt-in [performance](performance.md). |
| R-04 vendor throttling, ambiguity and duplicates | [Delivery](integration.md), E6. |
| R-05 drift, credentials and scope | [Delivery and contracts](integration.md), E5/E6/E7. |
| R-06 invalid inputs and reasons | [REST](rest.md), E2. |
| R-07 logging gaps, overload and sensitivity | [Logs](operations.md), E7. |
| R-08 stale or wrong access | [Access](access.md), E3/E4/P8. |
| R-09 rollout and migrations | [Persistence](persistence.md) and [restore](recovery.md), P9/E7. |
| R-10 older restore and external effects | [Recovery](recovery.md), [authority](authority-continuity.md), [inbound proof](inbound-recovery.md), P9/E6/E7 and [restore matrix](restore-evidence.md). |

The [decision record](decisions.md) identifies implementation and verification consequences for each DEC-01–14. [Source verification](sources.md)
