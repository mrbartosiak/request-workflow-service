# B07 execution evidence · Signed operation keys and exact intent digest

Executed on 2026-11-22 (Europe/Warsaw). Candidate `cadea474479bb885b45c9eb984870653ce682da1`, tree `7d32ee56eba06d8272b2844b87305a5e567dc6b4`, parent `b2ca94d2bff9bf3b11901b856787c7864de6f8d2`, branch `codex/b07-operation-keys`. `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. Sol xhigh (56) executed and Astra high (60) is assigned independent review. [Assignment](assignment.md) · [G0](../g0-kickoff.md) · [G0-B](../B01/g0-b-approval.md) · [B03 handoff](../B03/integration.md) · [B06 handoff](../B06/integration.md)

## Reproduction

Use PowerShell in `tmp/phase5-development/b07-operation-keys`. The ticket used an isolated copy of `b04-fixture/m2/qualified-repository`, shared public settings at both Maven settings levels, and process-local Temurin. Apache Maven 3.9.16 build `2bdd9fddda4b155ebf8000e807eb73fd829a51d5` and Temurin `25.0.5.1+2` ran locally. Maven reported Java `25.0.5.1` and the configured runtime path. The settings SHA-256 is `e6617e035baec17673be3f90b4d169ca75e308121e6308f7e358dc2d5b07ac08`. [Adopted toolchain](../B01/build-evidence.md#reproduction) · [Version log](../../../tmp/phase5-development/b07-tools/logs/maven-version.log)

```powershell
$phase5Tools = Split-Path (Get-Location)
$settings = Join-Path $phase5Tools 'b01-tools/m2/settings.xml'
$repository = Join-Path $phase5Tools 'b07-tools/repository'
$env:JAVA_HOME = Join-Path $phase5Tools 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:MAVEN_SKIP_RC = 'true'
$mvn = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$flags = @('-o', '-B', '-C', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$repository")
& $mvn @flags -pl application -am test '-Dtest=OperationKeyIssuerTest#issueReturnsAKeyOnlyForAnAuthorizedStablePrincipal' '-Dsurefire.failIfNoSpecifiedTests=false'
& $mvn @flags -pl adapter-http -am verify '-Dtest=OperationKeyIssuerTest,IntentDigestTest,HttpIntentDigestTest' '-Dsurefire.failIfNoSpecifiedTests=false'
& $mvn @flags clean verify
```

## Red and green

| Check | Observation |
| --- | --- |
| Initial assertion on the pinned base with the new test and minimal scaffolding | `CurrentAccess.requireKeyIssue` succeeded for the authorized synthetic principal. The first implementation returned no `IssuedKey`. The test failed with “authorized issue must return a signed operation key,” exit 1, one failure and zero errors. The final test also checks proof, grant, verified scope and random receipt identity. [Red log](../../../tmp/phase5-development/b07-tools/logs/red-operation-key.log) |
| Focused verify | 11 operation-key tests, six exact digest tests and two adapter tests passed. The adapter tests use B03’s actual validator and `MockHttpServletRequest` to compare equivalent JSON member order and escapes and changed decoded input, reason and observation. [Focused log](../../../tmp/phase5-development/b07-tools/logs/focused-green.log) |
| Full offline `clean verify` | All 11 reactor projects and 311 tests passed. This includes the 19 B07 tests, 156 lifecycle tests, 126 B03 HTTP boundary tests, six B06 synthetic authentication tests and four bootstrap tests. Zero failures, errors or skips. The candidate checkout is clean and the scoped commit passed `git diff-tree --check HEAD^ HEAD`. [Full log](../../../tmp/phase5-development/b07-tools/logs/full-offline-verify.log) |

| Log | SHA-256 |
| --- | --- |
| `maven-version.log` | `bee02194f8f38a20890751e474bfce4f6cd9ccc769660623d379760933f2d1b2` |
| `red-operation-key.log` | `7f0c928b12a23550a1652005b6356924640d573d8df9781e27bb5fd896a0eaba` |
| `focused-green.log` | `8c1661c2978bb1c96f18e976672dc01dc92bc14f7363e30e01b099146d454c78` |
| `full-offline-verify.log` | `b88940e4a019f16bdfcd5f1f9fafe1ab84cb8f8382dde37dc02431c23757b33d` |

## Bounds and acceptance

- **Issuance and proof.** `OperationKeyIssuer.issue` uses `CurrentAccess.requireKeyIssue` with the stable actor. Tests prove denial and authority failure precede trusted-time reads. The key carries the directory issuer, organization, stable principal and certified generation. Grant and person attributes are separate from key identity.
- **Format and purpose.** The compact `opk1` format uses canonical, unpadded Base64url for a versioned payload and a complete HMAC-SHA-256 tag. It bounds input to 1,024 characters before parsing. Scope text is bounded to 224 exact UTF-8 bytes per field and maximum-length issued keys measure 1,023 ASCII characters. The random token ID is 16 bytes represented internally as 32 lowercase hexadecimal characters for `ReceiptKey`. Tests cover altered signatures, incorrect purpose and key material, equivalent noncanonical encodings, trailing bytes, negative shape and version cases, malformed UTF-8, invalid nanoseconds, altered lifetime and signed future generations. Authentication precedes interpretation of claims.
- **Expiry and admission.** Issuance uses trusted time with its original nanoseconds and sets the absolute expiry exactly 24 hours later. `now == expiresAt` and later expire uniformly for eligible-scope active and past-generation tokens. An active key exactly three seconds before cutoff may execute with the reference margin. One nanosecond inside that margin is lookup only until cutoff. A verified past generation stays lookup only regardless of the margin. A missing, failed or pre-issuance time reading closes the boundary. Key results and errors redact protected data.
- **Exact derivation.** `IntentDigest` frames the derivation version and each typed field with a one-byte field tag, a four-byte big-endian length and exact bytes. It uses separate HMAC-SHA-256 material and purpose from token signing. Create binds action, type, name and body. Edit and transitions bind their action, target, exact body or required reason and original observation token. Bounds match B03 for name, body, ref, reason and observation. Cases cover decoded-name normalization differences, text and token whitespace, CRLF versus LF, action and target changes, a split-field ambiguity control, digest key rotation and a fixed HMAC vector independently calculated with Python `hmac` and `struct`.
- **Key material.** `OperationSecrets` copies independent 32-byte key inputs into separate signing and intent version maps. Duplicate bytes, invalid versions and incorrect key lengths fail configuration. New output uses the active versions. Tests prove verification and comparison across retained versions. An unsupported receipt digest version or invalid stored digest length defers comparison safely.

B07 supplies application and edge validation evidence for E2 and E4. The live issuance and command routes, guarded receipt lookups, actual response status and headers, real PostgreSQL effects and P1/P2/P4/P8/P9 force-order tests belong to B08 and later tickets. B07 used no fixture processes, online artifacts or wire logging. Maven’s version command printed the same `Odmowa dostępu.` startup line recorded in B01 and returned 0. [B07 criteria](../../../Phase4-development-planning/first-slice.md#b07--signed-operation-key-issuance-and-exact-intent-digest) · [REST](../../../Phase3-LLD/rest.md) · [Retry contract](../../../Phase3-LLD/retries.md) · [Functional cases](../../../Phase3-LLD/verification.md) · [PostgreSQL cases](../../../Phase3-LLD/postgres-evidence.md)

## Successor handoff

| Caller | Contract |
| --- | --- |
| B08 issue | Configure `OperationSecrets(signingVersion, signingMap, digestVersion, digestMap)` with independent material, provide a trusted UTC `TrustedTime`, and use `OperationKeyIssuer.REFERENCE_ADMISSION_MARGIN`. `issue(actor)` returns `IssuedKey.key()` and `expiresAt()` for the authorized response. |
| B08 preflight | Establish submitted-intent access and current authority first, such as `CurrentAccess.requireCreate(actor, type)`. Then call `verify(rawKey, actor, admission)` ahead of any receipt lookup or arbitration. `VerifiedKey.receiptKey()`, `generation()`, `issuedAt()` and `expiresAt()` carry authenticated claims. Only `ACTIVE_CAN_EXECUTE` admits a new effect. `ACTIVE_LOOKUP_ONLY` and `PAST_GENERATION_LOOKUP_ONLY` require authoritative lookup paths without a new claim or effect. `OperationKeyException.Reason.INVALID` is a safe invalid-key outcome. `EXPIRED` maps to uniform `key_expired` after submitted access. Trusted-time failure is `AuthorityUnavailableException`. |
| B08 guard and B12 release | Apply fresh secured submitted access and `recheck(verifiedKey, actor, freshAdmission)` before a protected guard decision. For B06’s release callback use `isFresh(verifiedKey)` beside the separately refreshed access proof. It returns false at expiry and propagates unavailable time. `VerifiedKey.eligibility()` is the initial snapshot. Recheck at later boundaries. Use the same issuer instance for a verified handle or verify the signed token again. B12 owns past-generation receipt trust and uniform fencing, and retains supported signing and digest versions through the documented lifetimes. |
| B08, B14 and B33 exact compare | Build `IntentDigest.Create`, `.Edit` or `.Transition` from B03’s exact decoded strings and typed action, target or Create type. Pass original `expectedRevision` text directly. `calculate(intent)` writes an active-version `ReceiptIntentDigest`. `matches(storedDigest, intent)` uses the stored version and fails closed if comparison is unavailable. For B33, vary the original target, action or fields while holding the signed key fixed to exercise original-intent isolation. |

Secret custody and production clock proof, configuration and enablement follow the applicable later gates. [Key custody gate](../../../Phase4-development-planning/deferrals-design.md) · [B12 release](../../../Phase4-development-planning/scope-proof.md) · [B14 workflow](../../../Phase4-development-planning/workflow.md) · [B33 contention](../../../Phase4-development-planning/db-verification.md)
