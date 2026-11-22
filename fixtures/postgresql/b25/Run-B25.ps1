[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'PortFree', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b25-[a-z0-9-]{1,24}$')] [string] $RunId = 'b25-local'
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
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$mavenOptions = @('-o', '-B', '-C', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$cache")
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$manifest = Join-Path $Scratch 'b25-prepared.json'

function Confirm-Isolation([string] $profile) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $artifacts = @(Get-ChildItem -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } |
        ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'target') -Filter '*.jar' -File } |
        Select-Object -ExpandProperty FullName)
    if ($artifacts.Count -ne 10) { throw "Expected ten reactor JARs for $profile." }
    $migration = $false
    foreach ($artifact in $artifacts) {
        $archive = [IO.Compression.ZipFile]::OpenRead($artifact)
        try {
            if ($archive.Entries.Name -match '^B25.*[.]class$') { throw "Private B25 class leaked into $profile." }
            if ($archive.Entries.FullName -contains 'db/integration/outbox_scheduling_v1.sql') { $migration = $true }
        } finally {
            $archive.Dispose()
        }
    }
    if (!$migration) { throw "Scheduling migration missing from $profile." }
    "PASS $profile scanned $($artifacts.Count) JARs, scheduling migration present and private B25 classes absent." |
        Set-Content -LiteralPath (Join-Path $logs "$profile-artifact-scan.log")
}

Push-Location -LiteralPath $checkout
try {
    $log = Join-Path $logs "$RunId-$($Action.ToLowerInvariant()).log"
    if ($Action -in 'Build', 'Compile') {
        if (!(Test-Path -LiteralPath $cache)) {
            Copy-Item -LiteralPath (Join-Path $phase 'wave2-integrated/repository') -Destination $cache -Recurse
        }
        if ($Action -eq 'Build') {
            & $maven @mavenOptions clean verify *> (Join-Path $logs 'default-verify.log')
            if ($LASTEXITCODE) { throw 'Default strict offline Maven build failed.' }
            Confirm-Isolation 'default'
            & $maven @mavenOptions -Psynthetic-http clean verify *> (Join-Path $logs 'profile-verify.log')
            if ($LASTEXITCODE) { throw 'Synthetic profile strict offline Maven build failed.' }
        }
        & './fixtures/postgresql/b32/Run-B32.ps1' -Action Compile -Workspace $Workspace -Scratch $Scratch
        $built = Get-Content -Raw -LiteralPath (Join-Path $Scratch 'prepared.json') | ConvertFrom-Json
        Confirm-Isolation 'synthetic-http'
        $spring = (Resolve-Path -LiteralPath (Join-Path $cache 'org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar')).Path
        $classpath = "$($built.runtime);$spring"
        $launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch ('b25-launcher-' + [guid]::NewGuid()))).FullName
        $privateFiles = Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File |
            Sort-Object Name | Select-Object -ExpandProperty FullName
        $shared = @('QualifyPostgresql.java', 'B08MvcSession.java', 'B10FixtureSources.java', 'B10Postgresql.java',
            'B17Postgresql.java') | ForEach-Object { Join-Path 'fixtures/postgresql' $_ }
        $shared += Get-ChildItem -LiteralPath 'fixtures/postgresql/b32', 'fixtures/postgresql/b33' -Filter '*.java' -File |
            Sort-Object FullName | Select-Object -ExpandProperty FullName
        & $javac -encoding UTF-8 -classpath $classpath -d $launcher @shared @privateFiles *> $log
        if ($LASTEXITCODE) {
            Get-Content -LiteralPath $log
            throw 'Private B25 launcher failed to compile.'
        }
        $inputs = @($classpath -split ';') + @($shared) + @($privateFiles)
        $hashes = @(Get-FileHash -Algorithm SHA256 -LiteralPath $inputs | Select-Object Hash, Path)
        @{ runtime = "$launcher;$classpath"; checkout = $checkout; inputs = $hashes } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        'PASS private B25 launchers compiled against the synthetic runtime packages.'
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.checkout -ne $checkout) { throw 'Use the exact checkout that prepared this fixture.' }
    if (Test-Path -LiteralPath $log) { throw 'Use a fresh run ID.' }
    if ($Action -ne 'Cleanup') {
        foreach ($inputFile in $built.inputs) {
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $inputFile.Path).Hash -ne $inputFile.Hash) {
                throw 'The prepared fixture no longer matches its inputs.'
            }
        }
    }
    $options = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $built.runtime)
    if ($Action -eq 'PortFree') {
        & $java @options 'B25PortFree' *> $log
        $exit = $LASTEXITCODE
        if ($exit -eq 0) {
            & $java @options 'B25Occupancy' *>> $log
            $exit = $LASTEXITCODE
        }
    } else {
        $options += @('B25Postgresql', $Action.ToLowerInvariant(), $bin, $runs, "b23-$RunId")
        & $java @options *> $log
        $exit = $LASTEXITCODE
    }
    Get-Content -LiteralPath $log
    if ($exit) { throw "Private B25 fixture failed. See $log." }
} finally {
    Pop-Location
}
