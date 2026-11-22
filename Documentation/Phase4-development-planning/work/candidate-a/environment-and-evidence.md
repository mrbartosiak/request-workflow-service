# Environment preflight and evidence record

**Starting inventory · 2026-11-22 Europe/Warsaw** The owner installed OpenJDK 25.0.2 in `C:\Users\Asia\.jdks\openjdk-25.0.2`. The coordinator verified `bin\java.exe -version` and `bin\javac.exe -version` at that exact path. Unqualified `java` and `javac` on checked PATH resolve to Temurin 21, and `JAVA_HOME` is unset. Select the explicit JDK or a session-local toolchain in local preflight. Use a portable Java 25 requirement in the project. No runnable application, build manifest, Java service sources, PostgreSQL daemon/CLI, Docker, Maven or Gradle were found in the checked workspace or PATH. Phase 4 records planning and inventory. [Earlier LLD readiness](../../../Phase3-LLD/work/reviews/performance-readiness.md)

## Preflight in Phase 5

1. Inventory existing paths and prepare proposals in `FND-00`. After G0, use approved local pins in `FND-01`. Record OS/shell, CPU/memory, disk, ports, selected JDK path and exact `java -version`/`javac -version`. Require 25 at both selected CLI and build/test toolchain. Record approved wrapper/build tool version, checksums, dependency repository access and trust settings. Verify resolved Spring Framework `7.0.11`, compatible servlet and test runtime, and start/stop a clean build. Resolve runtime gaps with the environment owner before boot evidence. DFR-5 owns production pins.
2. Propose the PostgreSQL 18 minor and driver with local-only access for G2b. After G2a/G2b, use `PG-01` to record the disposable endpoint alias, UTF-8 and time settings, reset and migration steps, isolated roles and restricted credentials. Prove primary access from separately pooled processes and isolation from unrelated data. Keep fixture authority/journals outside the workflow restore/reset boundary. Use synthetic scope, fixture keys and disabled outbound real networking. Check command, release and worker connections, and exclude test latches from release builds.
3. Preflight separate main and fault-test launch commands, portable reset, schema checksum, monotonic caller timing and independently configurable loss/barriers. Prove diagnostics with a deliberately failing case. Record timeouts, teardown and test data/control scope. Setup drift returns the scenario to blocked. Invite owner review of any material alternative.

## Per-card evidence bundle

| Record | Required fields |
| --- | --- |
| Provenance | Task/case IDs, exact plan/LLD/source and fixture revisions, agent/reviewer, gates and approval scope, source and environment/dependency hashes, commands, timestamp. |
| Red/green | Expected result, intended red failure and command, green pass and command, tests and regression results. For standalone evidence cards, capture the oracle's failing control before executing the qualifying case. |
| Determinism | Seed/fixture size, actor and scope aliases, clocks, barrier events and timeout, connection/instance IDs, `pg_blocking_pids` where applicable, fault injection cuts and ordering. |
| Outcomes | Sanitized HTTP status/body/headers and failure classification, before/after durable counts, identities, journal and egress observations, coverage and compatibility deltas, excluded paths. |
| Disposition | Planned, blocked, inconclusive, failed, passed or owner accepted. Record known gaps, reviewed commit and artifact digest. Changes to fixtures or behavior call for reviewed reruns of affected evidence. |

Store raw sanitized evidence for normative assessments, including timing samples. Keep credentials, raw keys, tokens, bodies, reasons, payloads and company data outside source and public evidence. Protect private recovery and authorization records. Fixture proof supports only the tested topology and target. [LLD evidence](../../../Phase3-LLD/verification.md) · [Operating logs](../../../Phase3-LLD/operations.md)
