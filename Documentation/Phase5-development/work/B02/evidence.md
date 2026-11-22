# B02 shared lifecycle evidence

**Candidate** `d1c34c7a781ae57ac94b258f998ac1958424100e` on `codex/b02-lifecycle`, tree `6ddb2f20a477533b41ea87d3d3c4a478aa9fa08c`, parent `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a`. The worktree is clean and all 17 changed paths are under `domain/`.

**Authority** PH4-PLAN-r1 SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. GPT-6.2 Sol medium (53) execution, GPT-6.2 Astra medium (57) assigned independent review. Executor result **pass, ready for review**. [Assignment](assignment.md) · [G0](../g0-kickoff.md) · [G0-B](../B01/g0-b-adoption.md) · [B01 integration](../B01/integration.md)

## Complete matrix and source checks

The table applies unchanged to `HR_TICKET`, `BUG_REPORT` and `INTERNAL_REQUEST`. A dash expects `Lifecycle.IllegalState` for an authorized, current new intent. The suite exercises all 147 cells independently, including 21 Start cases and 126 existing-state cases. There are 27 allowed cells and 120 denied cells across the three types. The existing-request tests also exercise the edit and transition effect constructors on their applicable allowed and denied pairs. [B02](../../../Phase4-development-planning/foundation.md#b02--shared-state-machine-and-invariants) · [Domain table](../../../Phase3-LLD/domain.md) · [E1](../../../Phase3-LLD/verification.md)

| Current | Create | Edit | Delete | Verify | Accept | Reject | Publish |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Start | CREATED | — | — | — | — | — | — |
| CREATED | — | CREATED | DELETED | VERIFIED | — | — | — |
| VERIFIED | — | VERIFIED | — | — | ACCEPTED | REJECTED | — |
| ACCEPTED | — | — | — | — | — | REJECTED | PUBLISHED |
| DELETED | — | — | — | — | — | — | — |
| REJECTED | — | — | — | — | — | — | — |
| PUBLISHED | — | — | — | — | — | — | — |

| Test in `LifecycleTest` | Source and observed effect |
| --- | --- |
| `startOnlyAllowsCreate` and `exactMatrixForExistingRequests` | Common eligibility and terminal denials. Create assigns supplied immutable identity and origin, preserves exact name/body, sets revision and sequence to 1 and emits one fact with absent prior state. Allowed writes retain identity, increment revision, and advance sequence only on transitions. [Domain](../../../Phase3-LLD/domain.md) |
| `allFourTerminalPathsHaveContiguousHistory` | Per type, Create → Delete, Create → Verify → Reject, Create → Verify → Accept → Reject and Create → Verify → Accept → Publish. Facts match ordered commands, state pairs, occurrence and contiguous per-request indexes. Delete marks body concealment, Delete and Reject carry separate reason payloads, and Publish attaches `Long.MAX_VALUE` exactly. [Domain](../../../Phase3-LLD/domain.md) · [Retention](../../../Phase3-LLD/retention.md) · [Identifiers](../../../Phase3-LLD/identifiers.md) |
| `editsPreserveExactTextAndIdentityAndDoNotAppendHistory` | Per type, a changed-body edit in CREATED and equal-body edit in VERIFIED preserve name and accept exact multilingual text, decomposition, emoji, surrounding whitespace and line endings. The write revisions are 1 through 5, while the facts have sequences 1, 2, 3 at revisions 1, 3, 5. [Domain](../../../Phase3-LLD/domain.md) · [Persistence](../../../Phase3-LLD/persistence.md) |
| `bodyAndReasonCannotBeMissingOrBlank`, `positiveCountersAndPublicationPairAreChecked` | Null/blank payloads, including Unicode White_Space, fail. Counters and creation order are positive, overflow fails, and only PUBLISHED has a positive publication ID. [Domain](../../../Phase3-LLD/domain.md) · [Persistence](../../../Phase3-LLD/persistence.md) · [Owner identifiers](../../../Phase3-LLD/owner-identifier-requirements.md) |
| `policyMakesOnlyADecisionFromPermittedFacts` | A synthetic Accept policy denies absent approval, allows with an optional routing intent, and leaves the immutable snapshot and common eligibility intact. The caller applies the separate state effect. [Domain](../../../Phase3-LLD/domain.md) · [Wiring](../../../Phase3-LLD/wiring.md) |

## Runs and reproduction

Local synthetic unit values only. Maven 3.9.16, Temurin 25.0.5.1+2, managed JUnit 6.1.3. Windows local date 2026-11-22, Europe/Warsaw. Each row pins a raw log in `tmp/phase5-development/b02-tools/logs/`.

| Run | Exit and decisive observation | Log SHA-256 |
| --- | --- | --- |
| Baseline red, 16:09:15 | 1. With the initial oracle and test-only POM overlay on the parent, Maven reached domain test compilation and failed on missing domain symbols including `Action` and `RequestState`. | `9b1bc0e9db0280989166436d0c6bcdd9f9394bad44876f4ead2cf35a6318f5e5` |
| Negative control, 16:11:49 | 1. Temporarily allow PUBLISHED/EDIT in `NEXT` and replace the second argument to `request.advance` in `edit` with `Math.incrementExact(request.lastSequence())`. The oracle detects 12 assertion failures, three for terminal denial and nine for sequence behavior, with zero test errors. Restored the original file bytes before verification. | `df6a0f9ac44acb9e5910b76a2d9d0cf973f0382808153314016db27d82b9fa1f` |
| Domain verify, 16:12:00 | 0. 156 tests, all passed. | `81d5160e14275ae7bbf2cb19efe1da0258af51ff818eadc6f2b09a3ad342ea73` |
| Full reactor verify, 16:12:19 | 0. All 11 projects passed, with 156 domain tests and four predecessor bootstrap tests. | `db3304a0e6c0078a58f7f1c424cc80c369ec365af335eead7c218bdff66b77c9` |

From the B02 worktree or a sibling review worktree at the candidate, use the adopted settings and an isolated copy of B01's `m2/patched-repository` as `repository` under the review scratch directory. This is the recorded B02 invocation.

```powershell
$b01 = (Resolve-Path ../b01-tools).Path
$b02 = (Resolve-Path ../b02-tools).Path
$env:JAVA_HOME = "$b01/temurin/jdk-25.0.5.1+2"
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$mavenArgs = @('-o','-B','-C','-s',"$b01/m2/settings.xml",'-gs',"$b01/m2/settings.xml",
    "-Dmaven.repo.local=$b02/repository")
& $maven @mavenArgs -pl domain -am clean verify
& $maven @mavenArgs clean verify
& "$env:JAVA_HOME/bin/jdeps.exe" --print-module-deps domain/target/domain-0.1.0-SNAPSHOT.jar
```

The red and negative-control invocations use the same Maven arguments with `-pl domain -am clean test`. To reproduce the baseline capability failure in an isolated parent checkout, apply the candidate's domain POM and test file only. The domain dependency tree contains test-scope dependencies only, and the production JAR reports `java.base` via `jdeps`. JAR SHA-256 `07747fa0163f5aa667884b48fc064d238af5483daa0efe4040e0218f77c2aa5e`, `requests-dependency-tree.json` SHA-256 `e968cc0338771e61c0a2ed58db8a3cb4081f5c9a5b484607ed7bf78ae64f0036`. Spring and project dependencies remain banned by the domain enforcer.

## Domain handoff

- **B08** Use `Lifecycle.create(Creation, TransitionFact.Occurrence)` with trusted organization, originating `PersonId`, reserved opaque `RequestRef` and immutable `CreationOrder`. `Created` carries the request snapshot, body and creation fact. The Create coordinator owns key arbitration, reservation proof, initial assignment, transactional write and receipt, replay, restart checks and guarded release. [B08](../../../Phase4-development-planning/first-slice.md#b08--first-runnable-durable-create-with-exact-retry)
- **B13/B14** Use `Lifecycle.next(state, action)` to check common eligibility after disclosability and access checks. `Lifecycle.edit(request, body)` returns only updated metadata and replacement body. `Lifecycle.transition(request, command, occurrence)` returns updated metadata and a fact for Verify or Accept. Compare the trusted acting person with `Request.originatingPersonId()` under the coordinator's distinct-person check, alongside scoped grants, current observations and permitted type-policy facts. `WorkflowPolicy<F,R>` returns a deny or an allow with optional routing intent. The coordinator interprets that intent and owns effects. [B13/B14](../../../Phase4-development-planning/workflow.md) · [Access](../../../Phase3-LLD/access.md)
- **B15** `Delete` and `Reject` require a `TransitionReason`. Their `Transitioned` effect carries the fact and separate reason. `concealBody()` identifies Delete for the atomic visibility update. Protected storage, receipt access, scope evidence and real HTTP concealment remain downstream proof. [B15](../../../Phase4-development-planning/workflow.md#b15--delete-and-reject)
- **B16/B17** `Publish(PublicationId)` consumes a supplied positive signed-64-bit number in the shared `PublicationId.INITIAL_NAMESPACE`. The transition returns the PUBLISHED snapshot and fact. The journal and coordinator own assignment order, burn and restore exclusion, local commit/witness, independent confirmation, exact retry and lossless decimal-string HTTP representation. [B16/B17](../../../Phase4-development-planning/publish.md)

The B02 result completes the E1 domain check. The remaining E1 proof uses real HTTP action context and stale/denied/replayed commands, then checks durable effect counts, protected payload visibility and history after restart. [E1](../../../Phase3-LLD/verification.md) · [PostgreSQL evidence](../../../Phase3-LLD/postgres-evidence.md)
