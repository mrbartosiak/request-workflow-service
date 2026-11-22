# Architecture and extension boundaries

**Scope** Component responsibilities and dependencies. **Status** Java 25, Spring Framework 7.0.11, REST and persistence are required. DEC-10 approves composition, single-release modules, bounded in-deployment delivery and extension conformance evidence. Exact layout and resource budgets remain open. [Assumptions](../Phase1-conceptual/assumptions.md) · [DEC-10](../Phase1-conceptual/decisions.md#dec-10-shared-core-and-extension-structure)

## Proposed component and trust boundaries

| Component | Responsibilities |
| --- | --- |
| REST boundary | Authenticate callers, validate shape and field contracts early, select named commands and queries, and map outcomes to authorized representations. |
| Command coordination | Resolve scope and operation identity, order competing effects through persistence, apply authorization and domain checks, and commit the accepted result once. |
| Shared lifecycle core | Own the initial states, legal transitions, input invariants, required effects, and edit eligibility. Model request, history, identifiers, authorization decisions, and delivery obligations as focused concepts. |
| Queries and policy | Enforce target and field visibility before browsing, direct reads, history, or replay disclosure. Evaluate approved identity, role, group, and approval policy. |
| Business profile | Supply approved type-specific input rules, access or approval policies and routing intent when invoked through the shared core's contracts. |
| Durable state boundary | Provide authoritative request ordering, unique IDs, operation outcomes, history, and atomic obligations across all service instances. |
| Integration coordination and adapters | Coordinate delivery and evidence independently of REST latency. Adapters contain vendor authorization, mappings, identifiers and capability differences. Route any authorized business mutation through the same command coordination. |

Multiple application instances share database authority. Delivery has its own resource limits in the same service deployment and runs off the REST path. Separate human, vendor and operator identities at trust boundaries. Vendor availability stays outside the synchronous interview lifecycle path. [Local consistency](local-consistency.md) · [Integration delivery](integration-delivery.md) · [Access and approvals](access-and-approvals.md)

## Design alternatives

| Axis | Option | Trade-off |
| --- | --- | --- |
| Extension mechanism | Composed policies and adapters invoked by a core-owned sequence | Explicit extension surface and isolated tests. Requires contract and wiring checks to guarantee enforcement in every composition. |
| Extension mechanism | Base interfaces with extensible implementations and sealed invariant steps | Common behavior can be centralized. Inheritance couples changes and needs controls against overrides bypassing authorization, atomic effects, or lifecycle rules. |
| Packaging | Core and extensions as modules within one service release (approved) | One coordinated compatibility surface and local consistency boundary. Use-case deployments share a release cadence. |
| Packaging | Core dependency of independently versioned use-case applications | Independent ownership and release cadence become possible. Requires conformance testing, compatibility policy, prompt repair adoption, and one authority wherever global history or identifiers span deployments. |
| Deployment | Separate workflow and integration services from the outset | Independent fault and scaling boundaries. Costs include more operations and cross-service coordination. A remote queue alone leaves a consistency gap at local commit. |

**Approved extension model.** The shared core owns the lifecycle and invokes request-type policies and vendor adapters through defined interfaces. The interfaces support isolated extension tests and explicit wiring.

**Approved packaging.** Ship the core and extensions together as separate modules in one service release.

**Approved conformance.** Add a test approval policy and replace a fake connector. Rerun lifecycle checks and report changes to modules, contracts and deployments. Future approved workflows may only add to the common lifecycle and must retain all existing states. Any variant selection or migration requires its own approval. [DEC-02](../Phase1-conceptual/decisions.md#dec-02-interview-lifecycle-and-corporate-variants) · [Decision record](decision-proposals.md) · [Acceptance evidence](acceptance-evidence.md)
