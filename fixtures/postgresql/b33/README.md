# B33 two-process key contention and intent isolation

The fixture exercises the packaged B14 controllers through two separate synthetic IPv4 loopback services. Each process owns bounded two-slot pools for workflow commands, independent Create proof, the scope journal and the original workflow observer. The databases use the B04 PostgreSQL 18.7 qualification and the B10/B12 role and certified-scope boundaries. B32 supplies the workflow pool, key gates and database identity probes. B14's effective profile is registered in a private loopback launcher because the packaged B08 entry point registers only its Create profile. The fixture classes compile into scratch directories outside the packaged JARs.

## Run

From the B33 checkout, supply the main workspace and fresh private scratch paths. Obtain the coordinator's lease for ports 55480–55482 before `Run` or `Cleanup`. The fixture uses 55480 for workflow and 55481 for proof. The inherited B15 topology reserves 55482 through teardown. Run IDs are single-use.

~~~powershell
$workspace = 'C:/Users/Asia/Documents/ChatGPT/SocieteGeneraleCodingInterview'
$scratch = Join-Path $workspace 'tmp/phase5-development/b33-fixture'
$b33 = @{ Workspace = $workspace; Scratch = $scratch }
$fixture = './fixtures/postgresql/b33/Run-B33.ps1'
& $fixture @b33 -Action Build
& $fixture @b33 -Action Controls -RunId b33-controls-01
& $fixture @b33 -Action Run -RunId b33-native-01
~~~

`Build` runs strict offline clean verification in the default and `synthetic-http` modes and compiles the private launchers against the packaged synthetic profile. `Compile` refreshes the fixture using a profile built within the same checkout. `Controls` rejects a missing or incorrect native blocker, an added durable effect and an injected fixture class in a private JAR without opening database or HTTP sockets.

For interrupted runs, pass the same scratch directory and run ID to `Cleanup`. It uses the B15 ownership marker under `runs/b15-b33-…`. The native run shuts down each app by closing private stdin, checks TCP connection refusal and verifies that the app sessions leave both stores before stopping the two postmasters.

## Scenario and evidence contract

| Case | Held order and acceptance |
| --- | --- |
| P1 settles | B reaches the preclaim cut. A claims the same signed key. B enters PostgreSQL's lock wait with A as its exact blocker. A is released before B's claim deadline. Both receive the same original HTTP 201, stable headers, Location and exact body. |
| P1 times out | Hold A through B's native 200 ms deadline. PostgreSQL SQLSTATE 55P03 yields HTTP 503 `outcome_unknown` with no receipt lookup and no durable effect while A is held. A then commits. B retries the same key and exact body and receives the original 201. |
| P1 fresh | Two fresh keys for identical inputs produce distinct references, reservations and exact finalized receipts. The same no-additional-effect oracle rejects the actual second Create as a negative control. |
| P2 authorized | Same-route changed Create bytes and cross-route Create/Edit, Edit/Create, Verify/Create and Accept/Create reuse one key. Each B request waits on A's exact PostgreSQL backend, loses the claim, and reads the receipt. The winner's exact effect and original response are preserved. B receives 409 `key_conflict` and adds no business, reservation or release evidence. |
| P2 concealed | Pause B as its duplicate claim returns zero. A has returned the original Edit success. Revoke and confirm its result grant with the audited scope operator, then let B perform the guarded receipt lookup. B's 404 normalizes to the same Create controller's ordinary submitted-type denial with the same key. Ordinary denial reaches neither claim nor lookup. Reinstatement restores 409. |
| Signed expiry | Advance both trusted clocks to the signed boundary. Exact retry returns 409 `key_expired` with zero claim, lookup or new durable effect. |

The V009 workflow count vector lists requests, bodies, history facts, assignments, receipts, finalized receipts, scope witnesses, grant overrides, visibility records and terminal reasons. The proof count vector lists Create reservations, B12 preparations, handoffs, client acknowledgments, legacy B08 preparations, scope attempts and scope confirmations. The independent creation counter records the latest consumed ordinal. The observer probes check exact operation input digest, receipt key binding, state, transitions, trusted time and reservation order. Conflict and denial probes inspect the full safe problem shape, stable wire headers and claim/lookup order. The concealment comparison normalizes only the HTTP Date and the linked per-attempt correlation ID and instance.

`B33Evidence` contains the reusable read-only effect counts, business row fingerprints and wire oracles for B34 and B39. `B33App` starts independently owned child services and exposes their B32 gates, backend identities, clock and raw HTTP response. Keep one gate-eligible command per process at a time. Preserve the 200 ms application lock budget. The concealed case uses the private worker's `BEFORE_RELEASE` gate immediately after JDBC returns zero rows from the duplicate claim and before B starts its guarded lookup.
