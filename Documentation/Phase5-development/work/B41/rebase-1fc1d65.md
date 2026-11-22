# B41 exact candidate after B09 integration

The coordinator rebased the 14-path B41 patch from original `04c1c53670e40fe5bd0345c6681f17eaf7c0c050` onto integrated B09 `c2634d1d4741e0d90378609391a5de5d6640e34b`. The exact candidate is `1fc1d65f0372698f31f5f331771f2b8ec864fef2`, tree `c28bea218f28315b99bac2a0639ed67785667e52`, on `codex/b41-evidence-on-b09`. The frozen original branch and evidence remain addressable. [Original evidence](evidence.md) · [Integrated B09](../B09/integration.md)

The B09 and B41 path sets were disjoint. The coordinator compared the complete `git diff --binary` output before and after rebase and verified equality, original Sol authorship, a clean worktree and a passing whitespace check. Git required a committer identity, so the coordinator used the existing command-local identity. The rebase does not transfer independent approval to a new revision. Astra must review and verify the exact combined candidate. [Assignment](assignment.md)
