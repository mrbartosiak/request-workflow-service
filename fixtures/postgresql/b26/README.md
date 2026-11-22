# B26 PostgreSQL inbound acknowledgment fixture

Build in the B26 checkout using the qualified local Java 25, Maven and PostgreSQL 18.7 cache. Use a private scratch directory. `Build` and `Compile` are port-free. Obtain the fixture lease on 55480–55482 before `Run` or `Cleanup`. The B26 run starts workflow and authority on loopback ports 55480 and 55481.

~~~powershell
$workspace = 'C:/path/to/main-workspace'
$scratch = Join-Path $workspace 'tmp/phase5-development/b26-fixture'
$b26 = './fixtures/postgresql/b26/Run-B26.ps1'
& $b26 -Action Build -Workspace $workspace -Scratch $scratch -RunId b26-example
& $b26 -Action Run -Workspace $workspace -Scratch $scratch -RunId b26-example
~~~

`Build` verifies the default and synthetic HTTP packages and compiles the fixture against the latter. `Compile` uses the profile package already built in the exact checkout. After an interrupted `Run`, call `Cleanup` with the original workspace, scratch and run ID. The qualified B10 launcher creates the B26 run directory with the `b10-b26-` prefix. Keep database data, logs and credentials in private fixture storage.

The fixture uses signed typed notices and two receiver instances with separate workflow pools. It authenticates the configured caller and proves the current writer, scope, exact B18 mapping and service access. It compares the acknowledgment, PostgreSQL transaction status and exact durable counts at a paused COMMIT, a failed deferred processing constraint and a lost successful COMMIT reply. The receipt and processing obligation carry the same transaction ID. Exact retries preserve both rows. Committed discrepancies preserve their original binding and persist through a clean PostgreSQL restart.

A second certified organization creates its target through the ordinary command and B18 mapping paths. Its independently authenticated receiver uses the same complete source key. The fixture observes the PostgreSQL blocker across the two organization guards and verifies one receipt, one obligation and one committed discrepancy after a bounded retry. Denied and unmapped notices return safe rejection while the same full key is locked.

The workflow owner explicitly runs `JdbcInboundMigration.migrate`. The fixture first installs the existing authority publication schema used by the B18 registry function. Keep the original mapping and current-access proof grants. Grant the workflow role SELECT on the inbox, SELECT on the four processing identity columns, and INSERT on the receipt and quarantine binding columns and on the processing identity, identifier type and external ID columns. The database supplies the transaction IDs and processing envelope version. Grant the independent observer read-only access for evidence.

Use `SyntheticInboundConfiguration.receiver` with the same workflow data source as the current access and B18 projection. Certify continuity for each exact source, tenant and identity domain on its current writer. For this fixture, a provider version is monotonic per resolved target in that scope and a fresh notice must match the current target revision. A processing obligation supplies a guarded external lookup reference. A reconciliation consumer must prove current authorization and authoritative source evidence before asking the ordinary command path to change workflow state. B27 adds recovery admission and retained coverage after rewind.
