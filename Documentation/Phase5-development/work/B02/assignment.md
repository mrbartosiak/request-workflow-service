# B02 execution assignment · Shared lifecycle and invariants

**Base** `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a` on `codex/b02-lifecycle`. `PH4-PLAN-r1` SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. GPT-6.2 Sol medium (53), independent GPT-6.2 Astra medium (57). [G0](../g0-kickoff.md) · [B01 integration](../B01/integration.md)

**Ownership** `domain/` only, including module-local test dependency on adopted JUnit. Use a separate copy of the approved Maven cache and local unit tests. The shared root POM, bootstrap, application and adapters are reserved for their own tickets. Public contract must support the accepted external coordination without persistence or Spring in the domain.

**Oracle** Exercise every canonical state/action pair for each initial type, all four terminal paths, permitted edit and exact name/body behavior, nonblank reason, terminal denial, revision versus history sequence, and the pure policy decision boundary. Pin a meaningful red on the base, run module and full reactor checks, show a negative control, provide the complete matrix and confirm the pure domain dependency graph. Record exact commands and pin each test to its source effect. [B02](../../../Phase4-development-planning/foundation.md#b02--shared-state-machine-and-invariants) · [Domain](../../../Phase3-LLD/domain.md) · [E1](../../../Phase3-LLD/verification.md) · [Wiring](../../../Phase3-LLD/wiring.md)

**Return** One exact local candidate commit plus `Phase5-development/work/B02/evidence.md` for independent review. Record the outgoing domain type and effect API for B08 and B13–B17, including remaining proof at persistence and HTTP boundaries.
