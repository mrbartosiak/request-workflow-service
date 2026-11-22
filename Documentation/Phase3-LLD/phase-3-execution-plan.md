# Phase 3 LLD execution plan

**Status** The owner approved kickoff, staffing, scope and the initial deferrals on 2026-11-22. Use the recorded Phase 3 requirements where they refine the signed Phase 2 baseline. [Owner selections](owner-kickoff.md) · [Approval gates](owner-approvals.md)

## Team and assignments

Use **two executors and two dedicated reviewers**. Both executors independently design the same complete LLD from the same source baseline and brief. The coordinator manages scheduling and owner approvals. Use `xhigh` for the requested very-high reasoning, following the Phase 2 convention. If a requested setting is unavailable, bring the substitution to the owner before assigning the affected role. [Phase 2 staffing convention](../Phase1-conceptual/phase-2-hld-execution-plan.md#agents-and-assignments)

| Agent | Model and reasoning | Assignment |
| --- | --- | --- |
| Executor 1 | GPT-6.2 Sol, `xhigh` | Draft one complete candidate. Revise it on Reviewer 1's findings. After both candidate signoffs, prepare the owner comparison, assemble the LLD and apply approved decisions. |
| Executor 2 | GPT-6.2 Sol, `xhigh` | Draft a second complete candidate from the shared brief. Revise it and any requested follow-up analysis on Reviewer 2's findings. |
| Reviewer 1 | GPT-6.2 Astra, `xhigh` | Review every Executor 1 output, including the candidate, owner comparison, final synthesis and revisions. Assign fixes directly to Executor 1 and re-review them. |
| Reviewer 2 | GPT-6.2 Astra, `xhigh` | Review every Executor 2 output and assign fixes directly to Executor 2. Independently review package-wide consistency and adoption of approved decisions in the assembled LLD. Assign assembly fixes directly to Executor 1 for Reviewer 1 and Reviewer 2 to verify. |

## Execution sequence after approval

1. Record the approved HLD baseline and accepted deferrals. Launch the two executors in parallel with identical briefs and separate draft folders. Keep candidate work isolated until each receives its own signoff. [Executor brief](executor-brief.md)
2. Review the candidates independently. Stagger reviewer turns to fit four concurrent agents including the coordinator. Keep each reviewer paired with their executor through revision and preserve the pairing when resuming the reviewer. [Review and signoff](review-and-signoff.md)
3. After both candidate signoffs, have Executor 1 prepare a per-decision comparison. Have Reviewer 1 sign off before asking the owner to choose. Continue independent detail where approval is pending. [Owner approvals](owner-approvals.md)
4. Have Executor 1 assemble the LLD from the reviewed candidates and the recorded owner decisions. Reviewer 1 verifies the complete assembly. Reviewer 2 checks interfaces and guarantees across documents. Apply the same review loop to later changes. Present the signed-off LLD and any unresolved decisions to the owner for Phase 4 handoff. [Review and signoff](review-and-signoff.md#final-package)

## Deliverables and location

Keep all Phase 3 files under `Phase3-LLD/`. Use `work/candidate-1/` and `work/candidate-2/` for independent drafts and `work/reviews/` for manifests, signoffs and the reviewed comparison. Write the assembled AI edition at `Phase3-LLD/LLD-AI-clarity.md` with linked Markdown modules beside it. This execution plan covers the AI edition and its verification design. The broader delivery plan calls for a separate human-readable edition and later implementation. [Phase obligations](../Phase1-conceptual/assumptions.md#phase-obligations)

The index should link modules covering domain and workflow, REST and validation, browse and history, identity and approvals, persistence and concurrency, retries and restore fencing, integration delivery, Java/Spring module wiring, tooling evaluation, verification and operations, and decisions and traceability. A module should stand on its own when included with the index. Prefer fewer than 5,000 characters per document and record the reason for any exception in the index. [HLD index](../Phase2-HLD/HLD-AI-clarity.md) · [Owner assumptions](../output/Assumptions-initial-HumanMade.txt)
