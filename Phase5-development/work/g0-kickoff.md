# G0 owner kickoff · Phase 5 wave 1

**Approved 2026-11-22 (Europe/Warsaw).** The project owner replied “Go decision given, please start” in the owner-facing Phase 5 coordinator chat after the handoff confirmed B01–B08 as the proposed first wave and G0 as the pending decision.

**Coordinator** This Phase 5 owner-facing chat (`01a20384-5640-7e60-8740-82d48391adf0`). The coordinator owns the integration branch, gate records and [ledger](ledger.md). The runbook calls for fresh GPT-6.2 Sol executors and independent GPT-6.2 Astra reviewers. Each reviewer must have a strictly higher Intelligence Index score than the corresponding executor.

**Verified inputs** `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, all 30 payloads, and independent signoff SHA-256 `c41aefee3cb54b15df02d5e2f38bf1308404f5d9def7e774bda2ab8835b58dab`. The governing `PH3-LLD-r2` manifest SHA-256 `51de59f997e5902ed58e13ee50efdcc49be0f2afad6fac561b9fb99659a6de81` and all 30 design payloads also matched. Runbook SHA-256 `a1dbebc262df3029e730ed22dc72f2b3848f10a85d5c492c30e9c1e63ec26c6e`.

**Authorized scope** Establish the local seed commit and coordinate B01–B08 subject to each ticket’s prerequisites and action gates. For B01, inventory installed Java and build capabilities, compare Maven/Gradle and managed-runner options using public documentation and artifact metadata, collect advisory findings, and prepare local qualification evidence against the verified design. Use existing tools and isolated scratch output. A dependency-based smoke can run only where the required tools and artifacts are already available under the current authorization.

**Open action gates** G0-B covers exact tool/dependency adoption and any proposed downloads, installation, provisioning or configuration. G1-Q covers disposable PostgreSQL qualification and fixture access. G1-U covers exact fixture adoption and use. Later waves require a G0 scope extension. Publication, production actions, and formal profile and run approval remain subject to their recorded gates. The existing quick-smoke authorization retains its original readiness conditions and bounds.

[Owner plan acceptance](../../Phase4-development-planning/work/owner/phase4-approval.md) · [Accepted manifest](../../Phase4-development-planning/phase4-plan-r1.sha256) · [Action gates](../../Phase4-development-planning/choices.md#later-authorizations) · [Runbook](../../Phase4.5-development-workflow/development-workflow.md)
