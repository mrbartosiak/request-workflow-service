[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $TreeRoot,
    [Parameter(Mandatory)] [ValidateSet('default', 'synthetic-http')] [string] $Profile,
    [Parameter(Mandatory)] [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
$TreeRoot = (Resolve-Path -LiteralPath $TreeRoot).Path
$rootPom = [xml] (Get-Content -LiteralPath (Join-Path $TreeRoot 'pom.xml') -Raw)
$modulePaths = @('.') + @($rootPom.project.modules.module)
$reactorGroup = $rootPom.project.groupId
$reactorVersion = $rootPom.project.version
$reactorArtifacts = @{}

foreach ($modulePath in $modulePaths) {
    $pomPath = Join-Path $TreeRoot "$modulePath/pom.xml"
    $pom = [xml] (Get-Content -LiteralPath $pomPath -Raw)
    $artifact = [string] $pom.project.artifactId
    if (-not $artifact -or $reactorArtifacts.ContainsKey($artifact)) {
        throw "Missing or duplicate reactor artifact in $modulePath/pom.xml"
    }
    $reactorArtifacts.Add($artifact, $modulePath)
}

$rows = [System.Collections.Generic.List[string]]::new()
$treeHashes = [System.Collections.Generic.List[string]]::new()
$external = @{}
$ordinal = [StringComparer]::Ordinal

function Add-DependencyNode($node, [string] $module, [string[]] $ancestors) {
    foreach ($field in 'groupId', 'artifactId', 'version', 'type', 'scope', 'optional') {
        if ([string] $node[$field] -notmatch '^[0-9a-zA-Z_.+\-]+$') {
            throw "Missing or invalid Maven $field in the $module tree."
        }
    }
    if (-not $node.Contains('classifier') -or [string] $node.classifier -notmatch '^[0-9a-zA-Z_.+\-]*$') {
        throw "Missing or invalid classifier in the $module tree."
    }
    $coordinate = "$($node.groupId):$($node.artifactId):$($node.version)"
    $isReactor = $node.groupId -ceq $reactorGroup -and
            $node.version -ceq $reactorVersion -and $reactorArtifacts.ContainsKey($node.artifactId)
    $kind = if ($isReactor) { 'reactor' } else { 'external' }
    $relation = if ($ancestors.Count -eq 0) { 'direct' } else { 'transitive' }
    $path = $ancestors -join ' > '
    $rows.Add((@($Profile, $module, $relation, $kind, $coordinate, $node.type, $node.classifier,
            $node.scope, $node.optional, $path) -join "`t"))

    if (-not $isReactor) {
        $key = @($coordinate, $node.type, $node.classifier) -join "`t"
        if (-not $external.ContainsKey($key)) {
            $external[$key] = @{
                Direct = $false
                Transitive = $false
                Scopes = [System.Collections.Generic.SortedSet[string]]::new($ordinal)
            }
        }
        $external[$key][$relation] = $true
        $null = $external[$key].Scopes.Add($node.scope)
    }

    $pathSegment = "$coordinate/$($node.type)/$($node.classifier)/$($node.scope)"
    foreach ($child in $node.children) {
        Add-DependencyNode $child $module ($ancestors + $pathSegment)
    }
}

foreach ($module in $reactorArtifacts.Keys) {
    $modulePath = $reactorArtifacts[$module]
    $tree = Join-Path $TreeRoot "$modulePath/target/requests-dependency-tree.json"
    if (-not (Test-Path -LiteralPath $tree -PathType Leaf)) {
        throw "Missing Maven dependency tree for $module ($Profile)."
    }
    $treeHash = (Get-FileHash -LiteralPath $tree -Algorithm SHA256).Hash.ToLowerInvariant()
    $treeHashes.Add("$Profile`t$module`t$treeHash")
    $root = Get-Content -Raw -LiteralPath $tree | ConvertFrom-Json -AsHashtable
    if ($root.groupId -cne $reactorGroup -or $root.artifactId -cne $module -or
            $root.version -cne $reactorVersion) {
        throw "Unexpected Maven dependency tree root for $module ($Profile)."
    }
    foreach ($child in $root.children) {
        Add-DependencyNode $child $module @()
    }
}

$output = [System.IO.Directory]::CreateDirectory($OutputDirectory).FullName
function Write-Tsv([string] $name, [string] $header, [string[]] $lines) {
    [Array]::Sort($lines, $ordinal)
    $destination = Join-Path $output "$Profile-$name.tsv"
    [IO.File]::WriteAllText($destination, ((@($header) + $lines) -join "`n") + "`n")
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "$Profile $name rows=$($lines.Count) sha256=$hash"
}

$externalRows = foreach ($key in $external.Keys) {
    $entry = $external[$key]
    @($Profile, $key, $entry.Direct.ToString().ToLowerInvariant(),
            $entry.Transitive.ToString().ToLowerInvariant(), ($entry.Scopes -join ',')) -join "`t"
}

Write-Tsv 'resolved' "profile`tmodule`trelation`tkind`tcoordinate`ttype`tclassifier`tscope`toptional`tancestors" $rows.ToArray()
Write-Tsv 'external' "profile`tcoordinate`ttype`tclassifier`tdirect`ttransitive`tscopes" $externalRows
Write-Tsv 'tree-inputs' "profile`tmodule`tsha256" $treeHashes.ToArray()
