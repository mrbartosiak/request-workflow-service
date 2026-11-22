[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b23-[a-z0-9-]{1,27}$')] [string] $RunId = 'b23-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$phase = Join-Path $Workspace 'tmp/phase5-development'
$checkout = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [IO.Directory]::CreateDirectory($Scratch).FullName
$logs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$cache = Join-Path $Scratch 'repository'
$env:JAVA_HOME = (Resolve-Path -LiteralPath (Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2')).Path
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$manifest = Join-Path $Scratch 'b23-prepared.json'

Push-Location -LiteralPath $checkout
try {
    $log = Join-Path $logs "$RunId-$($Action.ToLowerInvariant()).log"
    if ($Action -in 'Build', 'Compile') {
        if (!(Test-Path -LiteralPath $cache)) {
            Copy-Item -LiteralPath (Join-Path $phase 'wave2-integrated/repository') -Destination $cache -Recurse
        }
        & './fixtures/postgresql/b32/Run-B32.ps1' -Action $Action -Workspace $Workspace -Scratch $Scratch
        $built = Get-Content -Raw -LiteralPath (Join-Path $Scratch 'prepared.json') | ConvertFrom-Json
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        foreach ($artifact in ($built.runtime -split ';')) {
            $archive = [System.IO.Compression.ZipFile]::OpenRead($artifact)
            try {
                if ($archive.Entries | Where-Object { $_.Name -match '^B23.*\.class$' }) {
                    throw 'B23 fixture code is present in a release artifact.'
                }
            } finally {
                $archive.Dispose()
            }
        }
        'PASS no B23 fixture classes in the profile release classpath' |
            Set-Content -LiteralPath (Join-Path $logs 'b23-artifact-scan.log')
        $spring = (Resolve-Path -LiteralPath (Join-Path $cache 'org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar')).Path
        $classpath = "$($built.runtime);$spring"
        $launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch ('b23-launcher-' + [guid]::NewGuid()))).FullName
        $source = @('QualifyPostgresql.java', 'B08MvcSession.java', 'B10FixtureSources.java', 'B10Postgresql.java',
            'B17Postgresql.java', 'b23/B23WorkerTrace.java', 'b23/B23Postgresql.java') |
            ForEach-Object { Join-Path 'fixtures/postgresql' $_ }
        & $javac -encoding UTF-8 -classpath $classpath -d $launcher @source *> $log
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $log
            throw 'B23 private fixture compilation failed.'
        }
        @{ runtime = "$launcher;$classpath"; checkout = $checkout } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.checkout -ne $checkout) { throw 'Use the exact checkout that prepared this fixture.' }
    if (Test-Path -LiteralPath $log) { throw 'Use a new B23 run ID.' }
    $options = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $built.runtime, 'B23Postgresql',
        $Action.ToLowerInvariant(), $bin, $runs, $RunId)
    & $java @options *> $log
    $result = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($result -ne 0) { throw "B23 fixture process failed. See $log." }
} finally {
    Pop-Location
}
