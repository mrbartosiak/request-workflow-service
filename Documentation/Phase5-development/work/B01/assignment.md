# B01 build qualification assignment

**Stage** Pre-G0-B qualification under [G0](../g0-kickoff.md). Plan `PH4-PLAN-r1` SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. Base `98c5802c2efc512d14dabf0a6ab59bd567571754`. Executor GPT-6.2 Sol high (55). Independent reviewer GPT-6.2 Astra medium (57).

**Sources** [B01](../../../Phase4-development-planning/foundation.md#b01--reproducible-spring-build), [preflight](../../../Phase4-development-planning/environment.md), [Java and Spring assembly](../../../Phase3-LLD/wiring.md), [execution](../../../Phase4-development-planning/execution.md), [action gates](../../../Phase4-development-planning/choices.md#later-authorizations).

**Ownership** Isolated `codex/b01-qualification` worktree in `tmp/phase5-development/b01-qualification`. Return the sanitized evidence in `Phase5-development/work/B01/qualification.md`, tied to the base commit and evidence SHA-256. Put temporary probe files under the isolated worktree or an authorized temporary directory. No fixture is assigned.

**Oracle** A Java 25/Spring Framework 7.0.11 MVC context smoke with the accepted project dependency graph. Inventory Java and build capabilities, exercise the available Java 25 compiler and runtime, and record the earliest unmet prerequisite separately from any application assertion. Run a Spring smoke only with already available and authorized tooling and artifacts. Compare compatible Maven and Gradle wrappers and a managed runner using public technical sources. Record exact proposed versions, dated Spring Security and Snyk evidence and applicability, and which claims require actual dependency resolution. Identify exact owner actions to allow further qualification. If authoritative version or advisory data cannot be retrieved, preserve that gap for review.

**Handoff** Report exact commands, tool versions, source links, observations and scope remaining at G0-B. Include the planned module graph and B02–B04 successor handoff. Adoption, downloads, installation, global configuration, fixture access and work on successors await their applicable receipts.
