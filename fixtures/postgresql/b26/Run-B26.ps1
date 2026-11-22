[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b26-[a-z0-9-]{1,27}$')] [string] $RunId = 'b26-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$execution = Join-Path $Workspace 'tmp/phase5-development'
$checkout = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [IO.Directory]::CreateDirectory($Scratch).FullName
$logs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$env:JAVA_HOME = (Resolve-Path -LiteralPath (Join-Path $execution 'b01-tools/temurin/jdk-25.0.5.1+2')).Path
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$bin = Join-Path $execution 'b04-fixture/runtime/pgsql/bin'
$manifest = Join-Path $Scratch 'b26-prepared.json'

Push-Location -LiteralPath $checkout
try {
    $log = Join-Path $logs "$RunId-$($Action.ToLowerInvariant()).log"
    if ($Action -in 'Build', 'Compile') {
        $cache = Join-Path $Scratch 'repository'
        if (!(Test-Path -LiteralPath $cache)) {
            Copy-Item -LiteralPath (Join-Path $execution 'wave2-integrated/repository') -Destination $cache -Recurse
        }
        & './fixtures/postgresql/b32/Run-B32.ps1' -Action $Action -Workspace $Workspace -Scratch $Scratch
        $built = Get-Content -Raw -LiteralPath (Join-Path $Scratch 'prepared.json') | ConvertFrom-Json
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        foreach ($artifact in ($built.runtime -split ';')) {
            $archive = [System.IO.Compression.ZipFile]::OpenRead($artifact)
            try {
                if ($archive.Entries | Where-Object { $_.Name -match '^B26.*\.class$' }) {
                    throw 'B26 fixture code is present in a release artifact.'
                }
            } finally {
                $archive.Dispose()
            }
        }
        'PASS no B26 fixture classes on the packaged profile classpath' |
            Set-Content -LiteralPath (Join-Path $logs 'b26-artifact-scan.log')
        $launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch ('b26-launcher-' + [guid]::NewGuid()))).FullName
        $fixture = 'fixtures/postgresql'
        $source = @('QualifyPostgresql.java', 'B10FixtureSources.java', 'B10Postgresql.java',
                    'b26/B26CommitCut.java', 'b26/B26Postgresql.java') | ForEach-Object { Join-Path $fixture $_ }
        & $javac -encoding UTF-8 -classpath $built.runtime -d $launcher @source *> $log
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $log
            throw 'B26 private fixture compilation failed.'
        }
        @{ runtime = "$launcher;$($built.runtime)"; checkout = $checkout } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.checkout -ne $checkout) { throw 'Use the exact checkout that prepared this B26 fixture.' }
    if (Test-Path -LiteralPath $log) { throw 'Use a new B26 run ID.' }
    $options = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $built.runtime,
        'B26Postgresql', $Action.ToLowerInvariant(), $bin, $runs, $RunId)
    & $java @options *> $log
    $result = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($result -ne 0) { throw "B26 fixture failed. See $log." }
} finally {
    Pop-Location
}
