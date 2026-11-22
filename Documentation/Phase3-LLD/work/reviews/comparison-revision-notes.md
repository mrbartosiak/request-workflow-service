# Response to comparison review

**Revision** PH3-OC-r2. **Scope** Four findings on PH3-OC-r1. Signed candidates remain the sources. [Review](owner-comparison-r1-review.md) · [Exact manifest](comparison-manifest.md)

| Finding | Resolution and check |
| --- | --- |
| 1. Namespace approval | D1-R proposes one shared initial namespace. D1-D defers its construction with binding cross-type uniqueness and increasing per-namespace IDs. Index and form distinguish approval from requirement. [Decision slate](comparison.md) · [Identifier choices](comparison-identifiers.md) |
| 2. Client UUID semantics | D1-K has a scoped proof gate, durable retirement markers and no issuance endpoint or intrinsic expiry. D3 describes its alternative. Cases verify lost acknowledged Create, genuinely new UUID with and without complete history, and uniform fence or ordinary concealment as applicable. Create has no action observation. [Protocol](comparison-client-keys.md) · [Wire](comparison-wire.md) |
| 3. Combinations and dependencies | One value per component. D2-E/D2-A conflict, D6-G/D6-C vary independent settings, D1-D/D2-D leave dependent D3 behavior conditional, and D3-D/T3-R permits a fixture-only pilot. Form codes and scope conditions stay explicit. [Form rules](comparison-form-rules.md) |
| 4. Candidate options and worker limits | D1-S and D4-M select C2's transaction ordering and five modules independently. D4-W adds C2's per-instance writer cap while keeping C1's cluster attempt cap. Two-instance examples and tests state the effect of each. [Transaction choices](comparison-consistency.md) · [Delivery choices](comparison-delivery.md) |

Verify the exact sources, payloads, form rules and links before signoff. Performance, PostgreSQL and production proof remain planned. [Readiness](performance-readiness.md)
