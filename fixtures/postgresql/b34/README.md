# B34 PostgreSQL commit and retry evidence

The private launcher uses the B17 publication provisioner and B22 synthetic Publish trigger with the packaged application and B32/B33 loopback harness. The workflow owner explicitly installs publication receipt-retirement v3 after the B17 v2 setup. It creates disposable PostgreSQL 18.7 workflow and independent proof stores on 55480 and 55481. Obtain the coordinator's lease for 55480–55482 before invoking `Run` or `Cleanup`. The two application JVMs each use an ephemeral IPv4 loopback listener, distinct workflow and proof connections, and the same certified writer generation.

From the fixture checkout, specify the main workspace and a private scratch directory.

~~~powershell
$workspace = 'C:/Users/Asia/Documents/ChatGPT/SocieteGeneraleCodingInterview'
$scratch = Join-Path $workspace 'tmp/phase5-development/b34-execution'
$b34 = @{ Workspace = $workspace; Scratch = $scratch }
$fixture = './fixtures/postgresql/b34/Run-B34.ps1'
& $fixture @b34 -Action Build
& $fixture @b34 -Action Controls -RunId b34-controls-01
& $fixture @b34 -Action Run -RunId b34-native-01
~~~

`Build` inherits strict offline default and `synthetic-http` verification from B33/B32 and compiles the private launcher. `Compile` refreshes the private classes against the existing build for the same checkout. `Controls` needs no sockets and checks the packaged runtime, incomplete HTTP response detection, additional durable effects and missing publication retirement evidence.

`Run` uses a single-use run ID. For interrupted execution, use the same run ID and scratch with `Cleanup` under the port lease. The B17 ownership marker lives at `runs/b17-b34-…/b17-run-id`. The main transcript belongs to scratch `logs`. Child JVMs shut down through private stdin and the controller verifies HTTP connection refusal and closure of their database sessions.

| Scenario | Evidence |
| --- | --- |
| P3 Create callback failure | A finalizes the tentative receipt and the exact PostgreSQL backend blocks B's claim. At A's sanitized 500, B holds its claim before effects. Workflow fingerprints are identical and one independent ordinal is consumed. B records one Create on a distinct transaction. |
| P3 Publish append failure | A appends on the same connection and transaction as the finalized receipt. B waits on the exact backend, then pauses before effects. No local rows change. An unproved retry produces safe 503. Audited rollback burns A's ID. B commits one higher ID and one obligation, and returns the confirmed original only after required proof. |
| P4 response loss and restart | A commits and produces its ordinary HTTP response. A loopback relay forwards the exact request, captures the full upstream result for comparison, then closes the caller's connection with zero bytes. The driver stops A and starts a new A while B stays live. Both replay exact bytes, status and stable headers. Independent handoff evidence may increase with release. A dropped response adds no client acknowledgment. |
| P5 before real commit | The owned lease rolls back instead of sending commit, then reports missing commit acknowledgment. The independent observer sees an aborted XID. A returns safe 503. B's original intent and signed key record one result. |
| P5 after real commit | The owned lease performs real commit and then reports missing acknowledgment. A's Create receipt lookup is independently withheld for that command. For Publish, current scope is confirmed while original publication proof remains withheld. A returns safe 503. B's Publish replay remains held until the audited confirmation and guarded projection. The admission check and exact row comparison guard the outbound boundary. No connector is attached. |
| Signed expiry and retention | At the signed boundary, both child processes return 409 before claim or lookup. The cleanup predicate deletes zero receipts one nanosecond before the trusted cutoff. At the cutoff, the fixture owner physically deletes one exact Create receipt and one exact Publish receipt under the organization guard. The Publish retirement attests the original command XID and exact ID. The original commit view, guarded projection, independently committed journal and one READY obligation remain intact. Both original keys return 409 after cleanup with zero receipt activity or new effects. |

The JDBC fault covers application-visible uncertainty at the command data-source boundary. `pg_xact_status`, exact XIDs and witness and receipt joins establish the native result independently. B17's native v2 evidence records the original Publish cleanup failure with SQLSTATE 23503 and the retained receipt and proof. [B17 retirement verification](../../../Documentation/Phase5-development/work/B17/receipt-retention-evidence.md#native-postgresql)
