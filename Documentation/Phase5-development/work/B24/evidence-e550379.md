# B24-R1 corrective candidate evidence

Candidate `e550379a754a686b85903d233c9d7d2061bd5d7a`, tree `1e3791a5a3f0274e2ce4dbe2512e406927820d82`, has sole parent B45 `231589477a114b00fc127855e0598a0d56dc6727`. Both Git dates are 22 November 2026 at 22:48 Warsaw time. The commit contains the 14 approved worker and private fixture paths. All 14 native-frozen source digests reverified after the parent advance and commit. [Review finding](review-0f40eb4.md) · [Frozen digests](v2-port-free-source.sha256) · [Successor contracts](handoff-e550379.md)

## B24-R1 correction

Under the exact remote effect-key lock, the synthetic endpoint persists the current terminal non-application observation, terminal attempt ordinals and fence cutoff. Under the local outbox lock and current authority, the worker queries the same effect's durable arm history. It requires the current exact terminal fact and coverage of every armed predecessor before choosing terminal closure. Never-armed ordinals do not count as possible sends. A missing remote row supplies no terminal fact for a genuinely armed attempt. Current writer, exact original publication, endpoint and scope checks retain their existing boundaries. [Proof and control design](v2-port-free-checkpoint.md)

The two port-free controls each failed the intended assertion. The dense-ordinal rule falsely held the never-armed gap. Ignoring older durable arm history falsely accepted an armed gap. The initial original-package attempts timed out during unchanged authority provisioning before either could reach a native regression assertion. Their scoped logs and sealed original package are preserved as setup evidence. [Semantic controls](v2-port-free-checkpoint.md#port-free-verification) · [Original native setup evidence](v2-native-qualification.md#original-package-comparison)

## Native result on B26

The corrected fixture on B26 `6ffc8cbbfa43b08087aa6205f45908cf6305ab6f` exited 0 with 24 PASS markers on qualified PostgreSQL 18.7, pgJDBC 42.7.14 and Java 25.0.5.1+2. The never-armed throttle became `RETRYABLE`, and the same key's next attempt confirmed exactly one effect. The never-armed permanent rejection became `FAILED_PERMANENT` with zero effects. The genuinely armed predecessor remained uncertain until its old invocation actually arrived and reconciliation confirmed exactly one effect. The prior E6 matrix and B23 recorder compatibility passed. All seven postmasters across the corrected run and two original setup runs exited. Ports 55480–55482 were returned after checking both PID files and listeners. [Native qualification and durable counts](v2-native-qualification.md) · [Native green transcript](../../../../tmp/phase5-development/b24-v2-fixture/logs/b24-v2-native-green-01-run.log)

## Exact build on B45

The clean archive verified all 684 Git blobs and reproduced the checkout bytes used by the candidate, including Windows line endings on 661 files. B45 adds five specification, documentation and test paths relative to B26. The exact candidate was rebuilt from that archive with the qualified toolchain and strict offline Maven settings. [Source equivalence](../../../../tmp/phase5-development/b24-exact-e550379/source-equivalence.log)

Maven 3.9.16 and Temurin 25.0.5.1+2 ran `-o -B -C -ntp clean verify` in default and `synthetic-http` modes. Each passed 436 tests in 20 suites with zero failures, errors or skips. Isolation scans checked 10 default project JARs and 30 synthetic profile runtime JARs. Both private launchers compiled. All 30 runtime JARs, the private Spring test JAR and all 45 private launcher classes matched the qualified native run on B26 byte-for-byte. The B45-based verification remained port-free. [Default build](../../../../tmp/phase5-development/b24-exact-e550379/build/logs/default-verify.log) · [Synthetic profile build](../../../../tmp/phase5-development/b24-exact-e550379/build/logs/profile-verify.log) · [Native package equivalence](../../../../tmp/phase5-development/b24-exact-e550379/package-equivalence.log)

All 44 evidence entries reverified, including the original-package setup failures, both semantic controls, native qualification, exact source and package evidence, and the corrective B25/B28 handoff. Paths in the manifest are relative to the phase workspace root. [Evidence seal](../../../../tmp/phase5-development/b24-exact-e550379/evidence-e550379.sha256)

| Artifact | SHA-256 |
| --- | --- |
| [Candidate source archive](../../../../tmp/phase5-development/b24-exact-e550379/candidate-e550379.tar) | `3cb2f491cea1d4f9dbe80a4dbe66a5da79c894ee4c72e49251c837cf3e1ae4c2` |
| [Default clean verify](../../../../tmp/phase5-development/b24-exact-e550379/build/logs/default-verify.log) | `be25ac23cf3d6acbced1bd95b2ffb1202f6ce5590d87cfbcf04aff48fafa1138` |
| [Synthetic profile clean verify](../../../../tmp/phase5-development/b24-exact-e550379/build/logs/profile-verify.log) | `be1e433e54ff476e7c9c6af60a70433259043af9dc75748e6361d1e8db7ea496` |
| [Native-equivalent package inventory](../../../../tmp/phase5-development/b24-exact-e550379/package-sha256.csv) | `5ce3e7c620e1e15a02eaa18e5c9e4d88d158a895d4b6b2081a9f7d97ade8f33d` |
| [Corrective handoff](handoff-e550379.md) | `21325ff241eec8b4bca1d395d029b95dd3aba203b33637c1b3c06eba9b4a6d13` |
| [Evidence manifest](../../../../tmp/phase5-development/b24-exact-e550379/evidence-e550379.sha256) | `abd363261f1403b2be26d131a8c996d5a8ee82aa58a6336542665cd5fc5e24c9` |
