# B04 PostgreSQL fixture

The standalone probe qualifies a disposable real PostgreSQL 18.7 environment on Windows x64. It initializes separate PostgreSQL workflow and proof clusters on the same Windows host, exercises JDBC authentication and locking, takes and verifies an earlier physical workflow backup, starts a copy of that backup, and stops the PostgreSQL instances it starts. The fixture Maven project resolves pgJDBC 42.7.14 and its required runtime dependency. Application builds stay independent of this fixture.

## Run the qualification

Use a G1-Q-authorized Windows workspace with a verified EDB PostgreSQL 18.7 binary directory, B01 Java 25 and Maven 3.9.16, and the isolated B01 Maven settings. Run from this checkout. Set `$workspace` to the main workspace root where the coordinator provisioned the B01 tools and B04 scratch. Use an unused run ID and reserve loopback ports 55480, 55481 and 55482 for the duration of the probe.

```powershell
$workspace = (Resolve-Path '<main workspace root>').Path
$implementation = (Resolve-Path 'fixtures/postgresql').Path
$phase = Join-Path $workspace 'tmp/phase5-development'
$fixture = Join-Path $phase 'b04-fixture'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$cache = Join-Path $fixture 'm2/qualified-repository'
$classpathFile = Join-Path $fixture 'm2/qualified-classpath.txt'
$runs = Join-Path $fixture 'runs'
$bin = Join-Path $fixture 'runtime/pgsql/bin'
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$source = Join-Path $implementation 'QualifyPostgresql.java'
$runId = 'b04-example-01'

New-Item -ItemType Directory -Force -Path $runs | Out-Null
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" `
    -f (Join-Path $implementation 'pom.xml') org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree `
    org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath -Dverbose "-Dmdep.outputFile=$classpathFile"
if ($LASTEXITCODE -ne 0) { throw 'Fixture classpath resolution failed' }

$classpath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
& $java --class-path $classpath $source run $bin $runs $runId
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL qualification failed' }
```

The B04 Maven cache can be initialized by copying the adopted B03 integration `repository` to the new isolated `m2/qualified-repository` path. It contains B01's BeanUtils 1.11.0 pin for Dependency Plugin 3.10.0, which this fixture POM also applies to the standalone plugin realm. If the exact driver artifacts are missing, the same resolution command without `-o` fetches them from Maven Central under the approved G1-Q network scope. Keep `-C`, `-s` and `-gs` in both modes. Use a process scope that can connect to its own loopback postmasters. A restricted shell may report `pg_isready` no response even after the server log shows a loopback listener.

The probe prints `PASS B04 PostgreSQL qualification and teardown` after every assertion and successful shutdown. Raw native command logs, server logs, the verified physical backup and all data directories stay inside the run directory. The fixture generates separate transient credentials. The initialization password files are removed, and PostgreSQL stores SCRAM verifiers inside its scratch data and backup. Treat the raw fixture directories as private workspace artifacts.

## Cleanup

For an interrupted run, wait until the Java probe has exited and use the same binary directory, scratch directory and run ID. Cleanup is also safe to repeat after a completed qualification. It checks the run marker and each data directory, then verifies the PID file, portable executable and process start time before stopping an active postmaster. It confirms the cluster reports no running server and that its loopback port is free.

```powershell
& $java $source cleanup $bin $runs $runId
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL fixture cleanup failed' }
```

## Connection contract

| Cluster | Endpoint | Roles |
| --- | --- | --- |
| Workflow | `jdbc:postgresql://127.0.0.1:55480/workflow_db` | `b04_workflow` runtime, `b04_observer` SELECT only, `b04_admin` provisioning, `b04_backup` workflow physical replication only |
| Independent proof | `jdbc:postgresql://127.0.0.1:55481/proof_db` | `b04_proof` runtime, `b04_observer` SELECT only, `b04_admin` provisioning. The observer and administrator each use a proof-specific password. |
| Restored workflow | `jdbc:postgresql://127.0.0.1:55482/workflow_db` | Restored workflow identities from the physical image |

The probe requires SCRAM authentication and uses explicit loopback, connect/login/socket deadlines, and server-side statement, lock and idle-in-transaction budgets. Runtime roles have `CONNECT`, schema `USAGE` and the fixture table's `SELECT`, `INSERT` and `UPDATE`. B05 and B32 inherit this separation of migration, command, proof and observation responsibilities. Provision per-run credentials and scope grants to the schema operations of the next reviewed fixture under G1-U. The B04 probe tears down the endpoints and does not export secrets.

## Run the B05 local storage and transaction checks

Use the G1-U-authorized binary and dependency pins. This entry point initializes one fresh workflow cluster at `127.0.0.1:55480/workflow_db` with the same SCRAM, UTF-8 and session limits as B04. Reserve 55480 across both runs. The check provisions the B05 schema using `b04_admin` and grants `b04_workflow` SELECT/INSERT on the five workflow tables and `b04_observer` SELECT. Each role gets a distinct generated password through the fixture's connection factory.

Run from the candidate checkout. Set `$workspace` to the main workspace with the approved tools. Create a separate scratch root and copy the qualified cache once. Use fresh B05 run IDs for each invocation.

```powershell
$workspace = (Resolve-Path '<main workspace root>').Path
$phase = Join-Path $workspace 'tmp/phase5-development'
$b04 = Join-Path $phase 'b04-fixture'
$b05 = Join-Path $phase 'b05-fixture'
$cache = Join-Path $b05 'm2/repository'
$runs = Join-Path $b05 'runs'
$bin = Join-Path $b04 'runtime/pgsql/bin'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$launcher = 'fixtures/postgresql/B05Postgresql.java'

New-Item -ItemType Directory -Force -Path $runs, (Split-Path -Parent $cache) | Out-Null
if (!(Test-Path -LiteralPath $cache)) {
    Copy-Item -LiteralPath (Join-Path $b04 'm2/qualified-repository') -Destination $cache -Recurse
}
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" clean verify
if ($LASTEXITCODE -ne 0) { throw 'Offline reactor build failed' }

$entries = @('adapter-jdbc/target/test-classes', 'adapter-jdbc/target/classes',
    'application/target/classes', 'domain/target/classes')
$jars = @(
    'org/springframework/spring-jdbc/7.0.11/spring-jdbc-7.0.11.jar',
    'org/springframework/spring-tx/7.0.11/spring-tx-7.0.11.jar',
    'org/springframework/spring-beans/7.0.11/spring-beans-7.0.11.jar',
    'org/springframework/spring-core/7.0.11/spring-core-7.0.11.jar',
    'commons-logging/commons-logging/1.3.5/commons-logging-1.3.5.jar',
    'org/jspecify/jspecify/1.0.1/jspecify-1.0.1.jar',
    'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar',
    'org/checkerframework/checker-qual/3.55.1/checker-qual-3.55.1.jar'
)
$entries += $jars | ForEach-Object { Join-Path $cache $_ }
$classpath = ($entries | ForEach-Object { (Resolve-Path -LiteralPath $_).Path }) -join ';'
& $java --class-path $classpath $launcher run $bin $runs b05-empty-example empty
if ($LASTEXITCODE -ne 0) { throw 'Empty namespace check failed' }
& $java --class-path $classpath $launcher run $bin $runs b05-prior-example prior
if ($LASTEXITCODE -ne 0) { throw 'V001 upgrade check failed' }
```

The launcher checks `pg_ctl` status and loopback availability in teardown. If a run is interrupted, wait for the launcher to exit and repeat with `cleanup`, the same bin, scratch path, run ID and mode. Each run writes native diagnostics to its own private scratch directory. The application artifacts compile without the fixture driver or test entry point.

```powershell
& $java --class-path $classpath $launcher cleanup $bin $runs b05-prior-example prior
if ($LASTEXITCODE -ne 0) { throw 'B05 workflow cleanup failed' }
```
