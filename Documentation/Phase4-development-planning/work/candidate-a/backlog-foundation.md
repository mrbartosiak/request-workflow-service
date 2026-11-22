# Phase 5 backlog · foundation

## FND-00 Read-only readiness and pin proposals

**Needs** G3 and Phase 5 kickoff. **Scope** Inspect available executables, build constraints, compatible pins, official sources and fixture pathways. **Red** Define an inventory check and verify it catches Java 21, missing tools or an unset candidate endpoint. **Green** Review proposed direct and transitive pins against Spring Security Advisories and Snyk. Date the inventory and applicability findings. Produce reproducible availability and compatibility proposals with owners and precise requested changes for G0/G2b. **Stop/handoff** Record optional setup branches and blocked gaps. Any actual install or access change waits for the relevant disposition. [Stack and dependencies](../../../Phase3-LLD/wiring.md) · [Preflight](environment-and-evidence.md)

## FND-01 Build and dependency seams

**Needs** `FND-00`, G0. **Scope** Build, module skeleton and test entry points. **Red** Build a minimal Java 25 compilation and Spring context test, plus a dependency-edge failure using a forbidden domain-to-Spring edge. **Green** Pin approved local launcher/dependencies, create the adopted module seams and an explicit bootstrap. **Stop/handoff** Supply actual resolved direct/transitive dependency graph, rechecked dated Spring/Snyk findings before accepting those pins, local/CI command and bootstrap/module reports. Escalate applicable blocking findings before integration. Keep driver, authority client and mandatory tool adoption behind their gates. [Wiring module table](../../../Phase3-LLD/wiring.md) · [T6-R](../../../Phase3-LLD/tooling.md)

## DOM-01 Shared lifecycle and immutable values

**Needs** `FND-01`. **Scope** Pure domain lifecycle, action results and value checks. **Red** Test exact state × action matrix and each initial type. Assert illegal/terminal pairs fail, edits only change body/revision and each valid state change emits one indexed fact. Check mandatory reasons and bounded counter overflow. **Green** Implement shared `Lifecycle`, immutable identity and `WorkflowPolicy` extension with compact value types. **Stop/handoff** Share the parameterized rule table and isolated tests. [Lifecycle table and TransitionFact](../../../Phase3-LLD/domain.md) · [E1](../../../Phase3-LLD/verification.md)

## DOM-02 Scoped synthetic actors and policies

**Needs** `FND-01`. **Scope** Synthetic principal/organization directory, application authorization and wiring seams. **Red** Force alias equivalence, incorrect scope, missing distinct Verify/Publish grants, unresolved stable person and service/on-behalf-of Create denial. Verify absent and concealed equivalence. **Green** Implement trusted actor/policy ports and deterministic synthetic actor test fixtures. Separate row/field/filter/context/execute/history/result permissions. **Stop/handoff** Record permission fixture and stable fact-version interfaces, leaving live provider and role adoption to `PROD-03`. [Identity and permission table](../../../Phase3-LLD/access.md) · [E4](../../../Phase3-LLD/verification.md)

## HTTP-01 Wire specification, validation and Problem Details

**Needs** `FND-01`. **Scope** OpenAPI YAML, handwritten boundary DTOs, input validation and error mapping. **Red** Assert exact route shapes, status/header/problem type behavior, zero mutation calls on malformed or oversized data, omitted/null/missing boundaries, multilingual byte preservation and rejection of unknown/duplicate fields, U+0000, invalid UTF-8 and surrogates. **Green** Implement strict Spring MVC boundary and contract test fixtures, including key/header and type checks. All routes ultimately reach normal entry points as the owning cards join them. **Stop/handoff** Share YAML, test matrix and mapper seam, with a representative call count and decimal-string checks. [REST contract](../../../Phase3-LLD/rest.md) · [T1/T2-R](../../../Phase3-LLD/tooling.md) · [E2](../../../Phase3-LLD/verification.md)
