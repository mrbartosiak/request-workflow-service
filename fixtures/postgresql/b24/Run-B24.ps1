[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b24-[a-z0-9-]{1,24}$')] [string] $RunId = 'b24-local'
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
$manifest = Join-Path $Scratch 'b24-prepared.json'

function Confirm-Isolation([string[]] $artifacts, [string] $profile) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($artifact in $artifacts) {
        $archive = [IO.Compression.ZipFile]::OpenRead($artifact)
        try {
            if ($archive.Entries | Where-Object { $_.Name -match '^B24.*\.class$' }) {
                throw "Private B24 fixture class found in the $profile package."
            }
        } finally {
            $archive.Dispose()
        }
    }
    "PASS $profile scanned $($artifacts.Count) packages, no private B24 fixture classes" |
        Set-Content -LiteralPath (Join-Path $logs "b24-$profile-artifact-scan.log")
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
            if ($LASTEXITCODE -ne 0) { throw 'Default strict offline Maven build failed.' }
            $default = @(Get-ChildItem -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } |
                ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'target') -Filter '*.jar' -File } |
                Select-Object -ExpandProperty FullName)
            Confirm-Isolation $default 'default'
            & $maven @mavenOptions -Psynthetic-http clean verify *> (Join-Path $logs 'profile-verify.log')
            if ($LASTEXITCODE -ne 0) { throw 'Synthetic profile strict offline Maven build failed.' }
        }
        & './fixtures/postgresql/b32/Run-B32.ps1' -Action Compile -Workspace $Workspace -Scratch $Scratch
        $built = Get-Content -Raw -LiteralPath (Join-Path $Scratch 'prepared.json') | ConvertFrom-Json
        Confirm-Isolation ($built.runtime -split ';') 'profile'
        $spring = (Resolve-Path -LiteralPath (Join-Path $cache 'org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar')).Path
        $classpath = "$($built.runtime);$spring"
        $launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch ('b24-launcher-' + [guid]::NewGuid()))).FullName
        $source = @('QualifyPostgresql.java', 'B08MvcSession.java', 'B10FixtureSources.java', 'B10Postgresql.java',
            'B17Postgresql.java', 'b23/B23WorkerTrace.java', 'b23/B23Postgresql.java',
            'b24/B24RemoteDatabase.java', 'b24/B24Postgresql.java') |
            ForEach-Object { Join-Path 'fixtures/postgresql' $_ }
        & $javac -encoding UTF-8 -classpath $classpath -d $launcher @source *> $log
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $log
            throw 'Private B24 fixture compilation failed.'
        }
        @{ runtime = "$launcher;$classpath"; checkout = $checkout } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        Write-Output 'PASS private B24 and B23 fixtures compile against the synthetic runtime packages.'
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.checkout -ne $checkout) { throw 'Use the exact checkout that prepared this fixture.' }
    if (Test-Path -LiteralPath $log) { throw 'Use a fresh run ID.' }
    $options = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', $built.runtime, 'B24Postgresql',
        $Action.ToLowerInvariant(), $bin, $runs, "b23-$RunId")
    & $java @options *> $log
    $result = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($result -ne 0) { throw "Private B24 fixture failed. See $log." }
} finally {
    Pop-Location
}
