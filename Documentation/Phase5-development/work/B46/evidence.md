# B46 local OpenAPI compatibility pilot

The two local comparisons agreed on all four cases under the owner-approved G6-P trial. Both rejected removal of Publish and changing its response `publicationId` from a decimal string to a JSON number. Both accepted the unchanged source and the added route. The standalone validator reproduced the cursor pattern discrepancy on the exact unchanged source. Sol xhigh (56) executed the pilot on 23 November 2026. [Assignment](assignment.md) · [G6-P approval](g6-p-approval.md) · [Acceptance](../../../Phase4-development-planning/tool-pilots.md#b46--t3-r-api-diff-pilot)

## Provenance and cases

- The runner read `adapter-http/src/main/openapi/requests.yaml` as a Git blob at approved B45 `231589477a114b00fc127855e0598a0d56dc6727`, tree `8b07b3fb595eec3c2968740ff3c0962cf3f2b299`, blob `e44ff260bd329a865e235d51fcd86cd07846c639`. The canonical UTF-8 LF SHA-256 is `ebc002385b46c1f54d278eb51eebcfb3865f4299833f64631733a0aad89a9560`. The PH4-PLAN-r1 manifest SHA-256 is `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. All 30 entries matched. [B45 approval](../B45/review-2315894.md) · [Input receipt](../../../../tmp/phase5-development/b46-pilot/evidence/inputs.json) · [Verification receipt](../../../../tmp/phase5-development/b46-pilot/repeat-confirmation.json)
- The official Windows x64 `oasdiff` 1.39.0 archive SHA-256 was `956a73df88e03efdf3fcc5f327446cc176fefe4adcea1978b0fef464a1ee8901`. The executable matched `dfed65cfbd810ca725c9b02a61e419454521745f4bf2c9e607771da1bfee11f1` and reported `1.39.0`. The runner used Node v23.5.0 and Git 2.46.0.windows.1. [Portable qualification](qualification.md) · [Pinned input hashes](../../../../tmp/phase5-development/b46-pilot/source-inputs.sha256)
- The pull-request equivalent used a local copy of the approved B45 target. The release equivalent selected its own local baseline file through a manifest that pins the same B45 bytes. JSON comparisons verified exactly one intended semantic edit in each changed specimen. All `$ref` values in the specimens and baselines were local fragments. The trial ran offline, with external references, remote fetch and hosted comparison disabled. [Release selection](../../../../tmp/phase5-development/b46-pilot/evidence/release-selection.json) · [Runner](../../../../tmp/phase5-development/b46-pilot/run-pilot.cjs)

| Candidate | Manual result | Pull request `breaking` | Release `changelog` |
| --- | --- | --- | --- |
| Unchanged B45 bytes | Empty patch, pass | Exit `0`, empty findings | Exit `0`, empty findings |
| Remove `POST /requests/{ref}/actions/publish` | One Path Item removed, breaking | Exit `1`, error `api-path-removed-without-deprecation` | Exit `1`, same error |
| Change `PublishResult.properties.publicationId` to `{"type":"number"}` | One response property changed, breaking | Exit `1`, errors `response-property-type-changed` and `response-property-pattern-removed` | Exit `1`, same errors |
| Add `GET /pilot-compatibility` | One distinct path added under inherited bearer security, compatible with existing documented calls | Exit `0`, empty findings | Exit `0`, info `endpoint-added` |

The numeric specimen changes only the publication ID schema for Publish's 200 response. It retains the original string example and leaves the shared `DecimalString` schema and other publication ID projections intact. The single schema edit removes the referenced regex constraint along with the string type. The unchanged Spring payload contains the exact string `9223372036854775807`. The additive specimen describes a standalone 204 route in scratch. Shipping an added route would require its Spring implementation and parity checks. [Manual patch review](../../../../tmp/phase5-development/b46-pilot/evidence/manual-review.md) · [Automation results](../../../../tmp/phase5-development/b46-pilot/evidence/results.json) · [Repeat results](../../../../tmp/phase5-development/b46-pilot/repeat-check/results.json)

## Spring comparison and elapsed time

The prior independently approved B45 default and synthetic runs each passed 432 tests in 19 suites, including 77 OpenAPI contract cases. The integrated synthetic run had the same counts. B45's `seededRouteRemovalAndResponseDriftFailTheOriginalOracles` control removes the same Publish route and rejects the same numeric schema against a real MockMvc response. The B45 mapping check covers all 12 operations. B45 also exercises bearer challenges, field masks, omission versus null and precise IDs in projections. This pilot reused that proof. Compatibility diff results cover changes declared by the two documents, and Spring checks cover implementation behavior. [Exact B45 controls](../../../../bootstrap/src/test/java/com/company/requests/bootstrap/OpenApiContractTest.java#L511) · [Independent review](../B45/review-2315894.md) · [Integration](../B45/integration.md)

| Observed step | Elapsed time |
| --- | ---: |
| Four local Git patch generations | 0.822 s total |
| Executor patch and reference review | 17.788 s |
| Four pull-request comparisons | 0.690 s total |
| Four release comparisons | 0.563 s total |
| Standalone unchanged-source validation | 0.154 s |
| Previously recorded B45 OpenAPI Spring suite | 5.273 s default and 5.961 s synthetic |
| Previously recorded B45 `clean verify` | 50.003 s default and 58.359 s synthetic |

The command timings include local process startup. The manual window includes output display and reading one schema reference. The executor knew the seeded changes and recorded the classifications before seeing the compatibility findings. These timings describe this trial and the earlier B45 run. [Manual timing](../../../../tmp/phase5-development/b46-pilot/evidence/manual-review-time.json) · [Command timings](../../../../tmp/phase5-development/b46-pilot/evidence/results.json) · [B45 independent run times](../B45/review-2315894.md)

## Validator result and gate limits

On the exact B45 source, `validate --fail-on ERR` returned `1` with one error, `schema-pattern-regex-invalid`, at line 1244 column 9. The message was `` error parsing regexp: invalid escape sequence: `\u` `` and fingerprint `4ac884234f4e`. The raw result agrees with the coordinator's smoke test after accounting for the source path. The exact parsed `^[\u0001-\u007f]+$` pattern compiled under ECMAScript and met all six expectations, including the lower and upper boundaries and exclusion of NUL and non-ASCII input. Both unchanged comparisons returned `0`. This is a validator portability finding for review before requiring this tool to validate the specification. [Raw result](../../../../tmp/phase5-development/b46-pilot/evidence/cursor-validator.json) · [Coordinator result](../../../../tmp/phase5-development/b46-pilot/evidence/coordinator-cursor-validator.json) · [ECMAScript controls](../../../../tmp/phase5-development/b46-pilot/evidence/cursor-ecmascript.json)

The B45 source serves as the seeded baseline for the two local equivalents. An actual release comparison requires an approved released baseline. Compulsory CI adoption is a separate G6-M decision. The trial covers the four selected cases and the paired B45 behavior evidence. [T3-R decision](../../../Phase3-LLD/tooling.md) · [Acceptance](../../../Phase4-development-planning/tool-pilots.md#b46--t3-r-api-diff-pilot)

## Reproduction and integrity

Run from the workspace root with the qualified executable at `tmp/phase5-development/b46-tools/v1.39.0/oasdiff.exe`. Use a fresh directory name for each reproduction.

```powershell
node tmp/phase5-development/b46-pilot/run-pilot.cjs prepare local-recheck
node tmp/phase5-development/b46-pilot/run-pilot.cjs compare local-recheck
```

The runner uses absolute local paths, a pinned empty config and JSON format. The two comparisons use `--allow-external-refs=false --fetch=false --open=false --fail-on WARN`. The release changelog also uses `--level INFO`. Validation uses `--allow-external-refs=false --fail-on ERR`. The runner asserts exit statuses, rule IDs, severities, operation paths, the publication ID type transition and the cursor results. It preserves stdout, stderr, args, exit status and timing for every comparison. A second run reproduced the input hashes and findings, including fingerprints and source coordinates with source file paths omitted. [Runner](../../../../tmp/phase5-development/b46-pilot/run-pilot.cjs) · [Repeat confirmation](../../../../tmp/phase5-development/b46-pilot/repeat-confirmation.json)

The private scratch manifest covers 119 artifacts from the execution and repeat run. All entries matched when sealed. Manifest SHA-256 `fd72d2ff2574e2dd813963aaaf6b888fb3d49a6ee3bbb11055d2bfa493d1ede9`. The separately hashed input list covers the plan, tool, approval, qualification, coordinator smoke outputs and B45 Spring proof. [Scratch manifest](../../../../tmp/phase5-development/b46-pilot/SHA256SUMS) · [Pinned input hashes](../../../../tmp/phase5-development/b46-pilot/source-inputs.sha256)
