# Corrected B32 candidate on B41

The coordinator rebased both B32 fixture commits onto `5c7934e572545af263e412e28e043ad981d1112b` without changing their complete binary diff from the integrated B09 base. Exact candidate `5455ee8ab3f9a2ddb8addd71f443d3139942a73d`, tree `921798d8bf0b67aad0094bb0572ad911bddef50d`, first B32 commit `de84a82b3202ff360c2181d1c87deb7dbe3c8fda`, on `codex/b32-harness-on-b41`. The coordinator verified disjoint B32 and B41 path sets, original Sol authorship, passing whitespace check and clean worktree. The earlier branches retain their exact revisions. [Initial review](review-955b529.md) · [Correction evidence](evidence-72602d7.md) · [B41 review](../B41/review.md)

The packaged listener now includes the approved B41 evidence filter. Independent reproduction will verify both B32 fixes against that exact interface and build. The protected-runtime changes remain B41's, while the B32 patch consists only of fixture files.
