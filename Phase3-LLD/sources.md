# Source and capability verification

**Scope** A-02/08–10, DEC-03/07/08/13/14. Signed HLD-owner-r3, later owner requirements and the recorded PH3-OC-r2 selections govern. This adopted assembly revision is submitted for both package reviews.

| Source | Use |
| --- | --- |
| Interview brief and diagram | Mandatory REST and persistence, exact workflow, fields, numeric identifier, and interview submission. Owner DEC-01 adopts optional history and browse. [Brief](../tmp/pdfs/source-1.png) · [Diagram](../tmp/pdfs/source-2.png) |
| Owner assumptions and Phase 3 record | Java 25/Spring 7.0.11, corporate workflow and five enterprise tools, phase and owner approval requirements, delegated configuration and authorized short smoke. [Assumptions](../output/Assumptions-initial-HumanMade.txt) · [Kickoff](owner-kickoff.md) · [Approval gates](owner-approvals.md) |
| DEC-13 supersession | The earlier HLD saying IDs carry no ordering guarantee is superseded by the later owner's increasing signed 64-bit assignment requirement. This affects wire, shared cross-type uniqueness, assignment serialization, provider/migration mappings and old-backup proof. [HLD identifiers](../Phase2-HLD/workflow-and-history.md) · [Owner requirement](owner-identifier-requirements.md) · [Design](identifiers.md) |
| Exact baseline | HLD 25-entry manifest and shared 37-entry fingerprint list. [Signed HLD](../tmp/phase2-hld-execution/hld-owner-r3.sha256) · [Shared source list](work/reviews/source-baseline.sha256) |
| Reviewed designs | Both signed candidates and exact comparison preceded the dual-reviewed technical baseline PH3-LLD-DRAFT-r2. This revision applies the owner selections to that baseline. [Candidate 1](work/reviews/candidate-1-r2-signoff.md) · [Candidate 2](work/reviews/candidate-2-r2-signoff.md) · [Comparison](work/reviews/owner-comparison-r2-signoff.md) · [Technical baseline](work/reviews/lld-draft-r2-manifest.md) · [Owner record](owner-design-selections.md) |
| Capability anchors | The signed candidates verified the primary Spring and Java, PostgreSQL, HTTP, vendor and tooling documentation referenced by module. Pin actual component versions and rerun compatibility and behavioral fixtures before adoption. [Spring](wiring.md) · [PostgreSQL](persistence.md) · [REST](rest.md) · [Vendor docs](vendors.md) · [Tools](tooling.md) |

Evidence in this revision is source, provenance and static package validation. Runtime and vendor proofs are specifications. The approved short check needs a runnable service, real shared database and eligible runtime. [Readiness](work/reviews/performance-readiness.md) · [Assembly manifest](work/reviews/lld-r2-manifest.md)
