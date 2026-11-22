# B32 two-process PostgreSQL harness

Run this fixture under the approved local PostgreSQL and synthetic HTTP scope. It starts owned workflow and proof clusters on 127.0.0.1 ports 55480 and 55481 and runs two simultaneous application JVMs on separate ephemeral IPv4 loopback ports. Serialize the PostgreSQL ports with other fixture users. The applications use the B08 listener and Spring MVC Create configuration, with separate workflow and proof data sources in each JVM.

## Reproduce

From the B32 checkout, use PowerShell on the qualified Windows host. Set the main workspace and a private scratch root that belongs to this checkout. The script uses the adopted Maven 3.9.16 installation, Temurin 25.0.5.1+2, the qualified cache and PostgreSQL 18.7 already provisioned under the workspace.

~~~powershell
$workspace = 'C:/Users/Asia/Documents/ChatGPT/SocieteGeneraleCodingInterview'
$scratch = Join-Path $workspace 'tmp/phase5-development/b32-review'
$b32 = @{ Workspace = $workspace; Scratch = $scratch }
$fixture = './fixtures/postgresql/b32/Run-B32.ps1'

& $fixture @b32 -Action Build
& $fixture @b32 -Action Leak -RunId b32-leak-01
& $fixture @b32 -Action StallCleanupRed -RunId b32-stall-red-01
& $fixture @b32 -Action Stalled -RunId b32-stall-01
& $fixture @b32 -Action Run -RunId b32-green-01
& $fixture @b32 -Action Run -RunId b32-green-02
~~~

`Build` runs offline default and `synthetic-http` clean verification, checks the packaged JARs and compiles the harness into the private scratch launcher. `Compile` refreshes the harness against an already built `synthetic-http` profile in the same checkout. `Leak` inserts a fixture class into a private control JAR and requires the runtime classpath scan to reject it. The JAR used for the leak control belongs only to that command.

`Stalled` and `Run` require local IPv4 socket access by the launched processes. Each needs a fresh run ID. A connection-restricted shell can stop during PostgreSQL readiness. For the expected stall, Java observes the bounded latch expiry, sanitized HTTP 500 and zero workflow/proof effects. Only after both app processes and both databases stop cleanly does Java emit the completion marker and exit 3. PowerShell requires that marker and exit code. `StallCleanupRed` simulates a timeout carrying a suppressed teardown exception without opening sockets and confirms the same classifier rejects it.

The B04 helper records its original B08 ownership marker in the B32 scratch runs directory. For interrupted runs, pass the same B32 run ID and scratch to `Cleanup` after confirming port allocation. The cleanup command delegates to B04's owned-process checks.

~~~powershell
& $fixture @b32 -Action Cleanup -RunId b32-green-01
~~~

## Evidence

Each application owns one two-slot workflow pool and one two-slot proof pool, built on the qualified pgJDBC pooled connection API. Lease acquisition from a full pool fails within its 200 ms wait budget. At startup the worker holds both leases, checks that a third times out, and verifies that a subsequent lease reuses the released backend with reset transaction state. The parent confirms distinct live backends for A and B and links them to their application names. Startup and restart identities are compared against independent observers using endpoint, database, postmaster start, schema OID and owner, migration history and the packaged migrator hash. The observers have independent observer credentials, default read-only transactions and no application write grants.

The contention scenario holds B immediately before executing the key claim. A inserts the same claim and pauses. The workflow observer first confirms B's in-flight HTTP call is outside PostgreSQL lock wait, then requires `pg_blocking_pids(B)` to return A's exact backend after B is released. B receives the normal command lock-timeout response, HTTP 503 `outcome_unknown`. A returns 201, B replays it, and a restarted A replays it while B stays live. Two further Creates with fresh keys check precommit and prerelease independently. Each request remains under the existing command and release budgets. Workflow and proof observers assert cumulative row counts from all phases.

| Observation | Each workflow record count | Proof reservations | Proof preparations |
| --- | ---: | ---: | ---: |
| A claimed, and again after B received 503 | 0 | 0 | 0 |
| After A's HTTP 201 | 1 | 1 | 1 |
| After B's exact retry | 1 | 1 | 2 |
| After restarted A's exact retry | 1 | 1 | 3 |
| Fresh Create held before workflow commit | 1 | 2 | 3 |
| After precommit cut and HTTP 201 | 2 | 2 | 4 |
| Next fresh Create after commit and before release preparation | 3 | 3 | 4 |
| After prerelease cut and HTTP 201 | 3 | 3 | 5 |

The exact replay assertion compares the HTTP status, Location, content type, no-store header and body. The database assertion joins the returned reference to the original actor, assignment, UTF-8 fields, trusted occurrence time, finalized receipt, one reservation and the distinct preparations. Logs record hashes of the protected response fields and counts. Private stdin carries credentials to the children and closing it shuts down the owned application. The fixture verifies connection refusal after each JVM exits.

## Reuse for B33–B40

- Use `B32App` for independently started HTTP processes, requests, fresh application restart and fixture clock changes. Set the same trusted instant on peers before admitting a controlled call.
- Use `B32Gate` to arm a named one-shot cut, wait for its backend ID and arrival timestamp, inspect durable state and release it. Submit a single gate-eligible command per app per cut. Progress only after an explicit released marker. A timeout invalidates the run.
- Use `B32Fixture` and `B32Database` for the owned B04 topology, purpose-specific connections, server/schema identity, read-only observers, and PostgreSQL blocking checks. Keep the same workflow/proof separation when an approved successor adds restore or a new witness.
- Keep additions to the JDBC decorator in the private launcher. For later command or restore scenarios, add the feature-specific cut and its raw client and observer assertions, including bounded failure paths. Count probes alone do not establish a feature's outcome.
