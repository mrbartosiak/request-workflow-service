# B45–B47 · API contract and approved pilots

### B45 · Unversioned API and explicit adapters

**Needs** B03, B17, B20 and B21. **Sources** T1/2/4/5-R and adopted wire. [Tooling](../../../Phase3-LLD/tooling.md) · [REST](../../../Phase3-LLD/rest.md) · [Module assembly](../../../Phase3-LLD/wiring.md)

**Red** Compare OpenAPI YAML samples with actual Spring behavior. Seed unknown fields, absent versus null, problem headers, precise numeric string, output masks and path mismatches. Exercise explicit row/envelope fixtures after migration through B05/B22 as applicable, without requiring B22 for API evidence. **Green** Checked unversioned OpenAPI source, explicit DTOs and handwritten maps, spec/behavior checks. Maintain explicit JDBC and versioned internal envelope contracts in owning work. **Stop** Record API/fixtures and linkage to B05/B22. Hand seeded spec baseline to B46 and compatibility cases to B43/B48.

### B46 · T3-R API diff pilot

**Needs** B45 and G6-P for the approved trial setup. **Sources** Pilot-only decision and named break cases. [Tooling](../../../Phase3-LLD/tooling.md) · [Decisions](../../../Phase3-LLD/decisions.md)

**Red** Seed a removed route and an ID whose type changes to number. Include safe additions and unchanged behavior. **Green** Run the pinned oasdiff pilot at pull request and release with reproducible fixtures, plus manual diff/Spring testing comparison. Use an approved actual release baseline once available, or label the seeded baseline as pilot evidence. **Stop** Save version, compatibility outcomes, limits and manual/automatic costs. Hand G6-M evidence for separately approved compulsory use.

### B47 · T6-R package boundary pilot

**Needs** B01, B25 and G6-P for the approved trial setup. **Sources** Test-only pilot and permitted module graph. [Tooling](../../../Phase3-LLD/tooling.md) · [Wiring](../../../Phase3-LLD/wiring.md)

**Red** Seed domain→Spring, cross-adapter dependency and cycle. Check valid wiring and Java 25 bytecode import. **Green** Run the test-only ArchUnit pilot at pull request and release with a pinned candidate and repeatable fixtures. Compare enforced build dependencies and a manual JDK dependency check. **Stop** Capture permitted/forbidden edges, Java 25 compatibility, costs and links to reviewed pins. Hand G6-M evidence for separately approved compulsory use.
