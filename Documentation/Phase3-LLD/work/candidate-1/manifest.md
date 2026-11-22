# Candidate 1 review manifest

**Revision** PH3-C1-r2 · 2026-11-22 Europe/Warsaw. **Executor** Executor 1, GPT-6.2 Sol xhigh. **Dedicated reviewer** Reviewer 1, GPT-6.2 Astra xhigh. **Disposition** Submitted for re-review. [Candidate index](index.md) · [Finding responses](revision-notes.md) · [Review process](../../review-and-signoff.md)

**Supersedes** PH3-C1-r1 manifest `94295bbdcd43c92ba0b57a12e769439727ddefe3e45309fc96c3d83ecba0d18d`. Hashes below cover all 22 payload files, relative to this manifest. The manifest's digest is reported in the review handoff.

- Added `retention.md, revision-notes.md`.
- Modified `access.md, decisions.md, identifiers.md, index.md, integration.md, operations.md, persistence.md, postgres-evidence.md, queries.md, recovery.md, retries.md, sources.md, tooling.md, traceability.md, vendors.md, verification.md, wiring.md`, and this manifest.
- Identical payloads `domain.md, performance.md, rest.md`.

**Baseline** Signed HLD-owner-r3 and 37 pinned shared sources. Review-source list SHA-256 `6844b41abdb9249ffbe29a80de35936fac821b53fcc1c819b7e8dc2100cd70f2`. [HLD list](../../../tmp/phase2-hld-execution/hld-owner-r3.sha256) · [Pinned source list](../reviews/source-baseline.sha256) · [Shared brief](../../executor-brief.md) · [Owner kickoff](../../owner-kickoff.md) · [Owner identifiers](../../owner-identifier-requirements.md)

**Verification** Recomputed the signed HLD and pinned review-source digests. Verified candidate hashes, local paths and anchors, index coverage, lengths and the six finding contracts against their planned evidence. Runtime checks, including compilation, PostgreSQL, performance, connector and restore, remain pending. [Source checks](sources.md) · [Performance](performance.md) · [PostgreSQL cases](postgres-evidence.md)

**Outstanding decisions** Production engine, exact persistence, grants, wire and retention choices, independent allocator/mapping authority, live vendor scope and worker budgets, tooling and operating gates/topology await the reviewed comparison and owner decision. Binding constraints and delegated values follow the owner record. [Decision proposals](decisions.md) · [Traceability](traceability.md)

```text
617a98762feb781f4fe08ff18d2f35f3250b7409ecaa378d3b6c20e863548b4d  access.md
a642bf35eb9406826af566ccfa525f5b1c5b641dafc318226b1f475b0675336f  decisions.md
b8f140d811e791e29e79038611f2d2860df73dbd93df66425091b58ce9962b3a  domain.md
1940ea66ab23b750e5260f9bddc466fd141186d6fcbbd039742656cbeadebfd8  identifiers.md
a1441d13f9ff8e76cb3c1b1d372dabfe7f414a5067ebb1059c9c20ca3b9f9cad  index.md
a035e70eb41198dc2af4768b32275fc01371a77b02a3371dd89e5a4e2dbfbb2a  integration.md
29b300adb78745b3c1d72672ee52a09837e1b513b48092e8bcf82696a1e9d83d  operations.md
3f45da6306dd2565c5455873c28180fb5309cffa7aebcd81048f875253220aca  performance.md
824e55e1339e259e5019ff16819bb6de2e6203a5ed505ce88377387302d9c5d2  persistence.md
6169835a4b6b150b121dd3146ae4e48aa2b683b4ef27cb4a1273de3bdb2fa2dd  postgres-evidence.md
8965ab8d0a040508a8895033319a20a10b4c377be3aed6036e29fa6f1aa83e3d  queries.md
595b4ef39b32e67c8986c6f6587b1c2f727facf7f69b1bdf84107559596ce55a  recovery.md
8024a07cfc5f7d07b3ae40869a5f3d247d8004cd6df003ee1a4afaab3751ea41  rest.md
f167afbe7af7d31745abb336b081ccf38c539b1892dd51f8ba838493733d74cf  retention.md
ec9c92ddd36dbd96539af0460868c937a6d70ef6874b992a4d77b9a75bd7fc55  retries.md
4b64805ab3d4d8f0c6f604012fda72c7a80c3136ea44034683ee54fd4ab81d28  revision-notes.md
35cf0779fd9f844e6ceec5ee195ce3d4d50bc59a8063799d0bb3403ecf18726f  sources.md
0e0c60f3da1d7ac17f9679b9d6f89c268cf515223158dccd83eb73c04d85fa9f  tooling.md
808f5c7c59a49ba017a8c2247b9f5a1a0b3f0a8ee70ce646505954acd3d72c9f  traceability.md
24317dcf5a2aace7e9114d57c40b097ca2acb198d35e918907c177f202ecf0dc  vendors.md
2ff5b6e6c0b1eca80a925b3873e5dd3a67d88d8a3eb226ad68c147ff5099c9db  verification.md
94c02c5d40e0155ef873c3ebdfa787fe95103970a3208a813c0c5acda413d0c2  wiring.md
```
