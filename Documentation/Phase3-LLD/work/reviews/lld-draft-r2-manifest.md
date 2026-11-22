# Conditional LLD r2 manifest

**Revision** PH3-LLD-DRAFT-r2 · 2026-11-22 Europe/Warsaw. **Executor** Executor 1 (GPT-6.2 Sol xhigh). **Status** Conditional and submitted to both dedicated reviewers. [Design](../../LLD-AI-clarity.md) · [Finding responses](lld-draft-r2-responses.md)

**Changes** Added `inbound-recovery.md, proof-resolution.md, proof-evidence.md, lld-draft-r2-responses.md`. Updated `access.md, authority-continuity.md, integration.md, LLD-AI-clarity.md, observations.md, operations.md, postgres-evidence.md, publication-journal.md, recovery.md, rest.md, restore-evidence.md, retries.md, sources.md, traceability.md, verification.md, wiring.md, witness-records.md`. This manifest is new. All paths below are workspace-relative. Its own digest is supplied with the handoff.

| Provenance | SHA-256 |
| --- | --- |
| [Prior signed-input manifest](lld-draft-r1-manifest.md) | `9466bca104d41f3ea97d49a3ad7af959aa4dd49922bfeb2cf60ff0e2db10be1c` |
| [Reviewer 1 findings](lld-draft-r1-reviewer-1.md) | `c9cd53cbb45d440f590e002adff8250cf3cc49682eab8ab92ef25997573c3f4b` |
| [Reviewer 2 findings](lld-draft-r1-reviewer-2.md) | `b5d609b3524d4382b6e108d2d6055c5071367e67ba3c511c47b7215c42b4ff24` |
| [Signed comparison](comparison-manifest.md) | `47b06edd10856679c21a2b13ab5cace4ce7490a3ffeff47aff2eb4b150b82881` |

The prior manifest pins the owner source baseline, signed candidates/comparison and readiness. Reverified those sources, the 30 exact payloads, design links, trace and review assertions. Runtime checks remain planned and performance unmeasured. D1–D6 and T1–T6 remain open and the R path remains a proposal. Phase 4 requires separate approval. [Decision status](../../decisions.md) · [Readiness](performance-readiness.md)

```text
60e8cb2608a670f77083c1fd0bc5c5e7d5b40ba34927b7ae5527c71c49269f76  Phase3-LLD/access.md
f63730d86f9f8246b727a5623bae28dd9a5e29fe3fad88e958fc77552d6cfe6e  Phase3-LLD/authority-continuity.md
cc1b781a07b3687e46257634049c5a016f3431e31c38cfc882b82badcc9013ea  Phase3-LLD/decisions.md
2888cebffef2e61cc7a04818cc7be02aa53ed217f273900ae0e2fd7e916a154a  Phase3-LLD/domain.md
d3b829aec5b41b3964b58039f35c3371eda29833d79a15bd87fd2e79646e8cae  Phase3-LLD/identifiers.md
8d6e18869c27d67eb5f8fef668c3837ac375eb0ba1da3aa0cc530870ec39da0f  Phase3-LLD/inbound-recovery.md
aa00fc0b4f6f7a0633a083a8a064523ced40b6d638c6ca972c51794ecd47365a  Phase3-LLD/integration.md
3230c05c1676f9bd4a77a291fa4c36e0090ba5d0bc495e642756a842e1a3b781  Phase3-LLD/LLD-AI-clarity.md
0d8c852231caff34ebbce2d4e00e73847ba8159635cf1059c41dfb7c6cd60458  Phase3-LLD/observations.md
ef3997573c45ad666396174da989af6e5f8e4920a50aaec6d934bba61910786f  Phase3-LLD/operations.md
6962407d007efede02ff6e7193acfccac5e8d8b070f0c98850676cb847a7b6b6  Phase3-LLD/performance.md
dc6482a967531cb3db2e1850c2acadc008e727434e46d159d3d6dece41199c75  Phase3-LLD/persistence.md
c43a97b91034697dfc3ae0560b138795f5258ff46bf11537a842fd6b059995a2  Phase3-LLD/postgres-evidence.md
4b6fe80a26d463ce4e0e43905f13ea8346c3c51d14b39f96960616f96a25ebb0  Phase3-LLD/proof-evidence.md
0895cf6604b5503e5f5f0dca6b9d94331ea16164b9f73684e0e152d71b7e8d15  Phase3-LLD/proof-resolution.md
766b97f44854f19efa81359d69cc0ad6eafb38cfd191a05ccfc2c46f9a014920  Phase3-LLD/publication-journal.md
d39314c04c0be55d0e60de38fe014045b168efc2a48caacea45902a127db153c  Phase3-LLD/queries.md
f6fd66b112912f0576424586ba7135d86b82c1735c77dc0feb5d99cfb0d21bb7  Phase3-LLD/recovery.md
f995f7c919c3ef1f586ca9532d1b7ae1b2eb588dd55cffb72c673836aab905d2  Phase3-LLD/rest.md
6328947f4cf201fa613cb58e6424750fc4bd980f6907deffc0d405c0116a2050  Phase3-LLD/restore-evidence.md
35faf89cb5e562283a1e009ea28204612c28f844bc9f056d724ce79ef895fe3f  Phase3-LLD/retention.md
2b280e69434ca8415df8a9cfe43e3d6a0fcd692dc18713d40189521ce53a0df8  Phase3-LLD/retries.md
29e0a7b5a5e01b371d5c81703c6cbae79ef2e3ec36489d5363edf7fb547856d7  Phase3-LLD/sources.md
d1feee1d831f29db58dcd8d56975828783b8975575136895e56b7c1e6adfc4dd  Phase3-LLD/tooling.md
7fc812cbfb0adc394fd20abf524ce401f8a4063aa0b8f6a378c7b248c851a158  Phase3-LLD/traceability.md
ecbd5ff5aafba41136347154c89694bd9409dd39ffa252c0f63580762edd5d1d  Phase3-LLD/vendors.md
76563988ed75c462da456fa59484802fd402425946d55714f2d5312061962afa  Phase3-LLD/verification.md
534cfd0bc3eede369fa0f13b5e4564cfc5df86dc882196b1438d6832f9d477be  Phase3-LLD/wiring.md
a4521270d14d305c8c4e9926fe0b23ff631bf53b85341132bcc3b8ac8762303b  Phase3-LLD/witness-records.md
f5cf037553d62fe0cea957f71b5cd14931faf4570a22ba9064eff846d64df03c  Phase3-LLD/work/reviews/lld-draft-r2-responses.md
```
