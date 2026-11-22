# B03 execution assignment · MVC input validation and safe errors

**Base** `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a` on `codex/b03-http-edge`. `PH4-PLAN-r1` SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. GPT-6.2 Sol high (55), independent GPT-6.2 Astra medium (57). [G0](../g0-kickoff.md) · [B01 integration](../B01/integration.md)

**Ownership** `adapter-http/` only, including module-local adopted test dependencies and its MVC configuration. Use a separate copy of the approved Maven cache and in-process servlet tests. Real mutation controllers and application coordination follow in their tickets. Use in-process test mutation entry points to prove validation ordering and invocation counts.

**Oracle** Send the invalid E2 wire cases through MVC and verify safe protocol errors, required headers and zero mutation-entry calls. Verify accepted multilingual data, whitespace and casing round-trip. Implement reusable explicit DTO, bounded strict validation and Problem Details support for the accepted route inputs. Key and observation authentication and business decisions follow in their tickets. Record a meaningful red on the base, explicit negative controls, module and full reactor green evidence. [B03](../../../Phase4-development-planning/foundation.md#b03--mvc-input-validation-and-safe-error-mapping) · [REST](../../../Phase3-LLD/rest.md) · [E2](../../../Phase3-LLD/verification.md) · [Observations](../../../Phase3-LLD/observations.md)

**Return** One exact local candidate commit plus `Phase5-development/work/B03/evidence.md` for independent review. Hand the validated input and error contracts to B06–B08, B13, B19–B21 and B45. Capture any boundary work that remains in those successors.
