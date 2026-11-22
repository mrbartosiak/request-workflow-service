# Adopted LLD assembly review manifest

**Revision** PH3-LLD-r1 · 2026-11-22 Europe/Warsaw. **Executor** Executor 1 (GPT-6.2 Sol xhigh). **Review** Exact adopted assembly submitted to both dedicated reviewers. The owner selected D1-R, D2-R, D3-R, D4-R, T1-R through T6-R and D6-R. Phase 4 acceptance remains separate. [Index](../../LLD-AI-clarity.md) · [Readiness](../../handoff-readiness.md)

**Changes** Added `handoff-readiness.md` and this manifest. Updated `access.md, authority-continuity.md, decisions.md, domain.md, identifiers.md, inbound-recovery.md, integration.md, LLD-AI-clarity.md, observations.md, operations.md, performance.md, persistence.md, postgres-evidence.md, proof-evidence.md, proof-resolution.md, publication-journal.md, queries.md, recovery.md, rest.md, retention.md, retries.md, sources.md, tooling.md, traceability.md, verification.md, wiring.md, witness-records.md`. The 30 payload paths and hashes below are relative to `Phase3-LLD/`. Historical review records stay with the baseline.

| Provenance | SHA-256 |
| --- | --- |
| [Owner selections](../../owner-design-selections.md) | `8387f6d8020ff71540929b974fe490409bffce852ae8e5be41de9ec170e1de0d` |
| [Exact signed technical baseline](lld-draft-r2-manifest.md) | `6aa36aebf19220c7515492bdd49c044204d7cef2956704bdcbdcbbee94cebf17` |
| [Baseline Reviewer 1 signoff](lld-draft-r2-reviewer-1-signoff.md) | `920b68b4c289847c57ae750bb47db87fab1278d8f58b0edf9f5c6fcf02e17fad` |
| [Baseline Reviewer 2 signoff](lld-draft-r2-reviewer-2-signoff.md) | `bbc912f7ab2962590d6e4f3a955e2befb22b788d34e5424c75f7b5184abf7599` |
| [Exact reviewed comparison](comparison-manifest.md) | `47b06edd10856679c21a2b13ab5cace4ce7490a3ffeff47aff2eb4b150b82881` |
| [Comparison signoff](owner-comparison-r2-signoff.md) | `c09b9348c2ad75a72bc2ce3738daaac9d78ac7d005caf8688761e197f24f65db` |

The technical baseline pins signed candidates, comparison inputs, shared sources and quick-check readiness. Source and design checks are static. PostgreSQL, connector, restore and performance evidence remain planned. No runnable service is available for the authorized smoke. [Decision limits](../../decisions.md) · [Evidence readiness](performance-readiness.md)

```text
813428a904248996376d7b0c79e12ac539369b46850c9dc556afbf66f9e0d51a  access.md
a14a9b3576cbcda301cb1d0c8da01b258872d98c6c3f24a51617332a25059008  authority-continuity.md
fbaa2ea0e269b86132d914a7e0be56cb1d7e127ecd4ad9cac03e41e5f4a3a945  decisions.md
11461dc981d15b53c7bf6ae0007661514fc6957b42f33c8dc3bea145a325a12d  domain.md
5b5222f4514b14c41373974fcb9f76230ebb64759a6456de6a25de94bfa5c37e  handoff-readiness.md
b38085762cc626bc90db25c6168b5fb954cc3fce0e15e0dd6037697db9e87e05  identifiers.md
808abd715294c536ec4f6b0218b282c7b0c6d4af8dec9b88d428f9101b328fba  inbound-recovery.md
2e258d3326e18bec5cf5b17608e5a3129f90b96bd7bd0ebadef1f26129b59003  integration.md
cc37abaed1b138f75c1643acdfd9263a6571c1f79bf4ddfc6f4bb965613ebda5  LLD-AI-clarity.md
c36cdfca0175d0ed63dd33bed081cd68bb3bebe7bc60f0ad97ab94e9b699ac0a  observations.md
687f7b26b6a34a915b9bddd0c17b24b6a29fdb97d6f69f4cfdbb4da16623e1a3  operations.md
ac516f5950726ae4aabfee59dd879e2cc5f3eb9a61ca6f39d319e3d67bd4f000  performance.md
38fc1a583ee6f6f9fd208f172bee2b9c47567784dbe3b33b6350e444e1bf52c1  persistence.md
2097f78f738d62d18eaa3b06684e42931b863473f5d25183eb9fd4aa365cc6d7  postgres-evidence.md
ef0affa7c1f32b4c4a7bfffc65cf836dae0e3d855d4b7c55bed0ff5f636e2922  proof-evidence.md
eb8b217db0ef617290de7f72eae8068c689d7dcf5b371206a8022cfce45b2f60  proof-resolution.md
edba69a3028fdf4bcd04437cd6536d5bb5517b162b8d86a92d902a19f1cbdbdd  publication-journal.md
14be5007b3e6f707ce2d1910068f5b2e764d44088b0542e49474478f07f2cbd8  queries.md
26e966dc94bf2b42f5c1aacc7044df9f67e000af30d0bbb1db060c25e74cf0b3  recovery.md
aa17b79c8439dae845a7b3dc594d05d69b5e33a419c6acf62f9ff9bd2dd09d37  rest.md
6328947f4cf201fa613cb58e6424750fc4bd980f6907deffc0d405c0116a2050  restore-evidence.md
d59a38b32bb5dff27a829b2a66166baf3f50208822f6d3e9e877f9a703b1363f  retention.md
f71caca1c92de97613db5ade0545b878c6a938a219f13d7c091b03ef01d115a6  retries.md
00d7e7f17f5efa9397128786302e962f2d09953607f02fdb9c3832ee9a22584c  sources.md
776223e53236110cd088fbdac480f3e4057502d11fdbc5c076b0eb9d01a8901b  tooling.md
9cbfbbc59cc3d88d3a831772429e476d347b70e466208feefb29a312dbeb203b  traceability.md
ecbd5ff5aafba41136347154c89694bd9409dd39ffa252c0f63580762edd5d1d  vendors.md
cca22a01f8b3aba0b29d1403979976da6c2584e7e935b54d278fa1da1a4cc506  verification.md
b224f66e8cbcbb09a3d98e3220a4a73307ebafcc4158682e175d361a23535481  wiring.md
54cb86ef496de953ff39b98fa458c60024758565903ca4109ae212dfc611516b  witness-records.md
```
