# B18 PostgreSQL mapping fixture

Run in the B18 checkout with the qualified Java 25, Maven and PostgreSQL 18.7 cache from the main workspace. Use a private scratch directory. `Verify` and `Compile` need no listening ports. `Run` and `Cleanup` need the local fixture lease for 55480–55482.

~~~powershell
$workspace = 'C:/path/to/main-workspace'
$scratch = Join-Path $workspace 'tmp/phase5-development/b18-fixture'
$b18 = './fixtures/postgresql/b18/Run-B18.ps1'
& $b18 -Action Verify -Workspace $workspace -Scratch $scratch -RunId b18-example
& $b18 -Action Compile -Workspace $workspace -Scratch $scratch -RunId b18-example
& $b18 -Action Run -Workspace $workspace -Scratch $scratch -RunId b18-example
~~~

Run `Cleanup` with the same workspace, scratch directory and run ID after an interrupted `Run`. The launcher uses the existing Publish PostgreSQL setup to provision the same baseline, so the native run directory starts with `b17-b18-`. Treat the raw PostgreSQL data, backup, and native logs as private fixtures.

The fixture runs original Create and Publish through the configured Spring MVC handlers and invokes the guarded internal mapping service on the same profile. The physical workflow backup predates the tested mapping projection. A committed effect is read from the original certified workflow observer and matched to the independent registry. A different PostgreSQL server incarnation and a binding with no committed original effect both fail that proof. The restored copy uses a private fixture admission to test projection recovery while B39 owns production-style writer recertification. The fixture uses synthetic sources and loopback databases.
