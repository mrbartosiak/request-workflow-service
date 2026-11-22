# Signed sources and assembly decisions

**Baseline** The owner accepted PH3-LLD-r2 and its seven deferrals for Phase 4 planning. The exact source manifest SHA-256 is `51de59f997e5902ed58e13ee50efdcc49be0f2afad6fac561b9fb99659a6de81`. Its 30 payloads and both independent Phase 4 candidates were checked before synthesis. [Phase 3 acceptance](../Phase3-LLD/phase-4-handoff-acceptance.md) · [Accepted design](../Phase3-LLD/LLD-AI-clarity.md)

| Signed candidate | Exact manifest SHA-256 | Exact reviewer record SHA-256 |
| --- | --- | --- |
| PH4-A-r2 · 49 tickets and 21 payloads | `f0ceb278cf818090aa2ea81773fc397bcf3e1a7f0e7c3357ee7e7a8597a718bd` [Manifest](work/candidate-a/manifest.sha256) | `19be1c8e3cff04bae628b1cbb3048c268548ee71efcb985fe16a90df8f8545ae` [Reviewer A](work/reviews/a/candidate-a-r2-signoff.md) |
| PH4-B-r1 · 56 tickets and 28 payloads | `31935aadd4f217666295d7f50356fb845ce3fb7c6588d836af85db58ae897424` [Manifest](work/candidate-b/manifest.sha256) | `aac95dffe46636d6de103b6499fb797d983af63daca01115f61ddc00f13056ef` [Reviewer B](work/reviews/b/candidate-b-signoff.md) |

| Question | Candidate trade-off and canonical treatment |
| --- | --- |
| First increment and granularity | Both use real PostgreSQL, independent synthetic scope, guarded release and durable Create/replay early. A includes a minimal detail read in that increment and groups more evidence into each milestone. B starts with a smaller Create/replay increment and separates API reads and proof cuts into reviewable cards. Use B's stable B01–B56 graph. B19 owns current masked detail including revision. [A thin slice](work/candidate-a/backlog-local.md) · [Canonical milestones](milestones.md) |
| Qualification and security | A identifies proposed pin checks before provision, resolved checks after provision, and a hosted disposable fixture. B separates qualification and adoption. Combine these in C2, G0-B and G1-Q/G1-U, with proposed and actual advisory records and the verified Java 25 path. [A foundation](work/candidate-a/backlog-foundation.md) · [Canonical foundation](foundation.md) |
| Scope-changing normal commands | A makes the ordered scope delta, atomic witness and verified confirmation explicit for affected Edit/Verify/Accept. B already separates the journal, resolver and guarded handoff. State the effect and withheld-proof oracle directly in B14. [A actions](work/candidate-a/backlog-actions.md) · [Canonical commands](workflow.md) |
| Interview and production claims | A explicitly refreshes interview coverage and advisory evidence and requires a complete exact-target engine/proof matrix including representative upgrade. B isolates private packaging, formal authorization and production promotion. Carry A's explicit evidence checks into B48/B51/B56 and preserve each separate approval. [A interview](work/candidate-a/backlog-delivery.md) · [A target matrix](work/candidate-a/backlog-production.md) · [Canonical delivery](delivery.md) · [Canonical production release](production-release.md) |

The candidate signoffs apply to those exact inputs. The canonical package has its own digest and independent review. Owner disposition on PH4-PLAN-r1 is a separate decision. [Plan manifest](phase4-plan-r1.sha256) · [Owner slate](choices.md)
