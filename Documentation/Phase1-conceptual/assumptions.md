# Assumptions and delivery scope

## Product assumptions

| ID | Status | Statement | Source |
| --- | --- | --- | --- |
| A-01 | Source requirement | Build a company-internal request management backend. Consider HR ticketing, organization-wide bug reporting and handling, and other approval-dependent workflows when evaluating reuse. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) |
| A-02 | Source requirement | Use Java 25 and Spring Framework 7.0.11 for implementation. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) |
| A-03 | Source requirement | Expect new requirements after production launch. Favor long-term maintainability and extensibility. Keep conceptual domain entities small and focused. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) |
| A-04 | Source requirement | Expect concurrent application instances. Meet the owner's capacity, per-request latency, and database-level idempotency criteria. The database engine is open. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Quality acceptance](quality-acceptance.md) · [DEC-03](decisions.md#dec-03-database-and-initial-vendor-delivery) |
| A-05 | Source requirement | Assume user types, organizational roles, and groups exist. Some requests may be approved by designated roles or groups. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Access and approvals](decisions.md) |
| A-06 | Source requirement | Assume a corporate environment with Workday, Atlassian Jira, ShiftLeft, SonarQube, Sonatype, and other enterprise tools. Assess the business value and ease of integration for all five named tools. Evaluate extension mechanisms that preserve shared core functionality and lifecycle rules. Integration priorities and live delivery scope are open. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Integrations](integrations.md) · [DEC-03](decisions.md#dec-03-database-and-initial-vendor-delivery) |
| A-07 | Source requirement | Identify production issues from comparable applications and specify observable resilience outcomes. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Production risks](production-risks.md) |
| A-08 | Source requirement | Obtain owner approval before adopting any important decision. | [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Decisions](decisions.md) |
| A-09 | Owner-approved | Use the exact interview lifecycle initially for HR, bug reporting, and other request types. Defer corporate variants and apply the owner's extension constraint to any future variant. | [DEC-02](decisions.md#dec-02-interview-lifecycle-and-corporate-variants) |
| A-10 | Owner-approved | Include complete state-change history and paginated browsing in interview delivery. | [DEC-01](decisions.md#dec-01-optional-interview-features) · [Approved features](features.md#owner-approved-interview-features) |

## Phase obligations

The owner’s delivery plan calls for AI-optimized and human-readable editions of the assumptions, high-level design, and low-level design. This Phase 1 package is the AI-optimized edition requested for this work. [Owner assumptions](../output/Assumptions-initial-HumanMade.txt)

| Phase | Deliverable or obligation |
| --- | --- |
| 1. Conceptual | Describe features and quality acceptance in separate Markdown files. Define conceptual domain entities and their relationships. |
| 2. High-level design | Create a Markdown design in its own folder. Link from the main document to short, independently includable subdocuments. Prefer fewer than 5,000 characters per document and justify exceptions. Address idempotency, concurrency control, and reliable integration delivery, with clarity and maintainability as priorities. |
| 3. Low-level design | Use the same linked Markdown structure and length preference in a separate folder. Prefer existing Spring configuration and classes. Justify dependencies with advantages in the AI edition. Evaluate DTO generation from YAML, compile-time DTO mapping, breaking API change checks in CI, database-schema-derived types, schema-derived event payloads, and package-boundary tests. The owner identifies retry safety and retry tests against PostgreSQL for this phase. [Database decision](decisions.md#dec-03-database-and-initial-vendor-delivery) |
| 4. Development planning | Create a detailed and granular implementation plan. |
| 5. Development | Use test-driven development. |
| 6. Iterative improvement | Review and justify changes. Update conceptual or design documents when required. Record a new planning version for planning changes, implement the changes, record version information in commits, and keep endpoint paths unversioned. |

## Final interview submission

- Implement the REST backend and persist requests in a database. The brief specifies a backend service with no GUI requirement. Record the assumptions that govern the submission. [Interview brief, page 1](../tmp/pdfs/source-1.png)
- Submit source code and the requested documentation. Keep the database itself, compiled classes, dependency binaries, and bundled servers outside the submission. [Interview brief, page 1](../tmp/pdfs/source-1.png)
- Place a short application description in a `README.md` file at the repository root. [Interview brief, page 2](../tmp/pdfs/source-2.png)
- At final delivery, provide a link to a public GitHub or Bitbucket repository by the agreed deadline. [Interview brief, page 1](../tmp/pdfs/source-1.png)
