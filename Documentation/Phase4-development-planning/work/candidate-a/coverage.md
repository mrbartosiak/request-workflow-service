# Requirements to work and evidence

All test cases below are **planned**. Each promoted evidence record names the implementing ticket, test ID, run target and exact revision. Match requirement IDs to the owner baseline before signing. [Conceptual IDs](../../../Phase1-conceptual/README.md) · [LLD trace](../../../Phase3-LLD/traceability.md)

| Requirement | Owning tickets and acceptance evidence |
| --- | --- |
| A-01/02/03, A-09, F-10 | `FND-00/01`, `DOM-01/02`, `INT-06`, `DEL-01`. E1/E5, same lifecycle and extensibility on required runtime. |
| A-04, F-09 | `PG-01`–`PG-05`, `APP-07`, `PRF-02/05`, `REC-02`. P1–P9, shared durable results. |
| A-05, A-10 | `DOM-02`, `APP-01/02/05/07`, `PROD-03`. E1/E3/E4, complete history, paginated browsing and scoped actors. |
| A-06, F-11 | `INT-01`–`INT-06`, `PROD-05`. E5/E6, fake proof and each vendor disposition. |
| A-07 | `OPS-01`–`OPS-03`, `REC-04`, failure response and verified rehearsals. E7. |
| A-08 | G1–G8, DFR decisions and exact revision review. [Gate ledger](decision-gates.md) |
| F-01/02/03/04/05 | `DOM-01`, `PG-04`, `APP-02/03`, `PRF-02`. E1/E2/E4 and P6/P7. |
| F-06/07/08 | `HTTP-01`, `APP-01/04/05`, `DEL-01`. E1–E4. |
| Q-01, R-03 | `PERF-01`–`PERF-03`, `OPS-02`. Quick scope and separately approved formal evidence. |
| Q-02/03, R-01/02 | `PG-05`, `APP-07`, `PRF-02/04/05`, `REC-02`. P1–P9, proof cuts and original associations. |
| Q-04, R-06 | `HTTP-01`, `APP-03`. E2 zero invalid persistence, accepted text intact. |
| Q-05/06/07, R-05/07/09 | `FND-00/01`, `PG-01`, `OPS-01`–`OPS-05`, `DEL-01/02`, `PROD-07`. E7, safety and compatibility, log/advisory/complexity/coverage proof. |
| Q-08/09, R-04/05 | `INT-01`–`INT-06`, `PROD-05`. E5/E6, effect counts, reconciliation and extension touches. |
| Q-10, R-10 | `REC-01`–`REC-04`, `PROD-02`. P9, E7, timed drill, acknowledged loss and proof/restore cuts. |
| Q-11/12/13, R-08 | `DOM-02`, `APP-01`–`APP-07`, `PRF-03/04`, `REC-03`, `PROD-03/04`. E1/E3/E4/E7 and P8/P9. |

| Case | Owning work and expected proof |
| --- | --- |
| E1, E2 | `DOM-01`, `HTTP-01`, `PG-04`, `APP-01/02/03`, `PRF-02`. Exact lifecycle, validation and wire. |
| E3, E4 | `APP-04/05/06/07`, `PRF-03/04`, `REC-03`. Guarded lookahead, masked results, grants and revoked current release. |
| E5, E6 | `INT-01`–`INT-06`. Connection rollback, extension delta, fake ACK and remote effects. |
| E7 | `OPS-01`–`OPS-05`, `REC-01`–`REC-04`. Logs, release gates, recoverability. |
| P1, P2 | `PG-04/05`, `APP-07`. Key barrier, cross-route and fenced original. |
| P3, P4, P5 | `PG-05`, `PRF-02/04`, `INT-01`. Local rollback, lost commit/response and exact continuation. |
| P6, P8 | `APP-02/03/07`. Both orders and revocation at lock and release. |
| P7, P9 | `PRF-01/02/05`, `REC-01/02/03`, `INT-05`. Order, original associations and rewind. |
| Independent proof cuts and targeted restore | `PRF-03/04`, `REC-02/03`, `INT-05`, `PROD-02/06`. Exact-target case matrix and attempt fencing, scope/target/namespace proof, hold, rebound and acknowledgment classes. |

| Decisions | Application/evidence |
| --- | --- |
| DEC-01/02/06/10/11/12 | `DOM-01/02`, `HTTP-01`, `APP-01`–`APP-05`, `INT-06`, `DEL-01`. |
| DEC-03/08/13 | `PG-01`–`PG-05`, `PRF-01`–`PRF-05`, `REC-02`, `PROD-01/02`. |
| DEC-04/05 | `DOM-02`, `APP-01/05/06/07`, `PRF-03/04`, `REC-03`, `PROD-03/04`. |
| DEC-07/09/14 | `PERF-01`–`PERF-03`, `INT-01`–`INT-06`, `OPS-01`–`OPS-05`, `REC-04`, `PROD-05/07`. |

See each deferral and its blocked work in [deferred qualification](deferred-qualification.md). The [work graph](execution.md) maps each card to its milestone. [LLD case oracles](../../../Phase3-LLD/verification.md) · [PostgreSQL case oracles](../../../Phase3-LLD/postgres-evidence.md)
