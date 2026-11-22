[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'B08Mvc', 'B12', 'B15', 'B17', 'B19', 'B20', 'B21', 'B22')]
    [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b21-[a-z0-9-]{1,27}$')] [string] $RunId = 'b21-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$phase = Join-Path $Workspace 'tmp/phase5-development'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [IO.Directory]::CreateDirectory($Scratch).FullName
$cache = Join-Path $Scratch 'repository'
$logs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$prepared = Join-Path $Scratch 'prepared.json'
$classpath = Join-Path $Scratch 'b21-classpath.txt'
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'

Push-Location -LiteralPath $repo
try {
    if ($Action -in 'Build', 'Compile') {
        if (!(Test-Path -LiteralPath $cache)) {
            Copy-Item -LiteralPath (Join-Path $phase 'wave2-integrated/repository') -Destination $cache -Recurse
        }
        & './fixtures/postgresql/b32/Run-B32.ps1' -Action $Action -Workspace $Workspace -Scratch $Scratch
        $runtime = (Get-Content -Raw -LiteralPath $prepared | ConvertFrom-Json).runtime
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        foreach ($artifact in ($runtime -split ';')) {
            $archive = [IO.Compression.ZipFile]::OpenRead($artifact)
            try {
                if ($archive.Entries | Where-Object { $_.Name -match '^B21.*\.class$' }) {
                    throw 'B21 fixture code is present in a release artifact.'
                }
            } finally {
                $archive.Dispose()
            }
        }
        'PASS no B21 fixture classes in the profile release classpath' |
            Set-Content -LiteralPath (Join-Path $logs 'b21-artifact-scan.log')
        $spring = (Resolve-Path -LiteralPath (Join-Path $cache 'org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar')).Path
        $jdbcTests = (Resolve-Path -LiteralPath 'adapter-jdbc/target/test-classes').Path
        $fixtureRuntime = "$runtime;$spring;$jdbcTests"
        $launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch ('b21-launcher-' + [guid]::NewGuid()))).FullName
        $sources = @('QualifyPostgresql.java','B10FixtureSources.java','B10Postgresql.java','B08MvcSession.java',
            'B08HttpSession.java','B08Postgresql.java','B11Postgresql.java','B12Postgresql.java','B13Postgresql.java',
            'B14Postgresql.java','B15Postgresql.java','B16Postgresql.java','B17Postgresql.java','B19Postgresql.java',
            'B20Postgresql.java','B22Postgresql.java','b21/B21CursorProbe.java','b21/B21Postgresql.java') |
            ForEach-Object { Join-Path 'fixtures/postgresql' $_ }
        $log = Join-Path $logs "$RunId-compile.log"
        & $javac -encoding UTF-8 -classpath $fixtureRuntime -d $launcher @sources *> $log
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $log
            throw 'Private browse fixture compilation failed.'
        }
        "$launcher;$fixtureRuntime" | Set-Content -LiteralPath $classpath
        return
    }

    $build = Get-Content -Raw -LiteralPath $prepared | ConvertFrom-Json
    if ($build.repository -ne $repo) { throw 'Use the exact checkout that prepared this scratch directory.' }
    $class = if ($Action -eq 'B08Mvc') { 'B08Postgresql' } else { "${Action}Postgresql" }
    $ticket = switch ($Action) {
        'B08Mvc' { 'b08' }
        'B22' { 'b17' }
        default { $Action.ToLowerInvariant() }
    }
    $nativeRun = "$ticket-$($RunId.Substring(4))"
    $runtime = (Get-Content -Raw -LiteralPath $classpath).Trim()
    $log = Join-Path $logs "$RunId-$($Action.ToLowerInvariant()).log"
    if (Test-Path -LiteralPath $log) { throw 'Use a new run ID for each check.' }
    & $java "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Drequests.b08.http.runtimeClasspath=$($build.runtime)" -cp $runtime $class run $bin $runs $nativeRun *> $log
    $exit = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($exit -ne 0) { throw "$Action fixture failed." }
} finally {
    Pop-Location
}
