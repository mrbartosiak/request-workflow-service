# B01–B04 · Build, lifecycle and input edge

### B01 · Reproducible Spring build

**Needs** G0 with approved qualification scope in C5. Obtain G0-B before exact local tool/driver adoption or environment changes beyond the approved qualification. **Sources** Java/Spring versions, dependency graph and assembly. [Wiring](../../../Phase3-LLD/wiring.md) · [Preflight](environment.md)

**Red** Compile a Java 25/Spring Framework 7.0.11 MVC context smoke and enforce the approved project dependency graph. Capture the initial missing or incompatible capability and the first valid red assertion on provisioned tools. **Green** Root `com.company.requests`, explicit module graph and bootstrap `@Configuration/@Bean`, configured toolchain and repeatable empty context start. Keep dependencies explicit and pinned in the approved baseline. **Stop** Record resolved versions, direct/transitive inventory, commands and dependency graph. Hand module ownership and build entry points to B02–B04.

### B02 · Shared state machine and invariants

**Needs** B01. **Sources** State table, effects and transition facts. [Domain](../../../Phase3-LLD/domain.md) · [E1](../../../Phase3-LLD/verification.md)

**Red** Enumerate every state/action pair for each initial type. Assert four full terminal paths, body edits in CREATED/VERIFIED, immutable name, mandatory reasons, terminal denial and distinct revision versus history sequence effects. **Green** Domain values and pure `Lifecycle`/policy contract using approved external ports for coordination. **Stop** Record the complete matrix and no Spring dependency in domain. Hand values and effect contract to B08/B13–B17.

### B03 · MVC input validation and safe error mapping

**Needs** B01. **Sources** Route inputs, validation and Problem Details. [REST](../../../Phase3-LLD/rest.md) · [E2](../../../Phase3-LLD/verification.md)

**Red** Send wrong media, oversized decompressed body, malformed/duplicate/unknown/coerced members, invalid UTF-8, surrogate/U+0000, mandatory nulls, blank Unicode strings, repeated key headers, unexpected query and invalid revision forms. Observe safe protocol errors and zero mutation entry calls. Assert accepted multilingual bytes round-trip through the boundary. **Green** Explicit Spring MVC DTOs, bounded parser and field checks, safe standard headers, stable error codes and UTF-8 single response policy. **Stop** Capture test cases and call counters. Hand edge contracts to B06–B08, B13/B19–B21 and B45.

### B04 · Qualify the isolated PostgreSQL evidence environment

**Needs** B01 and G1-Q fixture qualification authorization. **Sources** Environment requirements and evidence harness. [Preflight](environment.md) · [PostgreSQL](../../../Phase3-LLD/postgres-evidence.md)

**Red** Run the capability probe for the candidate PostgreSQL 18 minor and client/driver, role isolation, independent proof store, two bounded connections, lock introspection and backup/restore path. Report the first missing capability. **Green** Provision or select an authorized disposable environment under G1-Q, then record exact pin candidates, bootstrap/cleanup commands and dependency review. Finish the repeatable probe. **Stop** Reviewer verifies the report, proof failure boundary and least privileges. If the exact pin/access method is still undecided, obtain G1-U use disposition before B05/B06. Hand test connection and role contract to B05 and the environment inventory to B32.
