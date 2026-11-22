[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Verify', 'Compile', 'Run', 'Cleanup')] [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b18-[a-z0-9-]{1,27}$')] [string] $RunId = 'b18-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$phase = Join-Path $Workspace 'tmp/phase5-development'
$checkout = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [IO.Directory]::CreateDirectory($Scratch).FullName
$logs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$launcher = [IO.Directory]::CreateDirectory((Join-Path $Scratch 'b18-launcher')).FullName
$cache = Join-Path $Scratch 'repository'
$env:JAVA_HOME = (Resolve-Path -LiteralPath (Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2')).Path
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$manifest = Join-Path $Scratch 'b18-prepared.json'

Push-Location -LiteralPath $checkout
try {
    $log = Join-Path $logs "$RunId-$($Action.ToLowerInvariant()).log"
    if ($Action -eq 'Verify') {
        if (!(Test-Path -LiteralPath $cache)) {
            Copy-Item -LiteralPath (Join-Path $phase 'wave2-integrated/repository') -Destination $cache -Recurse
        }
        & $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" -Psynthetic-http clean verify *> $log
        $result = $LASTEXITCODE
        Get-Content -LiteralPath $log -Tail 45
        if ($result -ne 0) { throw 'Strict offline B18 reactor verification failed.' }
        return
    }
    if ($Action -eq 'Compile') {
        & './fixtures/postgresql/b32/Run-B32.ps1' -Action Compile -Workspace $Workspace -Scratch $Scratch
        $built = Get-Content -Raw -LiteralPath (Join-Path $Scratch 'prepared.json') | ConvertFrom-Json
        $springTest = (Resolve-Path -LiteralPath (Join-Path $cache 'org/springframework/spring-test/7.0.11/spring-test-7.0.11.jar')).Path
        $classpath = "$($built.runtime);$springTest"
        $source = @('QualifyPostgresql.java', 'B08MvcSession.java', 'B10FixtureSources.java', 'B10Postgresql.java',
            'b18/B18Postgresql.java') | ForEach-Object { Join-Path 'fixtures/postgresql' $_ }
        & $javac -encoding UTF-8 -classpath $classpath -d $launcher @source *> $log
        $result = $LASTEXITCODE
        Get-Content -LiteralPath $log
        if ($result -ne 0) { throw 'B18 private fixture compilation failed.' }
        @{ runtime = "$launcher;$classpath"; checkout = $checkout } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.checkout -ne $checkout) { throw 'Use the exact checkout that prepared this fixture.' }
    if (Test-Path -LiteralPath $log) { throw 'Use a new B18 run ID.' }
    & $java -cp $built.runtime B18Postgresql ($Action.ToLowerInvariant()) $bin $runs $RunId *> $log
    $result = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($result -ne 0) { throw "B18 fixture process failed. See $log." }
} finally {
    Pop-Location
}
