# B24-R1 corrective port-free checkpoint

The isolated branch `codex/b24-synthetic-delivery-v2` remains uncommitted on reviewed B26 `6ffc8cbbfa43b08087aa6205f45908cf6305ab6f`. It changes the same 14 assigned paths as `0f40eb46255d59f6c7a56ef73462d6a2c758d704`, with corrective changes in seven files. The original branch and evidence remain intact. The frozen source manifest SHA-256 is `b4667d016f8e6104828079c12c6500458e568c6ffef1ff9729ebfce6a11f9fb6`. [Independent finding](review-0f40eb4.md) · [Frozen source files](v2-port-free-source.sha256) · [Original evidence](evidence-0f40eb4.md)

## Proof correction

The locked synthetic endpoint persists the current exact terminal no-apply observation, its verified fence cutoff and the remote attempt ordinals with explicit terminal non-application. With the local outbox row locked and current dispatch or recovery authority established, the worker independently reads the exact effect's durable arm history. It permits terminal closure only when the current armed attempt has its own terminal evidence and every armed predecessor is covered by explicit terminal evidence or the verified fence. Missing remote rows cannot close an armed attempt. The existing stable single-effect and fence-with-authoritative-absence rules still apply. Local finalization retains the exact remote observation reference so the scope and proof can be audited.

The private fixture stages ordinary Publish effects and exercises three paths.

- On an `UNSAFE` route, attempt 1 expires before arm. A qualified throttle at attempt 2 offers a deferred retry and attempt 3 confirms one effect.
- Another `UNSAFE` effect with the same never-armed gap receives an exact permanent rejection at attempt 2 and remains terminal with zero effects.
- An original attempt reaches durable arm before a lost reply. A valid stable-key repeat allows attempt 2 to proceed. The second result proves its own transient no-application while the fixture withholds the stable-key guarantee from that report. The worker holds `UNKNOWN` with no repeat. The original old invocation then arrives, and exact reconciliation confirms that original effect.

Both gap types compare the durable local arm ordinals with a remote observation that certifies only ordinal 2. The remaining B24 and B23 compatibility controls stay in the fixture.

## Port-free verification

Two isolated semantic controls used the same focused policy test. The dense-ordinal control failed at the never-armed gap with expected `true`, observed `false`. The control that discarded older durable arm history failed at the genuinely armed gap with expected `false`, observed `true`. Both perturbations remained in scratch. The corrected default and synthetic HTTP builds passed 359 tests in 19 suites, including all four B24 policy tests. Scans checked 10 default packages and 30 profile packages and confirmed private fixture isolation. Both private B24 and B23 launchers compiled. All 14 source digests reverified after the build.

| Artifact | SHA-256 |
| --- | --- |
| [Dense-ordinal control](../../../../tmp/phase5-development/b24-v2-controls-5606c7af/dense-red.log) | `6203216d9e192d0d2a27f7ad1ca6e61042d6dad0d0d5ec85ef901c872a2a37e5` |
| [Ignored-arm control](../../../../tmp/phase5-development/b24-v2-controls-5606c7af/ignore-armed-red.log) | `3918462e80cf5f89918775584be50a5b5777042825eb5604f735e6544c5b8956` |
| [Default clean verify](../../../../tmp/phase5-development/b24-v2-fixture/logs/default-verify.log) | `243c29c60251e21e449b9c0f7011f3ec296c6c3f563f176f05528fceef26da35` |
| [Synthetic HTTP clean verify](../../../../tmp/phase5-development/b24-v2-fixture/logs/profile-verify.log) | `0c9ff98efb5311a26007a3e80bc54755adb7e2afab81722a8b313af28a2bd4f4` |

## Next authorized stage

After the coordinator refreshes the exact parent and assigns the loopback lease, run the strict default and synthetic HTTP builds and the native fixture using a fresh run ID. The build uses Maven 3.9.16, Java 25.0.5.1+2 and strict offline `-o -B -C -ntp`. Native E6 uses PostgreSQL 18.7 and pgJDBC 42.7.14 on 55480–55482 and requires owned-process teardown. Obtain coordinator Git dates before creating a candidate.

```powershell
$workspace = (Resolve-Path '../../..').Path
$scratch = (Resolve-Path '../b24-v2-fixture').Path
& './fixtures/postgresql/b24/Run-B24.ps1' -Action Build -Workspace $workspace -Scratch $scratch -RunId b24-v2-current-prep
& './fixtures/postgresql/b24/Run-B24.ps1' -Action Run -Workspace $workspace -Scratch $scratch -RunId b24-v2-native
```
