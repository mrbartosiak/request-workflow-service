# B03 MVC input validation and safe errors

**Executor disposition.** Ready for independent review on local commit **11a48ac39a76eb5de7b4f7adfc855cf3b1d3c631** on codex/b03-http-edge. The assigned red base was 056c4e0bbb74df16b16684aad5fa1dc82cf1f22a. The initial implementation commit c22d6690026701919b14fda10c07f5df24c397c9 was rebased onto the integrated B02 commit d1c34c7a781ae57ac94b258f998ac1958424100e. The adapter content was identical across the rebase. The final commit changes eight files, all in adapter-http. The candidate worktree was clean and git diff --check passed. [Assignment](assignment.md) · [B01 integration](../B01/integration.md)

**Authority and sources.** PH4-PLAN-r1 manifest SHA-256 f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6, authorized wave 1 G0, and B01's G0-B adopted local build. Executor GPT-6.2 Sol high (55), assigned independent reviewer GPT-6.2 Astra medium (57). The evidence uses the REST and E2 contracts, route-specific observation and key boundaries, and browse limit and access rules. [G0](../g0-kickoff.md) · [G0-B](../B01/g0-b-adoption.md) · [B03](../../../Phase4-development-planning/foundation.md#b03--mvc-input-validation-and-safe-error-mapping) · [REST](../../../Phase3-LLD/rest.md) · [E2](../../../Phase3-LLD/verification.md) · [Observations](../../../Phase3-LLD/observations.md) · [Retries](../../../Phase3-LLD/retries.md) · [Browse](../../../Phase3-LLD/queries.md) · [Access](../../../Phase3-LLD/access.md)

## Before and after

Run date 2026-11-22 Europe/Warsaw. The in-process Spring servlet fixture starts the WebApplicationContext with the shipped RequestsMvcConfiguration, actual DispatcherServlet request handling, and test-only route beans. The fixture mutation sink records calls and the last DTO. It resets before each invocation. All requests use synthetic fixtures, with one opaque fixture key and no actor or credential source. Positive detail and result projections are fixture values.

| Run | Exact observation | Local log SHA-256 |
| --- | --- | --- |
| Base red, 16:09:16 +01:00 | On B01 production code, the three initial test requests used a raw String body and the test mutation sink. Malformed JSON, text/plain and repeated keys each returned 201 with one mutation entry call. They failed the intended status assertions of 400, 415 and 400 and the zero-call assertion. Three tests, three assertion failures, zero setup errors. | 6d6fdd657c3431eb0766d1d2070ffe57803f73fcd3d04654883a7737a01f3f2f [Log](../../../tmp/phase5-development/b03-tools/logs/b03-base-red.log) |
| Negative controls, 16:19:06 +01:00 | Temporarily removed the duplicate-member guard and raised the decoded limit by one byte. Two duplicate forms and two one-byte-over forms, identity and gzip, reached the sink once and returned 201 in place of 400 or 413. The scoped run reported 36 tests, four intended failures and zero errors. Restored the original validator bytes before committing. | 2ef7e9d41c1e0b06ad92903f4749dca948fb36303dc06375bf7d25bc61e5372a [Log](../../../tmp/phase5-development/b03-tools/logs/b03-negative-controls.log) |
| Candidate module, 16:21:03 +01:00 | Four reactor projects passed. All 121 B03 tests and all 156 B02 Lifecycle tests passed. Zero failures, errors or skips. | 293a872c4999559e0ce26e601072d910343593a6cdf4c28522c0f0e807658da1 [Log](../../../tmp/phase5-development/b03-tools/logs/b03-module-candidate.log) |
| Candidate full reactor, 16:21:48 +01:00 | All 11 projects passed. All 281 tests passed, including 121 B03, 156 B02 and 4 B01 tests. Zero failures, errors or skips. | 51e90d52107deb4b337fb0110a6251435a36fceb3a5caef547c9cd54672b2110 [Log](../../../tmp/phase5-development/b03-tools/logs/b03-reactor-candidate.log) |

## E2 request outcomes

The 121 test invocations send 162 requests. All 138 rejected requests assert zero mutation entries and an absent sink input. The other requests comprise 23 success assertions and one committed-response fault check. The accepted write fixtures cover five Create, two Edit and five named transition calls.

| Wire input or response | Observed result |
| --- | --- |
| Unsupported media or charset, missing type, and unsupported coding | 415 unsupported_media. The media response advertises application/json via Accept and includes Accept-Patch for PATCH. A coding failure advertises gzip and identity via Accept-Encoding. |
| Exact and one-byte-over decoded body sizes | A valid JSON object padded to 131,072 bytes enters the Create sink under identity and gzip. At 131,073 bytes each returns 413 request_too_large. Compressed fixtures occupy under 1,024 encoded bytes. Invalid gzip framing returns 400. |
| JSON shape and decoding | 400 invalid_input for missing, null, blank, malformed, duplicate or escaped-equivalent duplicate fields, unknown and client scope fields, coerced scalars/arrays/objects, trailing tokens, comments, invalid UTF-8, unpaired surrogate escapes and U+0000. Known invalid fields may appear as fixed field names. |
| Unicode and decoded byte bounds | Multilingual Create input preserves casing, punctuation, edge whitespace, supplementary characters and combining marks through the DTO and fixture detail response, with matching UTF-8 bytes. Exact name, body and reason bounds of 256, 16,384 and 2,048 bytes are accepted. Unicode White_Space-only values and over-limit bytes reject before entry. |
| Key, reference and observation inputs | Duplicate or coalesced Idempotency-Key values, missing, blank and oversized keys, malformed reference encoding and over-bound refs reject with 400. Exact 1,024-byte key and 128-byte decoded opaque ref limits are exercised. Numeric and descriptive decimal-string expectedRevision inputs reject, including the maximum signed 64-bit display value. The bounded observation string is passed as decoded, with the exact 2,048-byte limit tested. |
| Named requests and queries | Create requires type, name and body. Edit requires body and observation. Verify, Accept and Publish require observation. Reject and Delete additionally require reason. Unexpected write queries reject. Key issuance, Detail and History require empty body and query. Action context takes one case-exact named action. Browse rejects duplicate, unknown and incorrectly encoded parameters. It preserves exact name bytes, validates state, tests size default 10 and upper bound 50, and tests the 2,048-byte ASCII cursor limit. |
| Success and error responses | JSON success uses UTF-8 and Cache-Control no-store. The fixture proves exact string serialization of revision and publication ID and a permitted Create Location. Errors use UTF-8 ProblemDetail with the specified status, stable type and fixed title, one generated UUID correlation and a matching safe occurrence URN. They carry no request path, raw input, exception detail or Location. A 405 includes Allow. An uncommitted 500 discards prepared body and headers. An already committed response receives no second payload. |

## Reproduction

Run from the candidate worktree at tmp/phase5-development/b03-http-edge. The isolated repository is a separate copy of B01's approved patched-repository. The B01 settings file supplies both user and global settings for the Maven invocation. The adapter adds test-scope references to the already adopted Spring Test 7.0.11, JUnit Jupiter 6.1.3 and JUnit Platform Launcher 6.1.3. Production dependency coordinates and reactor edges are unchanged. Jackson is the adopted 3.1.8 and the provided Servlet API is 6.1.0. No service, fixture access, dependency download or host settings edit was used.

~~~powershell
$phase = (Resolve-Path ..).Path
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$repository = Join-Path $phase 'b03-tools/repository'
$mvn = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
& $mvn -s $settings -gs $settings "-Dmaven.repo.local=$repository" -o -B -C -pl adapter-http -am clean verify
& $mvn -s $settings -gs $settings "-Dmaven.repo.local=$repository" -o -B -C clean verify
~~~

The negative controls used the same local toolchain and Maven flags with -pl adapter-http -am, the Surefire filter HttpBoundaryTest#rejectsMalformedOrInexactCreateObjects+rejectsOneByteBeyondDecompressedBodyLimit, -Dsurefire.failIfNoSpecifiedTests=false, and the test goal.

## Successor handoff

| Owning successors | Contract and remaining proof |
| --- | --- |
| B06–B08 | Inject the RestInputValidator bean. Authenticate through the security chain, then call keyIssuance or create before command/application entry. The validator returns the exact key and decoded input bytes for B07 verification, claim checks and intent digests. Use RestProblems.create or RestProblems.response for safe catalogued outcomes and attach any configured WWW-Authenticate header at the authentication boundary. B06 owns synthetic identity, access and issued ref reservation. B08 supplies the real Create route, durable command and guarded replay/disclosure. Corporate identity qualification follows in B52. |
| B13–B15, B17 | Call actionContext, edit or action at the controller boundary. The decoder preserves bounded expectedRevision strings and rejects display decimals. B13 owns the actual observation envelope format and codec. The application owns actor/organization binding, scope, access, material facts, lifecycle and authorization-dependent errors. |
| B19–B21 | Use reference for Detail and History and browse for the approved query inputs. The REST edge accepts opaque decoded ref text within 128 bytes. The 32-character base64url rule governs issuance. B19–B21 own guarded reads, masks, exact result fields, cursor authentication, size/filter matching and live continuation. The B03 type, state and action literals match the corresponding integrated B02 domain values. |
| B45 | Compare the unversioned OpenAPI cases with actual MVC wire behavior, including literal ID strings and problem headers. Deployment verification needs the selected servlet runtime and ingress to enforce a separate encoded request limit and read deadline. B03 bounds the decoded read at 131,072 bytes plus one sentinel byte. Gzip can consume encoded framing before producing decoded bytes, so the decoded limit cannot serve as an encoded transport budget. Include bodyless routes, transport errors and safe logging in deployment checks. Spring's default no-handler advisory logged the synthetic raw route in this fixture. Carry the generated occurrence correlation into approved sanitized diagnostics and verify any security or throttling response headers at their owning boundary. |

The MVC configuration contributes the validator, global advice and success response headers. Test route classes are isolated to test sources and the package listing confirms their absence from the production JAR.
