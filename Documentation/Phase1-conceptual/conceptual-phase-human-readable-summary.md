# Request Management Conceptual Summary

The project will build a shared internal REST service for HR tickets, bug reports, and other requests that require approval. It will use Java 25 and Spring Framework 7.0.11 and store requests in a database. The interview scope includes a complete history of state changes and browsing with name and state filters. Browsing defaults to 10 requests per page. [Assumptions](assumptions.md) · [Features](features.md)

## How a request moves through the service

A request starts in **Created** with a name and body. The standard path is **Created → Verified → Accepted → Published**. A created request can be deleted. A verified or accepted request can be rejected. Both actions require a reason. Publishing assigns a unique numeric publication ID. Published, deleted, and rejected are terminal states. The body can be edited in Created or Verified, and an edit preserves the current state. [Workflow rules](workflows.md)

Each request has one current state. HR, bug reporting, and other internal request types start with the same lifecycle. Future variants may only add to it and must retain every existing state. Organizational roles or groups may determine who can approve a request. Where an access policy applies, both the actor's permission and the workflow must allow the action. Access tests must show **zero unauthorized reads or changes** across the approved permission scenarios, including direct access, browsing, history where exposed, and results returned on retry. [Domain model](domain-model.md) · [Access isolation](quality-acceptance.md#q-13-access-isolation)

## Performance and reliability

The service must handle **100 requests per second** across multiple running instances. **Every request must finish in under 3 seconds**. At that load, **at least 95% of requests for each core operation must finish within 500 ms** under the agreed workload. Measure creation, editing, state transitions, and browsing separately. The database must ensure that a recognized retry of the same successful action takes effect only once, even when handled by another instance. Concurrent actions must produce a valid sequence of changes, and failed writes must leave no partial change. [Quality criteria](quality-acceptance.md)

Validate requests at the REST entry point before writing to the database. Log all incoming and outgoing requests and describe errors clearly. Follow Spring Security Advisories and the Snyk Security Database, keep code complexity low, and report test coverage. Implementation will use test-driven development. [Quality criteria](quality-acceptance.md)

Restore verified service **within 60 minutes of declaring a database recovery incident**. Check workflow state, history, and any outstanding integration work before resuming service. Report any lost acknowledged writes separately. Recovery drills should also cover traffic spikes and integration failures. [Operational recovery](quality-acceptance.md#q-10-operational-recovery) · [Production risks](production-risks.md)

## Enterprise integration opportunities

Workday could help route HR requests to the right team. Atlassian Jira could hand bug reports to engineering. ShiftLeft could link security scan evidence, SonarQube could supply quality gate results, and Sonatype could supply dependency policy results or artifact metadata, depending on the product in use. For each candidate, assess its business value, the data it needs, and how it behaves during an outage or retry. The shared service should accommodate new workflows and connectors while preserving its lifecycle rules. [Integration assessment](integrations.md)

## Decisions still open

PostgreSQL is a candidate. The database engine, integration priorities, and any live vendor connections remain open. The owner also needs to approve who can act on or see requests, what data is retained after deletion, how retries are identified, and how the performance targets will be measured. [Decision register](decisions.md)

## Interview submission

The final interview submission is a link to a public GitHub or Bitbucket repository containing the application source, documented assumptions, and a short README at the repository root. [Submission requirements](assumptions.md#final-interview-submission)
