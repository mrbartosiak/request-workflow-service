[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('Build', 'Compile', 'Leak', 'Stalled', 'StallCleanupRed', 'Run', 'Cleanup')]
    [string] $Action,
    [Parameter(Mandatory)] [string] $Workspace,
    [Parameter(Mandatory)] [string] $Scratch,
    [ValidatePattern('^b32-[a-z0-9-]{1,27}$')] [string] $RunId = 'b32-local'
)

$ErrorActionPreference = 'Stop'
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$execution = Join-Path $Workspace 'tmp/phase5-development'
$env:JAVA_HOME = (Resolve-Path -LiteralPath (Join-Path $execution 'b01-tools/temurin/jdk-25.0.5.1+2')).Path
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin/jar.exe'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$settings = Join-Path $execution 'b01-tools/m2/settings.xml'
$bin = Join-Path $execution 'b04-fixture/runtime/pgsql/bin'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Scratch = [System.IO.Directory]::CreateDirectory($Scratch).FullName
$logs = [System.IO.Directory]::CreateDirectory((Join-Path $Scratch 'logs')).FullName
$runs = [System.IO.Directory]::CreateDirectory((Join-Path $Scratch 'runs')).FullName
$cache = Join-Path $Scratch 'repository'
$manifest = Join-Path $Scratch 'prepared.json'
$mavenOptions = @('-o', '-B', '-C', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$cache")

function Invoke-Verify($name, [string[]]$profile) {
    & $maven @mavenOptions @profile clean verify *> (Join-Path $logs "$name-verify.log")
    if ($LASTEXITCODE -ne 0) { throw "$name offline clean verify failed." }
}

function Get-RuntimeJars($node) {
    foreach ($dependency in $node.children) {
        if ($dependency.scope -notin 'compile', 'runtime') { continue }
        if ($dependency.type -ne 'jar' -or $dependency.classifier) { throw 'Unexpected runtime artifact packaging.' }
        $file = "$($dependency.artifactId)-$($dependency.version).jar"
        if ($dependency.groupId -eq 'com.company.requests') {
            (Resolve-Path -LiteralPath "$($dependency.artifactId)/target/$file").Path
        } else {
            $group = $dependency.groupId.Replace('.', '/')
            (Resolve-Path -LiteralPath (Join-Path $cache "$group/$($dependency.artifactId)/$($dependency.version)/$file")).Path
        }
        Get-RuntimeJars $dependency
    }
}

function Get-PackagedApp {
    $graph = Get-Content -Raw -LiteralPath 'bootstrap/target/requests-dependency-tree.json' | ConvertFrom-Json
    (Resolve-Path -LiteralPath "bootstrap/target/bootstrap-$($graph.version).jar").Path
    Get-RuntimeJars $graph
}

function Confirm-PackagedApp([string[]] $artifacts, [bool] $http, [string] $name) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $listener = $false
    foreach ($artifact in $artifacts) {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($artifact)
        try {
            foreach ($member in $archive.Entries) {
                if ($member.Name -match '^(B32|B08MvcSession|B08Postgresql|QualifyPostgresql)') {
                    throw 'Fixture class is present in a packaged artifact.'
                }
                if ($member.FullName -eq 'com/company/requests/bootstrap/SyntheticHttpMain.class') { $listener = $true }
            }
        } finally {
            $archive.Dispose()
        }
    }
    if ($listener -ne $http) { throw 'Unexpected B08 listener packaging.' }
    "PASS $name checked $($artifacts.Count) packaged JARs, no fixture classes, B08 listener=$listener" |
        Set-Content -LiteralPath (Join-Path $logs "$name-artifact-scan.log")
}

function Test-StallCompletion([int] $exit, [string] $log) {
    return ($exit -eq 3 -and
        (Select-String -LiteralPath $log -Pattern '^B32 EXPECTED STALL VERIFIED AND CLEAN TEARDOWN COMPLETE$'))
}

Push-Location -LiteralPath $repo
try {
    if ($Action -in 'Build', 'Compile') {
        if (-not (Test-Path -LiteralPath $cache)) {
            Copy-Item -LiteralPath (Join-Path $execution 'b08-http-integrated/repository') -Destination $cache -Recurse
        }
        if ($Action -eq 'Build') {
            Invoke-Verify 'default' @()
            Confirm-PackagedApp @(Get-PackagedApp | Select-Object -Unique) $false 'default'
            Invoke-Verify 'profile' @('-Psynthetic-http')
        }
        $launcher = [System.IO.Directory]::CreateDirectory((Join-Path $Scratch ('launcher-' + [guid]::NewGuid()))).FullName
        $databasePath = Join-Path $launcher 'postgres-classpath.txt'
        $jdbcOptions = @('-f', 'fixtures/postgresql/pom.xml',
            'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath',
            '-DincludeScope=runtime', "-Dmdep.outputFile=$databasePath")
        & $maven @mavenOptions @jdbcOptions *> (Join-Path $logs 'resolve-jdbc.log')
        if ($LASTEXITCODE -ne 0) { throw 'Qualified JDBC classpath resolution failed.' }
        $database = (Get-Content -Raw -LiteralPath $databasePath).Trim() -split ';'
        $artifacts = @( @(Get-PackagedApp) + $database | Select-Object -Unique )
        Confirm-PackagedApp $artifacts $true 'profile'
        $runtime = $artifacts -join ';'
        $sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' -File |
                Sort-Object Name | ForEach-Object FullName)
        & $javac -encoding UTF-8 -classpath $runtime -d $launcher fixtures/postgresql/QualifyPostgresql.java @sources
        if ($LASTEXITCODE -ne 0) { throw 'B32 fixture compilation failed.' }
        Get-FileHash -Algorithm SHA256 -LiteralPath $artifacts |
            Select-Object Hash, Path | Export-Csv -NoTypeInformation -LiteralPath (Join-Path $logs 'runtime-sha256.csv')
        @{ runtime = $runtime; launcher = $launcher; repository = $repo } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest
        return
    }
    $built = Get-Content -Raw -LiteralPath $manifest | ConvertFrom-Json
    if ($built.repository -ne $repo) { throw 'Use the exact checkout that prepared this scratch directory.' }
    $classpath = "$($built.launcher);$($built.runtime)"
    if ($Action -eq 'Leak') {
        $leak = Join-Path $logs "$RunId-injected-fixture.jar"
        if (Test-Path -LiteralPath $leak) { throw 'Use a new B32 run ID.' }
        & $jar --create --file $leak -C $built.launcher com/company/requests/bootstrap/B32Gate.class
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the private leak control.' }
        $log = Join-Path $logs "$RunId-leak.log"
        & $java -cp $classpath B32Harness scan "$($built.runtime);$leak" *> $log
        $exit = $LASTEXITCODE
        Get-Content -LiteralPath $log
        if ($exit -ne 1 -or -not (Select-String -LiteralPath $log -SimpleMatch 'Fixture control found on the release classpath.')) {
            throw 'Release artifact leak control failed to detect the fixture class.'
        }
        Write-Output 'PASS release artifact leak control rejected the injected fixture class with exit 1.'
        return
    }
    if ($Action -eq 'StallCleanupRed') {
        $log = Join-Path $logs "$RunId-stall-cleanup-red.log"
        if (Test-Path -LiteralPath $log) { throw 'Use a new B32 run ID.' }
        & $java -cp $classpath B32Harness stall-cleanup-red *> $log
        $exit = $LASTEXITCODE
        Get-Content -LiteralPath $log
        if ($exit -ne 1 -or (Test-StallCompletion $exit $log) -or
            -not (Select-String -LiteralPath $log -SimpleMatch 'B32 fixture barrier timed out; discarding run.') -or
            -not (Select-String -LiteralPath $log -SimpleMatch 'B32 additional failure (IOException)')) {
            throw 'Compound stall and cleanup failure control did not exercise the failure classification.'
        }
        Write-Output 'PASS compound stall and cleanup failure rejected.'
        return
    }
    $actionName = $Action.ToLowerInvariant()
    $log = Join-Path $logs "$RunId-$actionName.log"
    if (Test-Path -LiteralPath $log) { throw 'Use a new B32 run ID.' }
    & $java "-Drequests.b32.runtime=$($built.runtime)" -cp $classpath B32Harness $actionName $bin $runs $RunId *> $log
    $exit = $LASTEXITCODE
    Get-Content -LiteralPath $log
    if ($Action -eq 'Stalled') {
        if (-not (Test-StallCompletion $exit $log)) {
            throw 'Expected stalled barrier and clean teardown were not both confirmed.'
        }
        Write-Output 'PASS stalled B32 fixture run was discarded after clean teardown.'
    } elseif ($exit -ne 0) {
        throw "B32 $actionName failed, see $log."
    }
} finally {
    Pop-Location
}
