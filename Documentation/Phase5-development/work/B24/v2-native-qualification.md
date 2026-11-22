# B24-R1 native qualification on B26

Frozen corrective source `b4667d016f8e6104828079c12c6500458e568c6ffef1ff9729ebfce6a11f9fb6` remains uncommitted in `codex/b24-synthetic-delivery-v2` on exact B26 `6ffc8cbbfa43b08087aa6205f45908cf6305ab6f`. Both strict offline builds on that base passed 359 tests and produced the packaged runtime and private launcher. This native stage qualifies that snapshot while the final integration parent is pending. [Frozen source](v2-port-free-source.sha256) · [Port-free build evidence](v2-port-free-checkpoint.md) · [Finding and original package](review-0f40eb4.md)

Run `b24-v2-native-green-01` exited 0 with 24 PASS markers. PostgreSQL 18.7, pgJDBC 42.7.14 and Java 25.0.5.1+2 matched the qualified local toolchain. All five PostgreSQL executable fingerprints matched the B04 registry. The native package inventory records 30 runtime JARs, the private Spring test JAR and 45 private launcher class files. [Native green](../../../../tmp/phase5-development/b24-v2-fixture/logs/b24-v2-native-green-01-run.log) · [Package inventory](../../../../tmp/phase5-development/b24-v2-fixture/logs/native-green-package-sha256.csv) · [Qualified binary checks](../../../../tmp/phase5-development/b24-v2-fixture/logs/native-qualified-binaries.log)

| Regression cut | Native result |
| --- | --- |
| Never-armed throttle | The first claim expired as `EXPIRED_UNSENT`. Local durable arm history contained only attempt 2. Its scoped remote terminal observation certified attempt 2 with no fence and zero effects. The worker recorded deferred `RETRYABLE`. Attempt 3 reused the same key and confirmed exactly one effect. |
| Never-armed permanent rejection | After the same pre-arm expiry, attempt 2 received its exact permanent no-apply proof. It stayed `FAILED_PERMANENT` at two local attempts and zero remote effects, with no further claim. |
| Genuinely armed predecessor | The old invocation reached durable arm and its initial lost reply left no remote application. The authorized same-key replacement proved its own no-application. With the replacement's stable-key guarantee withheld, the exact remote terminal set contained only attempt 2 and local armed history contained attempts 1 and 2. The worker held `UNKNOWN`. The old authorized invocation then arrived, and reconciliation confirmed exactly one effect. |

The native run also passed the original E6 controls for scoped destination and tenant identity, ambiguous sends, stale and incomplete evidence, safe arrival orders under dedupe and fencing, route and operator revocation, endpoint binding, async acceptance through PostgreSQL restart, and migrated-schema B23 recorder compatibility.

## Original package comparison

The original runtime's 31 JARs matched the sealed `0f40eb4` inventory. Both launch manifests used the same 45 compiled corrective oracle files. Twenty-nine original JARs also matched the current native build, including the authority, bootstrap and PostgreSQL driver JARs. The two changed integration modules held the corrective source. [Original package and oracle comparison](../../../../tmp/phase5-development/b24-v2-native-red/baseline-equivalence.log)

Both original-package attempts reached PostgreSQL setup and hit the unchanged authority setup statement timeout in `JdbcAuthorityProvisioning.certifyWriter` before the regression. They are setup failures. The dense-ordinal and ignored-older-arm port-free controls remain the two observed semantic reds. [Original setup 01](../../../../tmp/phase5-development/b24-v2-native-red/logs/b24-v2-native-red-01-run.log) · [Original setup 02](../../../../tmp/phase5-development/b24-v2-native-red/logs/b24-v2-native-red-02-run.log) · [Semantic controls](v2-port-free-checkpoint.md#port-free-verification)

## Teardown and evidence

At 13:39:15.8317992 UTC all seven owned PostgreSQL processes from the three invocations had stopped and exited. Both owned scratch areas had zero postmaster PID files. Ports 55480–55482 had zero listeners and were returned to the coordinator. All 20 native evidence entries and all 14 frozen source digests reverified. The frozen worktree has the same bytes as before native qualification and awaits the final parent and date. [Teardown](../../../../tmp/phase5-development/b24-v2-fixture/logs/v2-owned-teardown.log) · [Evidence seal](../../../../tmp/phase5-development/b24-v2-fixture/native-qualification.sha256)

| Artifact | SHA-256 |
| --- | --- |
| Corrected native transcript | `aa83e49c9bc309a15b078fc6ab20a8e1b5fd4b8388267564f08d314083a4070c` |
| Corrected native package inventory | `5ce3e7c620e1e15a02eaa18e5c9e4d88d158a895d4b6b2081a9f7d97ade8f33d` |
| Original setup 01 | `08ff32bd6cb3229d4f4249612e00874d458f5f9ddf7455d169eda1b55984d05d` |
| Original setup 02 | `58bc54b99631179e12cd033e84a110b6ddd5462d80d0e6d936365a74d737bc79` |
| Same-oracle class inventory | `8dcd52979f70a86fe457b74decdb1ef54ae24d2c9d131b040649bb5f6bbfb13b` |
| Teardown | `08152fc2b69dbac227772cca89b12b8174a8f08788447d866d47b040c5138c52` |
| Native evidence manifest | `06f096cbaca641ffb48c6a0208de32b6a59820e401ac26bb92c6f8e4581bb859` |
