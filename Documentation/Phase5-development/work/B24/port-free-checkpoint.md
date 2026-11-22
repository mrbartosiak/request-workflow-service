# B24 port-free checkpoint

The isolated branch `codex/b24-synthetic-delivery` remains based on `5f5df645231182b82c51f867a0cf65fc2713a73a`. It includes reviewed B23 `9494969b4aca3880aac04dea2f6f502a0b1fa63a`. The approved PH4-PLAN-r1 manifest digest is `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, and all 30 entries matched. The coordinator owns integration, the ledger and the pending native lease. [Assignment](assignment.md) · [Acceptance](../../../Phase4-development-planning/outbound.md#b24--fake-connector-and-ambiguous-delivery-disposition) · [Delivery and safe repeat](../../../Phase3-LLD/integration.md) · [E6](../../../Phase3-LLD/verification.md)

## Frozen source and proof boundaries

The 14 source and fixture paths have SHA-256 fingerprints in [port-free-source.sha256](port-free-source.sha256), manifest digest `9c06c5f03a21b6e7e0e7a2e231a320081387482e83d229441705afab267ff65d`. The snapshot remains uncommitted pending a coordinated integration parent and Git date.

- The B24 composition adds an opted-in synthetic connector to the B23 claim and durable `ARMED` protocol. Workflow and remote transactions close independently. The workflow binds the scoped effect to the persisted endpoint identity at first arm.
- The independent synthetic database serializes all arrivals, async completion, lookup and fencing on the full destination, tenant and effect key. It records the attempt number, immutable request reference and revision, and exact durable proof. Its `SINGLE_EFFECT` contract also has a scoped unique index for the applying effect. The fence commits an attempt prefix cutoff, cancels pending old jobs and captures current absence in the same remote transaction.
- Local confirmation retains the scoped remote proof, effect reference and actual applying attempt. Exact authority and publication checks protect initial dispatch and later disposition. Stale, partial, mismatched or unfenced evidence leaves ambiguity held. Failed authorization of an attempted replacement preserves older uncertainty.
- The authenticated operator uses the existing scoped dispatch grant and exact original Publish proof. The audit binds the operator, action, writer, policy version and attempt at admission. Current authority and audit identity must still match at disposition. Caller-visible recovery reports recorded or withheld. The trusted runtime alone resolves the remote ledger.
- The fixture uses a dedicated synthetic database and role within the owned proof cluster to preserve separate persistence and transaction boundaries on the allocated loopback ports. B25 can reuse the repeat disposition and configured deferral. B28 can reuse the fake endpoint, conditional send and lookup controls, and exact proof queries. [Provisioning and cases](../../../../tmp/phase5-development/b24-synthetic-delivery/fixtures/postgresql/b24/README.md)

## Verification

The port-free negative control bypassed only the repeat-proof predicate in the B24 policy copied over a scratch export of the pinned baseline. `SyntheticDeliveryTest.absenceRequiresAStableSingleEffectContractOrActualExclusion` failed with expected `QUARANTINED`, observed `RETRYABLE`. The perturbation remains in private scratch. The added policy tests check async acceptance and exact authoritative evidence as well as the repeat rule.

The final frozen sources passed both clean reactor builds with qualified Java 25.0.5.1+2, Maven 3.9.16 and `-o -B -C -ntp`. Both modes ran 351 tests with zero failures, errors or skips, including all three new policy tests and all five existing B23 coordinator tests. The default and profile scans checked 10 and 30 packages respectively. The synthetic profile also compiled the private B24 and unchanged B23 launchers. `git diff --check` passed, and all 14 fingerprints were verified after the build.

| Evidence in `tmp/phase5-development/b24-fixture/logs` | SHA-256 |
| --- | --- |
| `default-verify.log` | `32a9a7fbe9c5328ffc2a8c1292701db8853efd1bd24f1f4b1bf2593a852d8217` |
| `profile-verify.log` | `28e83c7fe476fefed565ae842489f1330c5bc535f0ffd3c0b35ee1d4eff34828` |
| `b24-repeat-red.log` | `4e2e3eeda4dbf3f3c452732c5154677d1f80a337ba39068cb5633df747a093ab` |

Native E6 remains pending the coordinator's allocation of loopback ports 55480–55482. Run from the B24 checkout on the authorized parent. The B24 script uses PostgreSQL 18.7 and pgJDBC 42.7.14. The result must compare durable local attempts, remote application counts, exact proof and read authority, and verify owned teardown.

```powershell
$workspace = (Resolve-Path '../../..').Path
$scratch = (Resolve-Path '../b24-fixture').Path
& './fixtures/postgresql/b24/Run-B24.ps1' -Action Build -Workspace $workspace -Scratch $scratch -RunId b24-exact-prep
& './fixtures/postgresql/b24/Run-B24.ps1' -Action Run -Workspace $workspace -Scratch $scratch -RunId b24-native
```

If normal teardown fails, rerun the B24 script with `-Action Cleanup` and the same native run ID. The successful native result and exact commit go to independent Astra xhigh review after the parent and date are assigned.
