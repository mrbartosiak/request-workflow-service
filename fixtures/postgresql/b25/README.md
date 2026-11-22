# B25 scheduling and destination backpressure

The opt-in entry point is `SyntheticDispatchConfiguration.scheduledWorker`. Apply the B23 and B24 workflow migrations, then call `JdbcDispatchSchedulingMigration.migrate` as the workflow owner. Provision the fake endpoint in a separate local database with the B24 remote migration. The qualified launcher shows the column grants and independent proof, command, worker and connector roles.

Give each scheduled process an independent `ConnectionPoolDataSource` for its worker role. The worker owns four workflow sessions and its own scheduler. All processes serving a scheduled tenant/destination must use the reviewed scheduled entry point and the same workflow store. The migration starts from the B24 schema and rejects an earlier experimental B25 schema without durable occupancy. Existing B23 and B24 attempts keep their original meaning and have no scheduled slot owner.

| Limit | Enforcement |
| --- | --- |
| Four workers and connections per process | Four delivery threads own at most four pooled physical sessions. The clock submits work and has no connection. |
| Batch 8 and a 15-second lease | Claim selection uses a bounded batch and the workflow clock. An unarmed expired claim releases the effect. An armed expiry retains the arm and holds the effect as unknown. |
| Two attempts per tenant/destination | A guarded arm durably owns one of two uniquely indexed slots with a fresh exact invocation ID. Advisory permits supplement the durable reservation while the workflow session is alive. |
| Two-second I/O deadline | The worker records ambiguity when it loses its deadline. Lease expiry, an unknown effect, cancellation and database session loss leave the slot occupied until the actual connector invocation exits. The local JDBC endpoint also applies the transaction deadline. |
| One-to-60-second jitter and five safe repeats in 15 minutes | Deferral and retry history are durable. The limit counts later arms of the same effect and excludes unarmed claims. Existing B24 evidence and the prior scoped effect ID still apply. |
| Trusted destination cooldown | The configured connector ties the hint to an authoritative transient observation. The worker checks exact evidence and current authority before writing the shared cooldown. The claim and arm paths check it. |

After joining the local invocation, the worker records quiescence for its exact attempt and owner. It can use a fresh workflow session when the original session is lost. This idempotent update preserves the business disposition and works independently of the current writer epoch. An invocation whose physical completion cannot be established or recorded conservatively retains its one slot. Two such calls can hold that lane pending separate operations oversight. Other tenant/destination lanes retain their own capacity.

Use the existing qualified Java 25.0.5.1+2, Maven 3.9.16, PostgreSQL 18.7 and pgJDBC 42.7.14. The script accepts a workspace root and a private scratch directory.

- `Build` runs both strict offline verifications and scans ten reactor JARs for the migration and private-class exclusion, then compiles the launchers.
- `Compile` uses current verified synthetic-profile packages and writes a hash-bound launcher manifest without starting PostgreSQL.
- `PortFree` runs the settings, worker/batch and deadline controls plus a scripted JDBC test for lost sessions, expired unknown attempts, exact-owner release and ambiguous commits.
- `Run` uses the allocated B23 fixture ports 55480–55482 plus two ephemeral IPv4 loopback HTTP listeners under a B25-owned run. Use it after the coordinator grants the fixture. Both service JVMs use the B33 effective-access HTTP launcher and independently pooled command sessions. The child configuration arrives over stdin.
- `Cleanup` uses the same scratch and original run ID if a native run needs the owned teardown path.

Each service JVM has its own restricted worker role with four dedicated sessions and a separate one-session command pool. The private proof sources connect on demand and share a checkout budget behind their original-workflow observer role. The setup pools close before the services start. This keeps the qualified PostgreSQL cap at 16.

Both HTTP listeners start before dispatch. The launcher fills four worker sessions in service A, drives live lease expiry with the connector calls held, and checks guarded HTTP responses from both JVMs. Service B then confirms unrelated work while the expired calls still occupy both slots on the pressured route. After releasing the calls, the launcher checks shared cooldown, retry counts, quarantine, process exit, closed listeners and retired sessions.

The native launcher ages persisted timing in the lease and rolling-window cases. The port-free oracle measures the runtime timeout without manipulating its clock.
