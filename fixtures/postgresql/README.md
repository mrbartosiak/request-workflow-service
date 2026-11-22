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

## Run the B06 synthetic authority and guarded release checks

Use the G1-U PostgreSQL 18.7 and pgJDBC 42.7.14 pins and the Java/Maven setup above. Reserve both 55480 and 55481 for the entire check. The launcher provisions separate workflow and proof clusters, unique role credentials and select-only observers. The workflow role receives column-level UPDATE on `policy_guard.policy_version` because PostgreSQL requires UPDATE privilege for row-locking reads. The proof runtime can append reservations and advance the counter. The separate proof owner certifies genesis and the single writer and retains exclusive access to their updates.

Run from the B06 candidate checkout. Use a fresh scratch root with its own copy of the approved cache and a fresh `b06-` run ID.

~~~powershell
$workspace = (Resolve-Path '<main workspace root>').Path
$phase = Join-Path $workspace 'tmp/phase5-development'
$b04 = Join-Path $phase 'b04-fixture'
$b06 = Join-Path $phase 'b06-fixture'
$cache = Join-Path $b06 'm2/repository'
$runs = Join-Path $b06 'runs'
$bin = Join-Path $b04 'runtime/pgsql/bin'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$build = Join-Path $b06 'launcher'
$runId = 'b06-example-01'

New-Item -ItemType Directory -Force -Path $build, $runs, (Split-Path -Parent $cache) | Out-Null
if (!(Test-Path -LiteralPath $cache)) {
    Copy-Item -LiteralPath (Join-Path $b04 'm2/qualified-repository') -Destination $cache -Recurse
}
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" clean verify
if ($LASTEXITCODE -ne 0) { throw 'Offline reactor build failed' }

$entries = @('adapter-authority/target/test-classes', 'adapter-authority/target/classes',
    'adapter-jdbc/target/classes', 'application/target/classes', 'domain/target/classes')
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
& $javac -encoding UTF-8 -classpath $classpath -d $build 'fixtures/postgresql/QualifyPostgresql.java' 'fixtures/postgresql/B06Postgresql.java'
if ($LASTEXITCODE -ne 0) { throw 'B06 launcher compile failed' }
& $java --class-path "$build;$classpath" B06Postgresql run $bin $runs $runId
if ($LASTEXITCODE -ne 0) { throw 'B06 PostgreSQL checks failed' }
~~~

The expected backend-loss test may produce a Spring rollback diagnostic after the scratch owner terminates only the in-flight release connection. The launcher also injects an unknown transaction outcome after a guarded enqueue. Both paths keep the buffer unreadable. A subsequent guarded call completes and releases normally. The final lines confirm that both postmasters stopped and ports are free. A restricted process scope may fail to connect to the loopback listener. Use a scope allowed to connect to the two owned fixture endpoints.

For an interrupted run, wait for the launcher to exit and repeat with `cleanup`, the same binary directory, scratch paths and run ID. The launcher checks the run marker, data directory, executable, PID and start time before stopping a postmaster. It keeps the generated credentials and raw server diagnostics inside the private scratch directories.

~~~powershell
& $java --class-path "$build;$classpath" B06Postgresql cleanup $bin $runs $runId
if ($LASTEXITCODE -ne 0) { throw 'B06 PostgreSQL cleanup failed' }
~~~

## B08 listening HTTP and restart

Use the owner-approved Tomcat 11.0.29 core and annotations artifacts in a private copy of the qualified Maven cache. Build with the `synthetic-http` profile. It adds the B08 listening entry point to the bootstrap JAR. Use the approved portable PostgreSQL installation and serialize runs that share ports 55480 and 55481. The standalone PostgreSQL fixture POM resolves the qualified JDBC runtime.

Run from the candidate checkout. Adapt the variable setup in the B06 reproduction to a fresh B08 scratch root with its own cache, launcher directory and runs directory. Verify the private Tomcat cache entries against their Central SHA-256 sidecars first. Use a fresh B08 run ID.

~~~powershell
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" -Psynthetic-http clean verify
if ($LASTEXITCODE -ne 0) { throw 'Offline reactor build failed' }

$postgresClasspath = Join-Path $build 'postgres-classpath.txt'
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" -f fixtures/postgresql/pom.xml `
    org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath `
    '-DincludeScope=runtime' "-Dmdep.outputFile=$postgresClasspath"
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL runtime resolution failed' }
$database = (Get-Content -Raw -LiteralPath $postgresClasspath).Trim()

function Get-RuntimeJars($node) {
    foreach ($dependency in $node.children) {
        if ($dependency.scope -notin 'compile','runtime') { continue }
        if ($dependency.type -ne 'jar' -or $dependency.classifier) { throw 'Unexpected runtime artifact packaging' }
        $file = "$($dependency.artifactId)-$($dependency.version).jar"
        if ($dependency.groupId -eq 'com.company.requests') {
            (Resolve-Path -LiteralPath "$($dependency.artifactId)/target/$file").Path
        } else {
            $group = $dependency.groupId.Replace('.','/')
            (Resolve-Path -LiteralPath (Join-Path $cache "$group/$($dependency.artifactId)/$($dependency.version)/$file")).Path
        }
        Get-RuntimeJars $dependency
    }
}
$graph = Get-Content -Raw 'bootstrap/target/requests-dependency-tree.json' | ConvertFrom-Json
$bootstrap = (Resolve-Path "bootstrap/target/bootstrap-$($graph.version).jar").Path
$runtime = (@($bootstrap) + @(Get-RuntimeJars $graph) + ($database -split ';') | Select-Object -Unique) -join ';'

[xml]$suite = Get-Content -Raw 'bootstrap/target/surefire-reports/TEST-com.company.requests.bootstrap.RequestsMvcSmokeTest.xml'
$testClasspath = ($suite.testsuite.properties.property | Where-Object name -eq 'surefire.test.class.path').value
$fixtureClasspath = ($build,$testClasspath,(Resolve-Path 'adapter-jdbc/target/test-classes').Path,$database) -join ';'
& $javac -encoding UTF-8 -classpath $fixtureClasspath -d $build fixtures/postgresql/QualifyPostgresql.java `
    fixtures/postgresql/B08HttpSession.java fixtures/postgresql/B08MvcSession.java fixtures/postgresql/B08Postgresql.java
if ($LASTEXITCODE -ne 0) { throw 'B08 launcher compile failed' }

$runId = 'b08-http-example-01'
& $java "-Drequests.b08.http.runtimeClasspath=$runtime" -cp $fixtureClasspath B08Postgresql wire $bin $runs $runId
if ($LASTEXITCODE -ne 0) { throw 'B08 listening HTTP checks failed' }
~~~

The fixture starts both owned databases and provisions the immutable synthetic policy and writer certificate. It starts the first application, issues a key over HTTP and sends an authorized Create, then closes the application and observes that its port refuses connections. The second application uses the same port, key, byte-for-byte request and live databases. A separate observer checks the committed effect and release evidence. The launcher streams credentials to each child over a private pipe and closes it to stop that child. The regular `B08Postgresql run` mode still runs the in-process JDBC fault checks and MVC subprocess checks. Use `B08Postgresql cleanup` with the original binary directory, scratch paths and run ID to recover owned PostgreSQL processes after an interruption.

For a separately provisioned B08 scope, start the same application with the resolved `$runtime` classpath and a private stdin pipe. Pass an empty owned directory and a port, with `0` requesting a free port.

~~~powershell
& $java -cp $runtime com.company.requests.bootstrap.SyntheticHttpMain 0 $httpScratch
~~~

Write ten UTF-8 lines to stdin in order. Supply the workflow and proof runtime role passwords, writer UUID, writer generation, two unpadded base64url-encoded 32-byte secrets (signing and intent), then the owner, owner-alias, key-only and no-issue bearer values. Keep the pipe open while making requests and close it to stop the application. The app prints its `READY` loopback endpoint. Use a local client to send `POST /operation-keys` with the owner bearer, then `POST /requests` with that bearer, the returned `Idempotency-Key` and an exact JSON Create body. Treat the bearer and key as private inputs to the client.

## B12 guarded replay and independent release evidence

Run the fixture with the qualified Windows toolchain and a fresh scratch directory. Reserve 55480–55482 until the workflow, proof and isolated backup copy are stopped. The fixture executes the existing Create routes in the effective-access Spring MVC context against real PostgreSQL. It applies V005 after a legacy B08 preparation, injects trusted time and earlier receipt answers through private ports, and checks both orders of the PostgreSQL guard race. It verifies a physical backup copy and compares its receipt and result rows with the independent acknowledgment journal.

Use the B32 launcher to build the packaged runtime and obtain its qualified classpath. Set `$workspace` to the main workspace and `$scratch` to a new directory under private run storage.

~~~powershell
& ./fixtures/postgresql/b32/Run-B32.ps1 -Action Build -Workspace $workspace -Scratch $scratch
$manifest = Get-Content -Raw -LiteralPath (Join-Path $scratch 'prepared.json') | ConvertFrom-Json
$phase = Join-Path $workspace 'tmp/phase5-development'
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$jdk = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$build = Join-Path $scratch 'b12-launcher'
$runs = Join-Path $scratch 'runs'
$springTest = Join-Path $scratch 'repository/org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar'
$classpath = "$($manifest.runtime);$springTest"
$runId = 'b12-local-01'

New-Item -ItemType Directory -Path $build | Out-Null
& (Join-Path $jdk 'bin/javac.exe') -encoding UTF-8 -classpath $classpath -d $build `
    fixtures/postgresql/QualifyPostgresql.java fixtures/postgresql/B08MvcSession.java `
    fixtures/postgresql/B10FixtureSources.java fixtures/postgresql/B10Postgresql.java fixtures/postgresql/B12Postgresql.java
if ($LASTEXITCODE -ne 0) { throw 'B12 fixture compilation failed' }
& (Join-Path $jdk 'bin/java.exe') -cp "$build;$classpath" B12Postgresql run $bin $runs $runId
if ($LASTEXITCODE -ne 0) { throw 'B12 PostgreSQL fixture failed' }
~~~

After an interruption, use `B12Postgresql cleanup` with the same classpath, binary directory, scratch path and run ID to verify the run marker and stop the owned processes.

To enable the upgraded synthetic application composition, supply `SyntheticCreateConfiguration.GuardedReplay` in `SyntheticEffectiveAccessConfiguration.Settings`. Apply `JdbcAuthorityProvisioning.migrateReleaseEvidence` as the independent proof owner and authorize a separate proof-database login for each preparation, handoff, client receipt and read input. Grant each login schema usage and execute on its matching V005 function. The B12 fixture wires the preparation and handoff ports into the normal Spring MVC controller and verifies acknowledgment through its separate synthetic client role. The B08 standalone launcher uses its original settings and wire contract.

The earlier-generation fixture port supplies a synthetic trusted receipt or a proof miss. A real implementation of `OriginalReceiptProof` needs authoritative original commit and retention evidence. The default port fences earlier keys. The physical backup verifies effect presence for loss classification and remains outside the shipping proof path.
