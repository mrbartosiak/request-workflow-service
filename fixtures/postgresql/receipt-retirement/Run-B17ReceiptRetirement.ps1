[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Baseline', 'Red', 'Green', 'Cleanup')]
    [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b17-[a-z0-9-]{1,27}$')] [string] $RunId = 'b17-receipt-retirement',
    [ValidateSet('Candidate', 'Baseline')] [string] $CleanupLane = 'Candidate'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$phase = Join-Path $Workspace 'tmp/phase5-development'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [IO.Directory]::CreateDirectory($Scratch).FullName
$baselineCommit = '2cbe78606476dab4467e3ca260f2b51f3cdad212'
$isBaseline = $Action -in 'Baseline', 'Red' -or ($Action -eq 'Cleanup' -and $CleanupLane -eq 'Baseline')
$lane = if ($isBaseline) { 'baseline' } else { 'candidate' }
$laneRoot = [IO.Directory]::CreateDirectory((Join-Path $Scratch $lane)).FullName
$logs = [IO.Directory]::CreateDirectory((Join-Path $laneRoot 'logs')).FullName
$runs = [IO.Directory]::CreateDirectory((Join-Path $laneRoot 'runs')).FullName
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$prepared = Join-Path $laneRoot 'retirement-prepared.json'

if ($Action -in 'Build', 'Compile', 'Baseline') {
    $sourceRepo = $repo
    if ($Action -eq 'Baseline') {
        $sourceRepo = [IO.Directory]::CreateDirectory((Join-Path $laneRoot 'source-2cbe786')).FullName
        $archive = Join-Path $laneRoot 'baseline-2cbe786.tar'
        & git -C $repo archive --format=tar "--output=$archive" $baselineCommit
        if ($LASTEXITCODE -ne 0) { throw 'Could not archive the exact B21 baseline.' }
        & tar -xf $archive -C $sourceRepo
        if ($LASTEXITCODE -ne 0) { throw 'Could not extract the exact B21 baseline.' }
    }

    $buildAction = if ($Action -eq 'Compile') { 'Compile' } else { 'Build' }
    $b21 = Join-Path $sourceRepo 'fixtures/postgresql/b21/Run-B21.ps1'
    & $b21 -Action $buildAction -Workspace $Workspace -Scratch $laneRoot -RunId b21-retirement
    $runtime = (Get-Content -Raw -LiteralPath (Join-Path $laneRoot 'b21-classpath.txt')).Trim()
    $build = Get-Content -Raw -LiteralPath (Join-Path $laneRoot 'prepared.json') | ConvertFrom-Json
    $launcher = [IO.Directory]::CreateDirectory((Join-Path $laneRoot ('retirement-launcher-' + [guid]::NewGuid()))).FullName
    $fixture = Join-Path $PSScriptRoot 'B17ReceiptRetirementPostgresql.java'
    $compile = Join-Path $logs "$RunId-retirement-compile.log"
    & $javac -encoding UTF-8 -classpath $runtime -d $launcher $fixture *> $compile
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $compile
        throw 'Private receipt retirement fixture compilation failed.'
    }

    $v3 = 0
    foreach ($artifact in ($build.runtime -split ';')) {
        $jar = [IO.Compression.ZipFile]::OpenRead($artifact)
        try {
            if ($jar.Entries | Where-Object { $_.Name -match '^B17ReceiptRetirementPostgresql.*\.class$' }) {
                throw 'Receipt retirement fixture code reached a runtime package.'
            }
            $v3 += @($jar.Entries | Where-Object {
                $_.FullName -eq 'db/workflow/publication_receipt_retirement_v3.sql'
            }).Count
        } finally { $jar.Dispose() }
    }
    $expected = if ($isBaseline) { 0 } else { 1 }
    if ($v3 -ne $expected) { throw 'Unexpected v3 migration count on the qualified package classpath.' }
    "PASS $lane packages have $v3 v3 migrations and zero private retirement fixture classes" |
        Set-Content -LiteralPath (Join-Path $logs 'retirement-package-scan.log')
    @{
        classpath = "$launcher;$runtime"
        repository = $repo
        baseline = if ($isBaseline) { $baselineCommit } else { $null }
        launcher = $launcher
    } | ConvertTo-Json -Compress | Set-Content -LiteralPath $prepared
    return
}

$built = Get-Content -Raw -LiteralPath $prepared | ConvertFrom-Json
if ($built.repository -ne $repo -or ($isBaseline -and $built.baseline -ne $baselineCommit)) {
    throw 'The receipt retirement build does not belong to this checkout and lane.'
}
$mode = if ($Action -eq 'Cleanup') { 'cleanup' } else { 'run' }
$log = Join-Path $logs "$RunId-$($Action.ToLowerInvariant()).log"
if (Test-Path -LiteralPath $log) { throw 'Use a new log for the selected action and run ID.' }
$red = if ($Action -eq 'Red') { 'true' } else { 'false' }
$javaArguments = @("-Drequests.b17.retirement.red=$red", '-cp', $built.classpath,
    'B17ReceiptRetirementPostgresql', $mode, $bin, $runs, $RunId)
& $java @javaArguments *> $log
$code = $LASTEXITCODE
Get-Content -LiteralPath $log
if ($code -ne 0) { throw 'Receipt retirement fixture failed.' }
