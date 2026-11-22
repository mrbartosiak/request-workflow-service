# B02 integration and handoff

On 2026-11-22 (Europe/Warsaw), the coordinator fast-forwarded local `master` from `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a` to independently approved `d1c34c7a781ae57ac94b258f998ac1958424100e`. The integration checkout passed offline `clean verify` across 11 projects and all 160 tests. The integration log SHA-256 is `e56f060a83edcd69b074e5e9264644603715941d3e93bec1b2712ebc49c69860`. [Authority](../g0-kickoff.md) · [Review](review.md) · [Integration log](../../../tmp/phase5-development/b02-tools/b02-integrated-offline-verify.log)

B08 consumes `Lifecycle.create` and its `Created` request, body and transition effects. B13–B17 consume `Lifecycle.next`, `edit`, `transition`, the typed transition commands and reasons, `TransitionFact`, and the `WorkflowPolicy` decision contract. Use `mvn -o -B -C -pl domain -am verify` with the adopted Maven settings and runtime. [Exact commands and outgoing API](evidence.md)
