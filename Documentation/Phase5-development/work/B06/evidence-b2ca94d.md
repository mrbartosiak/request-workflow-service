# B06 · Scoped-subject correction

**Executor disposition, 2026-11-22 (Europe/Warsaw)** GPT-6.2 Sol xhigh (56) corrected the blocking stable-subject finding. The candidate is ready for independent GPT-6.2 Astra high (60) recheck. [Assignment](assignment.md) · [Independent finding](review-5695f95.md) · [Original evidence](evidence-5695f95.md) · [G1-U](../B04/g1-u-approval.md)

| Item | Exact identity |
| --- | --- |
| Approved `PH4-PLAN-r1` manifest SHA-256 | `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6` |
| Original B06 candidate and parent | `5695f9500439aaa5fcdffc7963bd8d31747031bd` |
| Original evidence SHA-256 | `e06d1cab2add97376b0eb80771e5936544441bcd5f17671847a8ef253ee4ffc8` |
| Corrected candidate on `codex/b06-authority` | `b2ca94d2bff9bf3b11901b856787c7864de6f8d2` |
| Corrected tree | `6f53c1ff9d454f652e024b2e25a074ea4973603c` |

## Correction and observed results

The earlier registry enforced credential uniqueness and stable-principal consistency. Two credentials could still provision the same organization and provider subject to distinct actors. The corrected constructor binds each `(organization, subject)` to one complete actor after enforcing the resolver’s configured issuer. A second credential for that subject must resolve to the same actor. Distinct aliases and credential rotation remain supported. Organizations can independently provision the same subject. The exception uses a fixed message without credential, subject or actor values. The commit touches the HTTP resolver and its test class only. [Resolver](../../../tmp/phase5-development/b06-authority/adapter-http/src/main/java/com/company/requests/adapter/http/SyntheticActorResolver.java) · [Regression](../../../tmp/phase5-development/b06-authority/adapter-http/src/test/java/com/company/requests/adapter/http/SyntheticActorResolverTest.java)

| Evidence | Observation | SHA-256 |
| --- | --- | --- |
| Targeted red against `5695f95` | The new negative test failed because the resolver accepted an alternate stable principal for the same scoped subject with a different credential. The alias, rotation and cross-organization control passed. Exit 1, six tests with one failure. | `efaeade6994beb7537a8cb48a70352f8cea35ae9279164761c4037a2d6bc4be4` |
| Corrected offline reactor | Six resolver tests passed, including rejection of a conflicting human or service binding and error-message redaction. All 292 tests across 11 projects passed. Exit 0. | `2508f3de88b59ea29d11a169003847237eab592a0dad955383442de593a82942` |
| Corrected dual-cluster run | PostgreSQL 18.7 and pgJDBC 42.7.14 completed the authority, lock, durability and bounded release tests on separate workflow and proof clusters. Exit 0. | `ee4833fbb86c9d45fe5a2b0e6674921edaa524ca46b97537b28416cf29c87f6c` |

[Red log](../../../tmp/phase5-development/b06-fixture/logs/b06-subject-red.log) · [Offline reactor log](../../../tmp/phase5-development/b06-fixture/logs/b06-subject-green-reactor.log) · [Dual-cluster log](../../../tmp/phase5-development/b06-fixture/logs/b06-subject-green-01-candidate.log)

The targeted red used `-pl adapter-http -am '-Dtest=SyntheticActorResolverTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`. The green used `clean verify`. Both used Maven 3.9.16, Temurin `25.0.5.1+2-LTS`, the approved B01 file for both `-s` and `-gs`, the copied G1-U cache and `-o -B -C -ntp`. The wrapper ran the real-engine check with `-Mode candidate -RunId b06-subject-green-01` after the previous reviewer’s teardown. [Reproduction](../../../tmp/phase5-development/b06-authority/fixtures/postgresql/README.md#run-the-b06-synthetic-authority-and-guarded-release-checks) · [Launcher wrapper](../../../tmp/phase5-development/b06-fixture/Run-B06.ps1)

Workflow system ID `7711038981516125992` and proof system ID `7711039002651912680` were distinct. The fixture stopped the initial postmasters `17000` and `10268` during the measured restarts, then stopped workflow `13852` and proof `19164` at teardown. It reported `pg_ctl status=3` and loopback 55480/55481 free. Port 55482 was unused. The original evidence records the synthetic scope limits and successor contracts. [B07–B13 handoff](evidence-5695f95.md#successor-handoff)
