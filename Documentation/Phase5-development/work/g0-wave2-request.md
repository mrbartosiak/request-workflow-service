# Owner decision request · Phase 5 wave 2

**Proposed scope.** Continue `PH4-PLAN-r1`, manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, from integrated M1 commit `9469405a1e4b8bb1a345158f6b54d93ea59772ce`, tree `e82759ffe33f9492143d05b1e3e4ada65054e084`. The existing owner-facing coordinator chat owns the integration branch, scoped dispatch, gate receipts and ledger. [M1 evidence](m1.md) · [Runbook](../../Phase4.5-development-workflow/development-workflow.md)

## G0 scope extension

| Ticket | Deliverable | Dispatch prerequisite |
| --- | --- | --- |
| B09 | Durable scope prepare and terminal closure, with the local scope witness under the exclusive guard | B05 and B06 integrated |
| B11 | Audited resolver and confirmation projection | B09 integrated |
| B10 | Effective grants, assignment and visibility updates | B06, B09 and B11 integrated |
| B12 | Guarded result replay, current disclosure and independent release evidence | B08, B10 and B11 integrated |
| B32 | Deterministic two-application-process PostgreSQL verification harness with dedicated fault controls | B04, B05 and B08 integrated |
| B41 | Structured and redacted request, response and error evidence with synthetic reconciliation | B08 integrated |

Use fresh GPT-6.2 Sol xhigh executors (56) and independent GPT-6.2 Astra xhigh reviewers (62) for B09–B12 and B32. For B41 use Sol high (55) and independent Astra high (60). B09, B32 and B41 are ready at M1. Assign up to two executors at a time, serialize shared contract and fixture ownership, and verify each exact reviewed revision before dependent dispatch. [Scope proof tickets](../../Phase4-development-planning/scope-proof.md) · [PostgreSQL verification](../../Phase4-development-planning/db-verification.md) · [Operational evidence](../../Phase4-development-planning/operating-verification.md)

## G1-U reuse extension

Extend the qualified B04 fixture use to B09–B12, B32 and B41 as each ticket needs it. Use the same portable PostgreSQL 18.7, pgJDBC 42.7.14 and Checker Qual 3.55.1 files and hashes, current Windows host, workflow `127.0.0.1:55480/workflow_db`, separate proof `127.0.0.1:55481/proof_db`, and `127.0.0.1:55482/workflow_db` solely for an isolated restored workflow copy where a ticket needs rewound evidence. Use new private scratch clusters and synthetic SCRAM credentials, separate roles per purpose, serial port ownership and teardown of owned processes. This includes ticket-specific migrations, proof and witness fault cuts, bounded two-process checks and sanitized evidence on that fixture. The prior disposition documents the advisory coverage limits and shared host. [Exact prior scope and artifact hashes](B04/g1-u-request.md) · [Prior fixture disposition](B04/g1-u-approval.md) · [Fixture review](B04/review.md)

## G0-B runtime reuse extension

Reuse the adopted Maven 3.9.16, Temurin 25.0.5.1+2, Spring 7.0.11 and qualified cached build inputs. Where a wave ticket needs real HTTP, reuse the verified `synthetic-http` profile with `org.apache.tomcat.embed:tomcat-embed-core:11.0.29` and `org.apache.tomcat:tomcat-annotations-api:11.0.29`, strictly on IPv4 loopback with synthetic credentials and data. B32 may start two application JVMs with one owned HTTP-only loopback connector each and an ephemeral port per JVM. TLS and clustering are out of scope. Fault controls stay on the fixture classpath. [Adopted build](B01/g0-b-adoption.md) · [Exact runtime inputs and advisory limits](B08/http-runtime-inputs.md) · [Runtime adoption](B08/g0-b-http-runtime-approval.md)

The decision covers the six listed tickets, existing verified artifacts, local synthetic evidence and their independent reviews. Separate gates cover additional dependencies or provisioning, production work, public publication, formal workload execution and later ticket dispatch. Approve the three scopes together to authorize eligible work through the wave. [Action gates](../../Phase4-development-planning/choices.md#later-authorizations)
