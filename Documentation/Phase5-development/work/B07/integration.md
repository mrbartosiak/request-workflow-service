# B07 integration and key handoff

On 2026-11-22 (Europe/Warsaw), local `master` fast-forwarded from `b2ca94d2bff9bf3b11901b856787c7864de6f8d2` to independently accepted `cadea474479bb885b45c9eb984870653ce682da1`. Integrated offline `clean verify` passed all 311 tests in 11 reactor projects. Integration log SHA-256 `3c0091d818fe82c02bc554ae02142c87a3981afeb2ec9a6d07a086f057d12bd5`. [Independent review](review.md) · [Integrated log](../../../tmp/phase5-development/b07-tools/logs/b07-integrated-offline-verify.log)

B08 calls `OperationKeyIssuer.issue` with the B06 authenticated actor and verifies or rechecks the key after current submitted access has been proved. Verification distinguishes fresh effects from active or past-generation lookup-only cases. `isFresh` enforces the signed cutoff during the B06 guarded release, while `IntentDigest.matches` uses the exact stored digest version. [Claim, time and intent contracts](evidence.md)
