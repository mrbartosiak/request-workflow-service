# B45 · Unversioned REST contract

The checked-in OpenAPI 3.1 document covers eight route families, with 11 paths and 12 operations. It lists the five transition alternatives as literal paths so each operation has its exact request and result schema. The Maven checks expand the actual constrained Spring mapping and compare the resulting method/path set with the document and independent route cases. The source uses JSON-compatible YAML 1.2 so the adopted Jackson parser can check the source and examples. The Spring handlers, handwritten maps and build pins come from the approved base. [OpenAPI source](../../../../adapter-http/src/main/openapi/requests.yaml) · [Accepted REST wire](../../../Phase3-LLD/rest.md) · [B45 acceptance](../../../Phase4-development-planning/tool-pilots.md#b45--unversioned-api-and-explicit-adapters)

The execution base was `ae4ca87437cbaf5f2a9527e375c4a10502218257`. The candidate parent is the reviewed B26 revision `6ffc8cbbfa43b08087aa6205f45908cf6305ab6f`. The refresh had zero overlap with the five B45 paths. The accepted `PH4-PLAN-r1` manifest matched SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, and all 30 entries matched after Windows checkout line endings were normalized to LF. Sol xhigh (56) executed and corrected the candidate after findings from Astra xhigh (62) on `501a891dc5afefbb957d280667d010deec6a1bd9` and `07847efef24b60c7179c7fe379856a2077f99e9f`. [Standing G0](../g0-remaining-plan-approval.md) · [Tooling decisions](../../../Phase3-LLD/tooling.md)

## Spring and contract evidence

MockMvc uses the existing `SyntheticEffectiveAccessConfiguration` controller composition and real validation, command, query and guarded-release classes. In-process port fixtures supply scoped records, grants and proof. The JDBC data sources fail any attempted connection. The real issuer signs a scoped key, which Create consumes and replays with one effect and its original 201, body and Location. The six existing-request result cases issue actual observations against pre-command rows and replay seeded committed receipts through the normal action routes. Request and response examples are compared to the declared schemas and actual JSON, with replacement only for generated opaque values. [Contract tests](../../../../bootstrap/src/test/java/com/company/requests/bootstrap/OpenApiContractTest.java) · [MVC fixture](../../../../bootstrap/src/test/java/com/company/requests/bootstrap/OpenApiMvcFixture.java) · [Schema and example checker](../../../../bootstrap/src/test/java/com/company/requests/bootstrap/OpenApiWire.java)

| Check | Coverage |
| --- | --- |
| Matched routes and authorization | 12 real route challenges with Bearer, no-store, a safe problem and zero transaction entries. Six unmatched or version-prefixed paths return safe 404. A wrong method returns 405 with the Spring Allow set. |
| Exact command input and headers | 20 body mismatch cases, nine query mismatch cases, absent versus explicit null, unexpected or duplicate members, Unicode text constraints, a single signed key, invalid signature, expiry, decompressed cap and media or encoding advertisement. Invalid shapes stop before entry to a transaction. |
| Protected successful output | Six committed action results and six action-context shapes, precise string revisions above JavaScript's safe-integer range, exact maximum signed-64-bit publication ID, per-caller field omission, omitted or real Browse continuation, and explicit null for the first history origin. |
| Safe failure envelopes | Stable status, type and title, exact allowed keys, a consistent correlation UUID and instance, and applicable Authenticate, Allow, Accept, Accept-Patch or Accept-Encoding. Tests include withheld publication proof, authority deferral and retryable uncertain outcome. |
| Ten deliberate mismatches | Removed Publish route, publication schema or payload coerced to a number, overflow, explicit null or omitted required ID, null masked field, leaked actor field, omitted history origin and incorrect Bearer challenge. Each seeded change is checked against the corresponding route, schema or actual Spring payload. |

The JVM checks exercise decoded HTTP requests in process. Connector handling of duplicate headers, encoded ingress limits and read deadlines remains native qualification. The 429 response is reserved for a deployed throttling policy. [B03 integration](../B03/integration.md)

## Independent review corrections

Detail and Browse share the schema restrictions for retained fields and publication state. A visible publication ID can accompany an omitted state where field grants permit. A visible state alongside the ID must be PUBLISHED. Deleted Browse and Detail omit retained name, body, display revision and publication ID. The real MVC cases check both projections with a full reader and a reader granted only row and publication-ID access.

The structural check requires the approved OpenAPI version, object-valued `info`, nonblank `info.title` and `info.version`, and the path objects. It checks the used request and response structure and recursively resolves known local component references. The structural mutants cover seven missing fields, a malformed `info`, and an unknown component.

| Control on the original contract | Observed result | Transcript and SHA-256 |
| --- | --- | --- |
| Exact `501a891` OpenAPI bytes with the new projection and structure controls | Five cases ran with three expected failures. Six independent assertions exposed the forbidden name or ID in deleted Browse and the ID in CREATED and VERIFIED Detail and Browse. Both masked-state publication projections passed. | `b45-v2-red-501a891-projections.log` · `7c3e527dd68d049771b18430286597144b968e55eefa0226a9cda8141b944ff7` |
| Original OpenAPI document with `info` removed in the isolated correction worktree | The normal mapping and document check failed with `Expected OpenAPI object: info`. The original document bytes matched their sealed hash again after restoration. | `b45-v2-red-501a891-missing-info.log` · `b7778d7d1e2a4446f1445f9df8f8900c6e11067cdd07be28fbe0d25223fbe832` |

The authentication check verifies that `BearerAuth` has type `http` and scheme `bearer`. Each of the 12 operations must require BearerAuth through its own security declaration or the inherited document security. The test accepts explicit required overrides and rejects an empty override, a sole anonymous alternative and an anonymous alternative alongside BearerAuth. The 12 existing MVC cases still check the actual 401 Bearer challenge with zero transaction entries.

| Authentication control | Prior checker on `07847ef` | Corrected checker |
| --- | --- | --- |
| Remove the BearerAuth `type` | All 76 cases passed. `b45-v3-red-07847ef-missing-type.log` · `e0d1f2c5acba4545b00edb19426e4dd54ae7b40979903d31a6b3741563602537` | The normal document check failed with `Expected BearerAuth with type http and scheme bearer`. `b45-v3-rejected-missing-type.log` · `e9b8248176cb0045a9719474f5b12c2a2da1c6e8bfedf9560a2fa287032cfed2` |
| Give Create an empty `security` override | All 76 cases passed. `b45-v3-red-07847ef-anonymous-create.log` · `6a1c6ee2cb4f6da77a859f0ea13e59c3e55bbe16bf2592ed54ae166edc502530` | The normal document check failed with `Expected required BearerAuth for post /requests`. `b45-v3-rejected-anonymous-create.log` · `8c9b683cde5362d6b706bdedafb9a68da092c0a34b44c19150a6ec4c6f226c77` |

The checks ran against disposable variants in the isolated worktree. The restored YAML matched the projection correction byte for byte.

## Reproduction

From the isolated worktree, supply the installed Maven 3.9.16, Temurin 25.0.5.1+2, B01 settings and the already qualified offline dependency cache. Run the same reactor in its default and synthetic HTTP build configurations. Both configurations use the in-process suite.

```powershell
$phase = Split-Path $PWD
$runtime = Join-Path $phase 'b01-tools'
$env:JAVA_HOME = Join-Path $runtime 'temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$settings = Join-Path $runtime 'm2/settings.xml'
$repo = Join-Path $phase 'wave2-integrated/repository'
$flags = @('-o', '-B', '-C', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$repo")
& $maven @flags clean verify
& $maven @flags -Psynthetic-http clean verify
```

The original pinned base passed 348 tests in the default reactor. The initial B45 candidate adds 71 cases. The B26 parent adds seven coordinator cases. The projection and document correction adds five cases. The authentication correction adds one case. Execution transcripts are in `tmp/phase5-development` at the workspace root.

| B45 source and parent | Configuration | Result | Transcript and SHA-256 |
| --- | --- | --- | --- |
| Initial on `ae4ca87` | Default | 419 passed in 18 suites, including 71 B45 cases. Zero failures, errors or skips. | `b45-final-default-clean-verify.log` · `0f2a567cbc4bc0ff13a2e5a829e0be88c74e854776ea0d1ff435d0c4ae07f5f3` |
| Initial on `ae4ca87` | Synthetic HTTP | 419 passed in 18 suites, including 71 B45 cases. Zero failures, errors or skips. | `b45-final-synthetic-clean-verify.log` · `d9cfd0048c26cce6818359a8a857fbb4e9573b516f5ee6982f6c1d88d61239c3` |
| Initial on `6ffc8cb` | Default | 426 passed in 19 suites, including 71 B45 cases. Zero failures, errors or skips. | `b45-6ffc8cb-default-clean-verify.log` · `8d64e7288bcb9bb24693d69179673b8642d4c4c550c67c7c2d1eb76252c05017` |
| Initial on `6ffc8cb` | Synthetic HTTP | 426 passed in 19 suites, including 71 B45 cases. Zero failures, errors or skips. | `b45-6ffc8cb-synthetic-clean-verify.log` · `77bca43b1960dc42878fe1633c3ba5b103b37abda86992e7519cccfb8c520a29` |
| Projection and document correction on `6ffc8cb` | Default | 431 passed in 19 suites, including 76 B45 cases. Zero failures, errors or skips. | `b45-v2-6ffc8cb-default-clean-verify.log` · `9044ee9a9026e9f31bb6ff618c06e33989ffb94cb0117da51a530c92efae8aa1` |
| Projection and document correction on `6ffc8cb` | Synthetic HTTP | 431 passed in 19 suites, including 76 B45 cases. Zero failures, errors or skips. | `b45-v2-6ffc8cb-synthetic-clean-verify.log` · `e5730943b1db9ca34ff3298946b2edcc16705e62d17a9720ba9569697d2347da` |
| Authentication correction on `6ffc8cb` | Default | 432 passed in 19 suites, including 77 B45 cases. Zero failures, errors or skips. | `b45-v3-6ffc8cb-default-clean-verify.log` · `4abcd004f2a7bef9ab5c1fc6acc691bbc65f6de5c298f13cee9251e6eeeda2b3` |
| Authentication correction on `6ffc8cb` | Synthetic HTTP | 432 passed in 19 suites, including 77 B45 cases. Zero failures, errors or skips. | `b45-v3-6ffc8cb-synthetic-clean-verify.log` · `3348c429003c8359ae942ff0072d15992cf191680129501b89155bb3ef0f714a` |

## Reviewed adapter compatibility

- **B05 JDBC row.** The independent empty and populated V001→V002 migration runs at reviewed `b8ea5bfb3fbfe126663ea68884e043a555f10264` confirmed preserved data, exact UTF-8, scoped mapping and signed-64-bit publication ID `9223372036854775807` in both a Publish row and the original receipt. The populated-run transcript matched SHA-256 `4a8a3f35b7d02765e8eca2fbcf00ae190d0aa9f98647581ee99ca084155001c1`. B45 verifies that ID as an exact decimal string at its HTTP boundary. [B05 integration](../B05/integration.md) · [Independent review](../B05/review.md) · [Migration fixture](../../../../fixtures/postgresql/B05Postgresql.java)
- **B22 versioned internal envelope.** The independent native run at reviewed `59be818e2596462a4eb2ab95d7e9e209d56b7d4f` passed all 17 checks. Its transcript at `tmp/phase5-development/b22-review-59be818/logs/b17-independent-01.log` matched SHA-256 `c7472557c35f021e150f38b9d0fd6a962f7ac037efda14ae5701007a54a2ad29`. The retained v1 `publish-request` projection survived migration and typed reading with its exact nanosecond schedule. The new v2 `published-request-reference` projection retained the one scoped effect on Publish replay. The strict reactor reruns the version and projection allowlist tests. [Native fixture](../../../../fixtures/postgresql/B22Postgresql.java) · [Versioned obligation tests](../../../../application/src/test/java/com/company/requests/application/OutboundObligationTest.java)

## Successor handoff

- **B46.** Use the corrected OpenAPI source above as the seeded pre-release baseline after independent approval. Its canonical UTF-8 LF SHA-256 is `ebc002385b46c1f54d278eb51eebcfb3865f4299833f64631733a0aad89a9560`. The manual controls show the route oracle detects Publish route removal and the schema oracle detects an ID type change to number. B46 owns the separately gated tool run, safe additions and release-baseline comparison. [B46 acceptance](../../../Phase4-development-planning/tool-pilots.md#b46--t3-r-api-diff-pilot)
- **B43.** Reproduce either reactor check for document structure, effective Bearer requirements, exact route set, body and query controls, stable problem headers, masked JSON and precision. The deliberate mismatches provide the local compatibility regression cases. [B43 acceptance](../../../Phase4-development-planning/operating-verification.md#b43--security-advisory-and-critical-quality-controls--e7)
- **B48.** Use the OpenAPI request and response examples for signed key issuance, original Create Location, action context and its observation, exact command replay, masked detail and Browse, and ordered history. Replace opaque placeholders with keys, observations and cursors returned by the service. [B48 acceptance](../../../Phase4-development-planning/delivery.md#b48--private-interview-walkthrough-and-source-only-package)
