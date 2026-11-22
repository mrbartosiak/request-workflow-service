# Phase 4.5 development workflow

Use this runbook to coordinate Phase 5 against the owner-accepted `PH4-PLAN-r1`. The applicable ticket defines its predecessors, required evidence and stop criteria. The Phase 4 owner receipt accepts the plan for handoff. Phase 5 starts after a separate G0 kickoff, with B01–B08 proposed as the first wave. [Development plan](../Phase4-development-planning/development-plan-ai-clarity.md) · [Owner receipt](../Phase4-development-planning/work/owner/phase4-approval.md) · [Execution and TDD](../Phase4-development-planning/execution.md)

## Kickoff and workspace

1. Verify the SHA-256 of the Phase 4 manifest against the owner receipt, `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, then verify each of its 30 entries. Use the receipt to locate and verify the independent signoff and the plan index to identify the accepted LLD revision. Repeat the corresponding checks when a new plan revision is accepted. [Phase 4 manifest](../Phase4-development-planning/phase4-plan-r1.sha256)
2. Record G0 with the exact plan digest, coordinator, approved ticket IDs, qualification actions and exclusions. Record each later wave as an owner-approved G0 scope extension before dispatch. Include the intended Sol/Astra staffing and explicit delegation to fresh subagents in the kickoff assignment. Record the exact scope of each applicable action gate separately. [Later authorizations](../Phase4-development-planning/choices.md#later-authorizations)
3. Establish the integration baseline before dispatch. The repository had no `HEAD` at Phase 4.5. If that still applies, create a local seed commit within the authorized kickoff scope. Stage reviewed inputs by explicit path and include the accepted source files needed by the wave. Keep existing artifacts in place and keep scratch work, credentials and raw evidence outside the staged set. Record the resulting commit and plan digest together.
4. Keep one coordinator-owned ledger in `Phase5-development/work/ledger.md`. Use one row per authorized ticket or independently gated ticket stage with its state, direct prerequisites, gate receipt links, executor and reviewer settings, base and candidate commits, evidence and review paths, and integration result or blocker. Store the compact handoff and review under `Phase5-development/work/Bxx/`. The coordinator is the ledger's sole writer. Keep raw output in the authorized evidence location and link sanitized excerpts from the handoff.

## Chats and agents

| Session | When to start | What it owns |
| --- | --- | --- |
| `Phase 5 Coordinator Wave 1` | Start or designate one owner-facing project chat at G0. | The coordinator runs the ready queue, requests owner decisions, assigns file and fixture ownership, integrates approved candidates and reports milestone exits. Use GPT-6.2 Sol `high` for coordination. |
| `Bxx Execute` | Spawn a fresh executor subagent when the ticket or stage is ready. | It owns the assigned paths, red and green evidence, candidate commit and fixes. Reuse it for a short correction within the same scope. |
| `Bxx Review` | Spawn a fresh reviewer subagent when the exact candidate and evidence are ready. | It independently inspects the sources, changed code and oracle, records a verdict on the exact commit, and checks fixes. Keep the reviewer's checkout read-only and return code changes to the executor. |
| `Phase 5 Coordinator Wave N` | Continue in a fresh project chat when the coordinator's working context needs a reset or the next wave materially changes scope. | Resume from the ledger, verified plan revision, current integration commit and open decisions. |

Start worker agents with no inherited chat history (`fork_turns="none"`) and provide the brief below with an explicit model and effort. Give each worker absolute paths for accepted sources and shared evidence so that it can locate them from its checkout. Keep the workers inside the coordinator's chat by default. For an owner-visible coordinator chat, the owner can open it or explicitly request that Codex create it. Review and execution keep distinct sessions. A replacement worker starts from the latest brief, evidence and unresolved findings.

Start with at most two executors and one active reviewer alongside the coordinator. Prioritize a review that unlocks the next ticket or clears a shared integration surface. Give each executor a short `codex/bxx-…` branch and isolated worktree from the pinned integration commit when parallel writes are available. Assign one writer at a time to shared contracts, migrations, bootstrap wiring and shared fixtures. Give parallel runs separate fixture allocations or serialize those runs. If worktree setup is unavailable, serialize writes in a suitable checkout. [Phase 4 execution](../Phase4-development-planning/execution.md)

## Model assignments

Use the supplied Intelligence Index scores when assigning work. The reviewer must use **GPT-6.2 Astra** and score strictly higher than the **GPT-6.2 Sol** executor. Record both settings for each candidate, including fixes, and check the pair again when either setting changes.

| Ticket risk | Executor | Independent reviewer |
| --- | --- | --- |
| Small, bounded change with a deterministic oracle | Sol `medium` (53) | Astra `medium` (57) |
| Standard implementation or verification | Sol `high` (55) | Astra `medium` (57) |
| Transaction, concurrency, authority, disclosure, recovery or production qualification | Sol `xhigh` (56) | Astra `high` (60) |
| Escalated candidate with a hard cross-system failure or consequential unresolved finding | Sol `xhigh` (56) or `max` (57) | Astra `xhigh` (62) |

Keep the executor's setting when escalating review alone. If the executor reaches Sol `max`, assign Astra `high` or `xhigh` before the next review because Astra `medium` also scores 57. Reviewers report findings and Sol executors author the fixes. If a reviewer edits a candidate, isolate that edit and return the finding to a Sol executor for independent implementation. If a valid independent pairing is unavailable, keep that candidate pending and advance other ready work. Escalate the existing review as needed. Reserve a second independent review for a remaining cross-system risk that requires a separate check.

## Context budgets

Keep the kickoff brief under roughly 1,500 tokens. Budget about 15,000 tokens of task material per routine execution or review session and up to about 30,000 for a complex one. These are planning targets for briefs, loaded sources, diffs, tool output and discussion. Check them before a broad read and again at handoff. Use the ticket headings and named predecessor interfaces to open the relevant LLD sections, changed code, callers and tests. Start reviews from the candidate diff, its acceptance clauses and evidence. Fetch raw logs or larger source ranges as needed to resolve specific questions.

When the scope expands, the conversation approaches its budget, or a second fix round ends with the same blocker, capture a brief checkpoint and start a fresh session if more work is ready. Carry forward the active requirements, exact commits, evidence pointers and unresolved findings. Split an oversized assignment along interfaces or evidence cases and keep the original ticket's combined stop criteria. Give the reviewer enough context to check interactions across those parts. The coordinator reads the ledger, active handoffs and gate receipts and opens source details when resolving a dependency or decision.

## Ticket cycle

1. **Dispatch.** Read the ticket's `Needs` and `Stop`, check the current G0 wave and applicable stage receipts, and use the integrated predecessor commits and handoffs. Where the plan permits proposal or qualification work before an action gate, dispatch that authorized stage separately and leave later work pending. Reserve the assigned files, contracts and fixture.
2. **Execute.** Tie the oracle to an exact source clause. Record the intended failing assertion on the pinned base and distinguish environment failures. Apply the smallest change that passes, then exercise the normal entry point and rerun impacted predecessor cases. For a verification-only ticket whose behavior already passes, use the plan's negative control or fixture perturbation and keep the perturbation out of delivered code. Record exact commands, versions, applicable E/P IDs, relevant before/after observations and remaining gaps. For an evidence-only stage, pin the checked code commit and identify the exact report by digest. [Ticket evidence protocol](../Phase4-development-planning/execution.md#ticket-cycle)
3. **Review.** Check the exact candidate and base, source-to-oracle alignment, scope and authority, edge cases, and the strength of the evidence. Independently rerun the decisive checks where the authorized environment is available. For an inspection-only verdict, state what was inspected and which required evidence is still pending. Give findings a source clause, file or artifact, observed failure, requested correction and blocking status. Return corrections to the executor and verify the response on its new commit. Approve only when the ticket or authorized stage meets its stop criteria.
4. **Integrate.** The coordinator checks the verdict, model pair and current gates. Fast-forward the integration branch to the approved candidate when it descends from the current integration head. If the base advanced, compare the affected contracts, rebase the candidate, rerun the relevant predecessor and successor checks, and obtain review of the new exact commit before integration. Reuse unaffected evidence by reference. Confirm checks on the integrated revision before dispatching a successor that relies on it. Keep affected successors pending if a merged check fails and route the correction through the same review cycle.
5. **Close the stage.** Record the integrated commit, reproduction command, outgoing contract and successor IDs. A blocker entry names the unmet requirement, affected successors, responsible role and next action. Continue eligible independent tickets. Route changes to an accepted contract, scope or oracle through the Phase 6 procedure. [Change control](../Phase4-development-planning/changes.md)

### Dispatch and handoff briefs

**Coordinator kickoff.** “Coordinate the owner-authorized G0 wave of `PH4-PLAN-r1` using this runbook. Verify the plan, receipts and integration baseline. Dispatch each ready ticket or authorized stage to a fresh Sol executor and an independent Astra reviewer with the listed settings. Maintain the ledger and report decisions, milestone evidence and blockers with their affected tickets.”

**Executor assignment.** Include ticket and stage, exact ticket heading and source clauses, plan digest, base commit, G0 and action receipt links, predecessor commits and handoffs, owned paths and fixture, expected red oracle, stop criterion, and return path. Ask for candidate commit, before/after evidence with reproduction command, contract changes, successor IDs and blockers.

**Reviewer assignment.** Include the same ticket and authority references, executor model and effort, candidate and base commits, scoped diff, oracle and evidence paths. Ask for the reviewed candidate SHA, reviewer model and effort, checks run or inspected, findings, and `approved`, `changes required` or `blocked` for the exact ticket stage. A fix review adds the previous findings and the new candidate delta.

## First wave

The table assumes the owner approves the proposed B01–B08 wave. A predecessor must be reviewed and integrated before it can satisfy `Needs`. The coordinator opens later work only after its G0 scope extension. [B01–B04](../Phase4-development-planning/foundation.md) · [B05–B08](../Phase4-development-planning/first-slice.md)

| Ticket | Ready condition and handoff | Sol / Astra effort |
| --- | --- | --- |
| B01 | G0 permits bounded build qualification. Collect build/runtime evidence for G0-B. After applicable approval, pin the adopted build and hand the module graph and commands to B02–B04. | `high` / `medium` |
| B02 | B01 → lifecycle matrix and domain effect contract. | `medium` / `medium` |
| B03 | B01 → MVC validation, safe errors and mutation-entry evidence. Schedule alongside B02 or B04 when ownership is separate. | `high` / `medium` |
| B04 | B01 + G1-Q → qualify the disposable PostgreSQL fixture and independent proof store. Send exact pin, access and provisioning evidence for G1-U. | `xhigh` / `high` |
| B05 | B04 + G1-U → migrations, JDBC and rollback evidence. | `xhigh` / `high` |
| B06 | B03 + B05 + G1-U → synthetic identity, reservation and release guard evidence. | `xhigh` / `high` |
| B07 | B03 + B06 → signed operation-key and exact intent digest contract. | `xhigh` / `high` |
| B08 | B02 + B05 + B06 + B07 → real HTTP Create and exact replay after restart, with one durable effect and guarded disclosure. Review the combined PostgreSQL and authority evidence for M1. | `xhigh` / `xhigh` |

## Scheduling after the first wave

Schedule from each ticket's `Needs` and action gates. Use M0–M5 to report delivery exits. After a G0 extension, B09 can start once B05/B06 land if journal and adapter ownership are separate from B08. B11 follows B09, B10 follows B11, and B12 joins those results with B08. Once B08 lands, B32 and B41 can start under the extended scope as their respective inputs are ready. [Milestones](../Phase4-development-planning/milestones.md) · [Scope and release proof](../Phase4-development-planning/scope-proof.md) · [PostgreSQL verification](../Phase4-development-planning/db-verification.md) · [Operating verification](../Phase4-development-planning/operating-verification.md)

| Exit | Dispatch and review focus |
| --- | --- |
| M2 · Interview workflow | Start the command, Publish and read tickets as their individual predecessors land. Check the command and disclosure contracts together at integration. Use B32 and the required two-process evidence before the interview package. [B13–B15](../Phase4-development-planning/workflow.md) · [B16–B18](../Phase4-development-planning/publish.md) · [B19–B21](../Phase4-development-planning/reads.md) |
| M3 · Integration and restore | Schedule B22–B31 from the outbound, inbound and recovery dependencies. Share the reviewed proof and harness interfaces. Check the command transaction boundary, fake connector outcomes and verified recovery handoffs. [B22–B25](../Phase4-development-planning/outbound.md) · [B26–B28](../Phase4-development-planning/inbound.md) · [B29–B31](../Phase4-development-planning/restore.md) |
| M4 · Qualification | Run B32–B47 in independent tranches where the relevant features and harness are ready. Record evidence by environment and scope, and dispatch the tool pilots within G6-P. [B32–B36](../Phase4-development-planning/db-verification.md) · [B37–B40](../Phase4-development-planning/restore-verification.md) · [B41–B44](../Phase4-development-planning/operating-verification.md) · [B45–B47](../Phase4-development-planning/tool-pilots.md) |

At each milestone, the coordinator records the exact integrated revision, the required exit proofs with ticket verdicts, the integrated checks and any outstanding scope. Use an Astra review of the assembled result when an interaction or target claim has yet to be independently covered. Reuse applicable ticket signoffs. Keep incomplete exit criteria pending or blocked.

For M5, review B48's private walkthrough and exact source snapshot. Public publication requires G10. Run B49 only within its existing quick-smoke authorization after readiness passes. B50 can draft the formal profile at its documented entry point. Formal execution requires both G8 for the profile and G9 for the run. Treat B51–B56 as conditional on their recorded design, tooling, operating and target scopes. Obtain G11 on the exact reviewed release packet before promotion. [B48–B50](../Phase4-development-planning/delivery.md) · [B51–B54](../Phase4-development-planning/production.md) · [B55–B56](../Phase4-development-planning/production-release.md) · [Design gates](../Phase4-development-planning/deferrals-design.md) · [Operating gates](../Phase4-development-planning/deferrals-operating.md)
