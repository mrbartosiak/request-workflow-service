# B24 synthetic delivery and ambiguity evidence

Candidate `0f40eb46255d59f6c7a56ef73462d6a2c758d704` has tree `0bfd41686d11178db4929bd1ab370813f12702f0` and sole parent `6ffc8cbbfa43b08087aa6205f45908cf6305ab6f`. Both Git dates are 22 November 2026 at 22:47 Warsaw time. The candidate changes 14 assigned source and fixture paths. All 13 other port-free source files retained their frozen bytes. Only the private native oracle needed corrections to scheduling and pooled-connection lifetime. [Assignment](assignment.md) · [Port-free checkpoint](port-free-checkpoint.md) · [Successor handoff](handoff.md)

## Native E6

Run `b24-b26-native-05` exited 0 on PostgreSQL 18.7, pgJDBC 42.7.14 and Temurin 25.0.5.1+2. Five PostgreSQL executables matched the B04 qualification fingerprints. The existing harness verified distinct workflow and authority system identifiers and scoped roles. The remote ledger ran in its own database on the owned authority cluster, with independent connections and a dedicated role. Native transcript SHA-256 `7c0e36867f65c6f595be300423c724607bb9268ce3fc7871192d974537dd77ab`. [Native transcript](../../../../tmp/phase5-development/b24-fixture/logs/b24-b26-native-05-run.log) · [Qualified binaries](../../../../tmp/phase5-development/b24-exact-0f40eb4/qualified-binaries.log)

| Case | Durable observation |
| --- | --- |
| Exact scope | Equal effect ID and attempt 1 produced two independent local attempts and two distinct remote mappings across destinations. The same coordinates under another tenant produced a separate remote effect. Local confirmation matched the full remote proof tuple and applying attempt. |
| Reply loss and lookup quality | Applied reply loss left one local `UNKNOWN` attempt and one remote effect. Stale, partial, mismatched projection, destination and endpoint reports stayed quarantined. An exact current reconciliation confirmed the original. Before-apply reply loss stayed at one quarantined local attempt and zero remote effects after unfenced retry and suppression requests. |
| Transient and permanent | An exact terminal transient closed the attempt without effect, persisted a deferral and offered the same scoped ID at attempt 2. The second attempt produced one effect. Permanent rejection stayed terminal with one local attempt and zero remote effects. |
| Single effect across delayed arrivals | Both old-first and replacement-first arrivals returned one applied remote effect across two local attempts. The late or subsequent arrival persisted as deduped. The old worker could not finalize its expired claim. |
| Remote fence | An old application committed before the fence and reconciled to its original effect without another claim. In the opposite order, fenced authoritative absence allowed attempt 2, and the arriving old attempt persisted as fenced. Each order produced one effect. A separate fenced async job stayed suppressed with zero effects after the old completion was attempted. |
| Current authority | Pre-arm route revocation suppressed without any remote effect. Post-send route revocation held one effect as locally unknown. Revocation between operator admission and outcome withheld the disposition, with an audit row containing no observation reference. Current reauthorization let the operator confirm the original. |
| Endpoint and replacement | A freshly configured endpoint could neither claim nor reconcile the previous endpoint's retry. A revoked replacement stayed deferred and preserved the older applied effect for reconciliation. |
| Async restart and recorder | Acceptance remained pending with zero effects. A proof-cluster PostgreSQL restart and reconstructed B24 worker verified the persisted job. Its completion then produced exactly one effect and confirmation. The legacy B23 recorder still finalized with its original scoped receipt on the additive schema. |

The semantic red bypassed only the absence exclusion predicate and failed with expected `QUARANTINED`, observed `RETRYABLE`. Native setup run 01 hit the unchanged upstream 150 ms publication-provisioning timeout. Run 02 exposed an invalid assumed order between destination claims at the same schedule. Runs 03 and 04 exposed the inherited prewarmed source lifetime across proof restart. The final native fixture accepts either destination order and requires both exact claims. It closes the prewarmed Publish session and sources before restart, then reconstructs a B24 worker with direct observer and authority connections. [Semantic red](../../../../tmp/phase5-development/b24-fixture/logs/b24-repeat-red.log) · [Native setup and oracle transcripts](../../../../tmp/phase5-development/b24-fixture/logs/)

At 12:54:39 UTC all 13 postmasters from the five owned runs had exited. All owned run directories were free of postmaster PID files, and two listener checks returned zero on 55480–55482. The lease returned to the coordinator. Fifteen console and native logs passed the credential-pattern scan. [Teardown](../../../../tmp/phase5-development/b24-fixture/logs/owned-teardown.log) · [Log audit](../../../../tmp/phase5-development/b24-exact-0f40eb4/log-audit.log)

## Package equivalence

The candidate source archive verified all 679 Git blobs. The clean extraction reproduced the native Windows checkout bytes on every path. The exact default and synthetic HTTP builds used Maven 3.9.16 strict offline `-o -B -C -ntp clean verify` and the qualified Java toolchain. [Source equivalence](../../../../tmp/phase5-development/b24-exact-0f40eb4/source-equivalence.log)

Each mode passed 358 tests in 19 suites with zero failures, errors or skips. Package isolation scans covered 10 default project JARs and 30 profile runtime JARs. The exact synthetic profile reproduced all 30 native runtime JARs, the private Spring test JAR and all 45 private launcher classes byte-for-byte. All 34 sealed evidence files reverified. [Default build](../../../../tmp/phase5-development/b24-exact-0f40eb4/build/logs/default-verify.log) · [Profile build](../../../../tmp/phase5-development/b24-exact-0f40eb4/build/logs/profile-verify.log) · [Package equivalence](../../../../tmp/phase5-development/b24-exact-0f40eb4/package-equivalence.log)

| Artifact | SHA-256 |
| --- | --- |
| [Candidate source archive](../../../../tmp/phase5-development/b24-exact-0f40eb4/candidate-0f40eb4.tar) | `550db394d2c33d2a1ae791754d9347342bb249e16be083ec8e5da581f7da9a46` |
| [Default build transcript](../../../../tmp/phase5-development/b24-exact-0f40eb4/build/logs/default-verify.log) | `13f4f1f9074437d773fe17a73375fd671de8bfd793a244c9213c77a4a5c5d952` |
| [Synthetic HTTP build transcript](../../../../tmp/phase5-development/b24-exact-0f40eb4/build/logs/profile-verify.log) | `fbea3ce772cb8d37af1830f135a36cf57b43fd69829a056b5120c2c67afa293a` |
| [Exact native package inventory](../../../../tmp/phase5-development/b24-exact-0f40eb4/package-sha256.csv) | `b0f878679b9dfd36131bc0e9313a58ccefbcce31c42bc04828c2602cbc6bf3f3` |
| [Owned teardown](../../../../tmp/phase5-development/b24-fixture/logs/owned-teardown.log) | `a87ac4716fc738f22dc54936d4333a3745efad1e1aff8ea76fa77828b62a3e92` |
| [Evidence manifest](../../../../tmp/phase5-development/b24-exact-0f40eb4/evidence-0f40eb4.sha256) | `7de027ddcae3e3fcca8e318159e19b16b83dbd2d1687e4a78a46f7b133e385ad` |
