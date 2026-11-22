param(
    [Parameter(Mandatory)][ValidateSet('compile','green','red','b11','b08','cleanup')][string]$Mode,
    [Parameter(Mandatory)][string]$Scratch,
    [ValidatePattern('^b(08|10|11)-[a-z0-9-]{1,32}$')][string]$RunId
)
$ErrorActionPreference = 'Stop'
$src = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$scratchRoot = (Resolve-Path -LiteralPath $Scratch).Path
$phase = Split-Path -Parent $scratchRoot
$jdk = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$cache = Join-Path $scratchRoot 'repository'
$runs = Join-Path $scratchRoot 'runs'
$launcher = Join-Path $scratchRoot 'launcher'
$pg = Join-Path $cache 'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'
$checker = Join-Path $cache 'org/checkerframework/checker-qual/3.55.1/checker-qual-3.55.1.jar'
[xml]$suite = Get-Content -Raw -LiteralPath (Join-Path $src 'bootstrap/target/surefire-reports/TEST-com.company.requests.bootstrap.RequestsMvcSmokeTest.xml')
$testClasspath = ($suite.testsuite.properties.property | Where-Object name -eq 'surefire.test.class.path').value
$jdbcTests = Join-Path $src 'adapter-jdbc/target/test-classes'
$fixtureClasspath = ($launcher,$testClasspath,$jdbcTests,$pg,$checker) -join ';'

if ($Mode -eq 'compile') {
    New-Item -ItemType Directory -Force -Path $launcher,$runs | Out-Null
    $sources = @('QualifyPostgresql.java','B10Postgresql.java','B10FixtureSources.java','B11Postgresql.java','B08Postgresql.java',
        'B08MvcSession.java','B08HttpSession.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
    & "$jdk/bin/javac.exe" -encoding UTF-8 -classpath $fixtureClasspath -d $launcher $sources
    exit $LASTEXITCODE
}
if (!$RunId) { throw 'A fresh run ID is required for native runs. Cleanup uses the same B10 run ID.' }

if ($Mode -eq 'b08') {
    function Get-RuntimeJars($node) {
        foreach ($dependency in $node.children) {
            if ($dependency.scope -notin 'compile','runtime') { continue }
            if ($dependency.type -ne 'jar' -or $dependency.classifier) { throw 'Unexpected runtime artifact packaging' }
            $file = "$($dependency.artifactId)-$($dependency.version).jar"
            if ($dependency.groupId -eq 'com.company.requests') {
                (Resolve-Path -LiteralPath (Join-Path $src "$($dependency.artifactId)/target/$file")).Path
            } else {
                $group = $dependency.groupId.Replace('.','/')
                (Resolve-Path -LiteralPath (Join-Path $cache "$group/$($dependency.artifactId)/$($dependency.version)/$file")).Path
            }
            Get-RuntimeJars $dependency
        }
    }
    $graph = Get-Content -Raw -LiteralPath (Join-Path $src 'bootstrap/target/requests-dependency-tree.json') | ConvertFrom-Json
    $bootstrap = (Resolve-Path -LiteralPath (Join-Path $src "bootstrap/target/bootstrap-$($graph.version).jar")).Path
    $runtime = (@($bootstrap) + @(Get-RuntimeJars $graph) + @($pg,$checker) | Select-Object -Unique) -join ';'
    & "$jdk/bin/java.exe" "-Drequests.b08.http.runtimeClasspath=$runtime" -cp $fixtureClasspath B08Postgresql wire $bin $runs $RunId
} elseif ($Mode -eq 'b11') {
    & "$jdk/bin/java.exe" -cp $fixtureClasspath B11Postgresql run $bin $runs $RunId
} elseif ($Mode -eq 'cleanup') {
    & "$jdk/bin/java.exe" -cp $fixtureClasspath B10Postgresql cleanup $bin $runs $RunId
} else {
    & "$jdk/bin/java.exe" "-Drequests.b10.legacyRed=$($Mode -eq 'red')" -cp $fixtureClasspath B10Postgresql run $bin $runs $RunId
}
exit $LASTEXITCODE
