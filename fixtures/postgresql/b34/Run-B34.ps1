[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Controls', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b34-[a-z0-9-]{1,23}$')] [string] $RunId = 'b34-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$execution = Join-Path $Workspace 'tmp/phase5-development'
$jdk = (Resolve-Path -LiteralPath (Join-Path $execution 'b01-tools/temurin/jdk-25.0.5.1+2')).Path
$java = Join-Path $jdk 'bin/java.exe'
$javac = Join-Path $jdk 'bin/javac.exe'
$bin = Join-Path $execution 'b04-fixture/runtime/pgsql/bin'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [IO.Directory]::CreateDirectory($Scratch).FullName
$logs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$manifest = Join-Path $Scratch 'b34-prepared.json'
$b33 = Join-Path $Scratch 'b33-prepared.json'

Push-Location -LiteralPath $repo
try {
    if ($Action -in 'Build','Compile') {
        if ($Action -eq 'Build' -or !(Test-Path -LiteralPath $b33)) {
            & './fixtures/postgresql/b33/Run-B33.ps1' -Action $Action -Workspace $Workspace -Scratch $Scratch
        }
        $built = Get-Content -Raw -LiteralPath $b33 | ConvertFrom-Json
        if ($built.repository -ne $repo) { throw 'Use the checkout that prepared the inherited fixture.' }
        $spring = (Resolve-Path -LiteralPath (Join-Path $Scratch 'repository/org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar')).Path
        $launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch ('b34-launcher-' + [guid]::NewGuid()))).FullName
        $sources = @('fixtures/postgresql/B08MvcSession.java','fixtures/postgresql/B17Postgresql.java')
        $sources += Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File | Sort-Object Name | ForEach-Object FullName
        $compileLog = Join-Path $logs 'b34-compile.log'
        $runtime = "$($built.classpath);$spring"
        & $javac -encoding UTF-8 -classpath $runtime -d $launcher @sources *> $compileLog
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $compileLog
            throw 'B34 private fixture compilation failed.'
        }
        @{ classpath = "$launcher;$runtime"; runtime = $built.runtime; repository = $repo } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.repository -ne $repo) { throw 'Use the checkout that compiled this fixture.' }
    $mode = $Action.ToLowerInvariant()
    $log = Join-Path $logs "$RunId-$mode.log"
    if (Test-Path -LiteralPath $log) { throw 'The specified action already used this run ID.' }
    if ($Action -eq 'Controls') {
        & $java -cp $built.classpath B34Harness controls $built.runtime *> $log
    } else {
        & $java -cp $built.classpath B34Harness $mode $bin $runs $RunId $built.runtime *> $log
    }
    $status = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($status -ne 0) { throw "B34 $mode failed. See $log." }
} finally {
    Pop-Location
}
