# B24 synthetic remote delivery

B24 runs against the qualified local PostgreSQL fixture and ordinary guarded Publish. It uses the existing B23 cluster launcher with a B24-specific scratch directory. The workflow and authority stores keep their existing databases and logins. The fake endpoint uses a distinct database and a dedicated SCRAM runtime role on the owned proof cluster. It opens a separate transaction for every remote operation.

## Provision the synthetic worker

1. Apply the workflow dispatch grants migration and `JdbcOutboxMigration`, then install `JdbcOutboxWorkerMigration` and `JdbcSyntheticDeliveryMigration.workflow` as the workflow owner.
2. Use `JdbcSyntheticDeliveryMigration.remote` in the separate synthetic endpoint database. Use the restricted grants in `B24RemoteDatabase` and `B24Postgresql.provision`. The workflow, authority, command and remote data sources each carry only the required role privileges.
3. Opt in through `SyntheticDispatchConfiguration.syntheticWorker` with one configured contract per exact route. Use the original endpoint database identity consistently across workers. Use the B23 recorder composition on its separate fixture or workload.
4. Give the route principal and authenticated recovery operator the current tenant-scoped `DISPATCH_PUBLISH_REFERENCE` grant for the configured request type. Both must resolve the exact original publication association. The operator's action records an audit entry at admission and rechecks the latest policy and writer at disposition.

The normal send entry point is `DispatchCoordinator.dispatchNext()`. Operator recovery uses `DeliveryReconciler.recover`. It accepts the authenticated operator binding, exact scoped attempt and action. Its result reports whether the disposition was recorded. The scoped workflow audit and remote proof store hold the detail for authorized operators.

| Remote contract | Basis for repeat |
| --- | --- |
| `SINGLE_EFFECT` | An immutable per-key contract and an authoritative lookup. Remote key locking and the unique application constraint cover old, replacement and async arrivals. |
| `FENCEABLE` | An authoritative no-effect observation after a durable same-key fence. The same locked path bars future arrivals and completion of pending jobs through the fenced attempt. |
| `UNSAFE` | Explicit remote terminal no-apply records close the current attempt and every actually armed predecessor outside a verified fence. The worker checks all durable arm records for the exact scoped effect. An empty remote lookup leaves uncertainty held. |

The fake freezes the minimum invocation projection and full destination, tenant, effect and attempt identity. The worker binds its first arm to the endpoint identity. Confirmation holds the remote effect, actual applying attempt and exact observation. Async acceptance remains pending. Lost replies leave risk with the original effect ID. A proven transient uses the configured deferral. B25 owns the scheduler, jitter, limits and retry budget. B28 can reuse the persisted fake ledger and its controlled send and lookup cases.

## Reproduce

Run the script from this checkout with `-Workspace` pointing at the phase workspace root and `-Scratch` at the private B24 scratch directory.

- `-Action Build -RunId b24-prep` runs default and synthetic HTTP profile offline verification, checks the release packages and compiles the private launchers.
- `-Action Run -RunId b24-native` uses loopback ports 55480–55482 after allocation. It exercises E6 and verifies owned teardown. Use a fresh run ID for every native invocation.
- `-Action Cleanup -RunId b24-native` reads the owned B23-format run marker in B24 scratch to clean up that run if normal teardown did not finish.

The controls exercise lost replies with and without application, both arrival orders, authentic stale and partial observation shapes, scope and endpoint mismatches, current route and operator revocation, retained async jobs across restart, and safe exclusion of an already accepted job. The fixture compares local attempt state, exact remote proof and durable application count. It uses qualified Java 25.0.5.1+2, Maven 3.9.16, PostgreSQL 18.7 and pgJDBC 42.7.14.
