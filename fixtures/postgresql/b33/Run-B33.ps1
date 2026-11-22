[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Controls', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b33-[a-z0-9-]{1,23}$')] [string] $RunId = 'b33-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$execution = Join-Path $Workspace 'tmp/phase5-development'
$jdk = (Resolve-Path -LiteralPath (Join-Path $execution 'b01-tools/temurin/jdk-25.0.5.1+2')).Path
$java = Join-Path $jdk 'bin/java.exe'
$javac = Join-Path $jdk 'bin/javac.exe'
$bin = Join-Path $execution 'b04-fixture/runtime/pgsql/bin'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [System.IO.Directory]::CreateDirectory($Scratch).FullName
$logs = [System.IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [System.IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$manifest = Join-Path $Scratch 'b33-prepared.json'

Push-Location -LiteralPath $repo
try {
    if ($Action -in 'Build','Compile') {
        if ($Action -eq 'Build' -or -not (Test-Path -LiteralPath (Join-Path $Scratch 'prepared.json'))) {
            & './fixtures/postgresql/b32/Run-B32.ps1' -Action $Action -Workspace $Workspace -Scratch $Scratch
        }
        $built = Get-Content -Raw -LiteralPath (Join-Path $Scratch 'prepared.json') | ConvertFrom-Json
        if ($built.repository -ne $repo) { throw 'The B32 build belongs to a different checkout.' }
        $launcher = [System.IO.Directory]::CreateDirectory((Join-Path $Scratch ('b33-launcher-' + [guid]::NewGuid()))).FullName
        $sources = @('fixtures/postgresql/B10FixtureSources.java','fixtures/postgresql/B10Postgresql.java')
        $sources += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File |
                Sort-Object Name | ForEach-Object FullName
        $compileLog = Join-Path $logs 'b33-compile.log'
        & $javac -encoding UTF-8 -classpath "$($built.launcher);$($built.runtime)" -d $launcher @sources *> $compileLog
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $compileLog
            throw 'B33 private fixture compilation failed.'
        }
        $classpath = "$launcher;$($built.launcher);$($built.runtime)"
        & $java -cp $classpath B33Harness scan $built.runtime *> (Join-Path $logs 'b33-profile-artifact-scan.log')
        if ($LASTEXITCODE -ne 0) { throw 'B33 profile artifact scan failed.' }
        @{ classpath = $classpath; runtime = $built.runtime; repository = $repo } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.repository -ne $repo) { throw 'Use the checkout that compiled this fixture.' }
    $mode = $Action.ToLowerInvariant()
    $log = Join-Path $logs "$RunId-$mode.log"
    if (Test-Path -LiteralPath $log) { throw 'The specified action already used this run ID.' }
    if ($Action -eq 'Controls') {
        & $java -cp $built.classpath B33Harness controls $built.runtime *> $log
    } else {
        & $java -cp $built.classpath B33Harness $mode $bin $runs $RunId $built.runtime *> $log
    }
    $status = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($status -ne 0) { throw "B33 $mode failed. See $log." }
} finally {
    Pop-Location
}
