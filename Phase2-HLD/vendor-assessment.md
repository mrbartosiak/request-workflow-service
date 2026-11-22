# Enterprise vendor assessment

**Scope** Business fit and readiness for five corporate tools. **Status** Vendor docs checked 2026-11-22. Flows and ease assessments are proposed under DEC-03 and DEC-09. Products, priorities, ownership, data, and live delivery remain open. [Owner assumptions](../output/Assumptions-initial-HumanMade.txt) · [Phase 1 assessments](../Phase1-conceptual/integrations.md)

## Workday

Read a minimal worker reference and a permitted organization attribute for HR routing. Measure routing accuracy and manual queue reassignments. Workday documents Staffing v7 `GET /workers` and the security domains protecting endpoint data. A scoped sandbox lookup must confirm worker linkage, field access, version and entitlement. Compare scheduled refresh and on-demand lookup. Track freshness and route manually when evidence is unavailable. [Workday developer guide](https://developer.workday.com/documentation/GUID-6c547385-848b-4005-ab2c-632f1d6b5688)

## Atlassian Jira

Hand eligible bugs to engineering using a safe summary and correlation reference. Store the issue link and measure manual handoffs, missing links and duplicates. Jira owns issue data. Status import is a separate option. Cloud documents issue creation, finite webhook retries with tenant-scoped stable IDs, and throttling. Cloud search may lag. `reconcileIssues` strengthens consistency only for known issue IDs, so an empty search after a lost creation response is insufficient. Confirm edition, project scopes and searchable correlation in a sandbox. Apply the [remote safety rule](integration-delivery.md#outbound-contract). [Cloud issues](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/) · [Webhooks](https://developer.atlassian.com/cloud/jira/platform/webhooks/) · [Cloud search](https://developer.atlassian.com/cloud/jira/platform/search-and-reconcile/) · [Rate limits](https://developer.atlassian.com/cloud/jira/platform/rate-limiting/) · [Data Center](https://developer.atlassian.com/server/jira/platform/jira-rest-api-examples/)

## ShiftLeft

**Value and flow.** Attach approved scan/finding references, revision and minimal severity to remediation or exception requests. Measure evidence-link time and duplicate requests across scans. **Ease.** Conditional on confirming the corporate product and stable finding identities. Qwiet documents bearer-authenticated scan and findings reads. Harness identifies Qwiet AI as formerly ShiftLeft and now offers Harness SAST and SCA. Verify tenant interface, token scopes, retention and the approved reconciliation read. [Harness acquisition](https://www.harness.io/press-and-news/harness-strengthens-its-application-security-business-with-acquisition-of-qwiet-ai) · [Harness offering](https://www.harness.io/blog/qwiet-ai-is-now-harness-sast-and-sca) · [Qwiet reads](https://docs.shiftleft.io/sast/api/walkthrough)

## SonarQube

**Value and flow.** Attach quality gate evidence to the correct project, branch and revision for engineering review. Measure time to verify and stale matches. **Ease.** Server documents analysis callbacks with quality gate/status context and configurable HMAC-SHA256. Treat verified callbacks as hints and check the applicable Web API for reconciliation and exact analysis support. Verify Server or Cloud, version, scoped credentials, analysis identity, project mapping and supersession rules. [Server webhooks](https://docs.sonarsource.com/sonarqube-server/discovering/integrations/webhooks) · [Server Web API](https://docs.sonarsource.com/sonarqube-server/extension-guide/web-api)

## Sonatype

**Value and flow.** Lifecycle can supply approved application/report, component and policy evidence for dependency exceptions. Nexus Repository is a separate read-side option for artifact references. Measure evidence-link time and stale results. **Ease.** Confirm product, version, scopes and retention, and clarify overlap with ShiftLeft. Lifecycle documents evaluation callbacks, no reattempt for failed webhook deliveries, and report reads. Prove completeness through an approved report or audit source. Nexus documents component/asset search. [Evaluation notifications](https://help.sonatype.com/en/continuous-monitoring-concepts.html) · [Webhook limits](https://help.sonatype.com/en/lifecycle-webhooks.html) · [Report API](https://help.sonatype.com/en/report-rest-api.html) · [Nexus search](https://help.sonatype.com/en/search-api.html)

Where applicable, compare lookup with recurring reconciliation. Verify sandbox, network access, IDs, privacy and operational ownership. Keep approved references and minimal normalized evidence. [Delivery contract](integration-delivery.md)
