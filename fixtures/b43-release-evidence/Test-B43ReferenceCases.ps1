[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $InventoryPath,
    [Parameter(Mandatory)] [string] $JavaHome
)

$ErrorActionPreference = 'Stop'
$inventory = (Get-FileHash -LiteralPath $InventoryPath -Algorithm SHA256).Hash.ToLowerInvariant()
$asOf = [DateOnly]::new(2026, 11, 23)
$releaseId = 'b43-local-rehearsal'
$expectedSources = @('Spring Security Advisories', 'Snyk Security Database')

function Get-AdvisoryDisposition($review, [string] $inventoryDigest, [string] $release, [DateOnly] $date) {
    if (-not $inventoryDigest) { return 'block_missing_inventory' }
    if (-not $review -or -not $review.WeeklyOn -or -not $review.PrereleaseOn) {
        return 'block_missing_review'
    }
    if ($review.Inventory -cne $inventoryDigest -or $review.Release -cne $release) {
        return 'block_review_scope_mismatch'
    }
    if ($review.WeeklyOn -gt $date -or $review.WeeklyOn -lt $date.AddDays(-7) -or
            $review.PrereleaseOn -gt $date -or $review.PrereleaseOn -lt $review.WeeklyOn) {
        return 'block_stale_or_invalid_dates'
    }
    if (-not $review.Sources -or -not $review.Owner) { return 'block_incomplete_review' }
    foreach ($source in $expectedSources) {
        if ($review.Sources[$source] -ne 'complete') { return 'block_incomplete_review' }
    }

    $refer = $false
    $track = $false
    foreach ($item in $review.Findings) {
        if ($item.Severity -notin 'high', 'critical' -or -not $item.Applicable -or $item.Resolved) { continue }
        if ($item.Exposure -eq 'exposed') { return 'block_exposed_unresolved' }
        if ($item.Exposure -ne 'unexposed' -or -not $item.Evidence -or -not $item.Owner) {
            $refer = $true
            continue
        }
        $track = $true
    }
    if ($refer) { return 'refer_for_exposure_review' }
    if ($track) { return 'track_documented_unexposed' }
    return 'no_blocking_finding'
}

function Get-QualityDisposition([int] $complexity, [int] $observed, [int] $outcomes) {
    if ($complexity -lt 1 -or $observed -lt 0 -or $outcomes -lt 1 -or $observed -gt $outcomes) {
        throw 'Invalid reference quality counts.'
    }
    if ($complexity -gt 10 -and $observed * 10 -lt $outcomes * 9) { return 'escalate_both' }
    if ($complexity -gt 10) { return 'escalate_complexity' }
    if ($observed * 10 -lt $outcomes * 9) { return 'escalate_branch_outcomes' }
    return 'within_reference'
}

function Assert-Decision([string] $case, [string] $expected, [string] $actual) {
    if ($expected -cne $actual) { throw "$case expected $expected, received $actual." }
    Write-Output "$case expected=$expected actual=$actual PASS"
}

$complete = @{
    WeeklyOn = $asOf
    PrereleaseOn = $asOf
    Inventory = $inventory
    Release = $releaseId
    Owner = 'seeded review owner'
    Sources = @{ 'Spring Security Advisories' = 'complete'; 'Snyk Security Database' = 'complete' }
    Findings = @()
}

$stale = $complete.Clone()
$stale.WeeklyOn = $asOf.AddDays(-8)
$weeklyBoundary = $complete.Clone()
$weeklyBoundary.WeeklyOn = $asOf.AddDays(-7)
$missingPrerelease = $complete.Clone()
$missingPrerelease.PrereleaseOn = $null
$wrongInventory = $complete.Clone()
$wrongInventory.Inventory = '0' * 64
$wrongRelease = $complete.Clone()
$wrongRelease.Release = 'different-candidate'
$incomplete = $complete.Clone()
$incomplete.Sources = @{ 'Spring Security Advisories' = 'complete'; 'Snyk Security Database' = 'partial' }

$high = $complete.Clone()
$high.Findings = @(@{ Severity = 'high'; Applicable = $true; Resolved = $false; Exposure = 'exposed' })
$critical = $complete.Clone()
$critical.Findings = @(@{ Severity = 'critical'; Applicable = $true; Resolved = $false;
        Exposure = 'unexposed'; Evidence = 'seeded variant has no affected runtime facility'; Owner = 'seeded security owner' })
$unknown = $complete.Clone()
$unknown.Findings = @(@{ Severity = 'critical'; Applicable = $true; Resolved = $false; Exposure = 'unexposed' })
$highAndCritical = $complete.Clone()
$highAndCritical.Findings = @($critical.Findings) + @($high.Findings)
$highAndUnknown = $complete.Clone()
$highAndUnknown.Findings = @($unknown.Findings) + @($high.Findings)

Assert-Decision 'missing-inventory' 'block_missing_inventory' (Get-AdvisoryDisposition $complete '' $releaseId $asOf)
Assert-Decision 'missing-review' 'block_missing_review' (Get-AdvisoryDisposition $null $inventory $releaseId $asOf)
Assert-Decision 'missing-prerelease' 'block_missing_review' (Get-AdvisoryDisposition $missingPrerelease $inventory $releaseId $asOf)
Assert-Decision 'stale-weekly-review' 'block_stale_or_invalid_dates' (Get-AdvisoryDisposition $stale $inventory $releaseId $asOf)
Assert-Decision 'review-on-other-inventory' 'block_review_scope_mismatch' (Get-AdvisoryDisposition $wrongInventory $inventory $releaseId $asOf)
Assert-Decision 'review-on-other-release' 'block_review_scope_mismatch' (Get-AdvisoryDisposition $wrongRelease $inventory $releaseId $asOf)
Assert-Decision 'partial-source-review' 'block_incomplete_review' (Get-AdvisoryDisposition $incomplete $inventory $releaseId $asOf)
Assert-Decision 'exposed-unresolved-high' 'block_exposed_unresolved' (Get-AdvisoryDisposition $high $inventory $releaseId $asOf)
Assert-Decision 'unexposed-documented-critical' 'track_documented_unexposed' (Get-AdvisoryDisposition $critical $inventory $releaseId $asOf)
Assert-Decision 'unexposed-undocumented-critical' 'refer_for_exposure_review' (Get-AdvisoryDisposition $unknown $inventory $releaseId $asOf)
Assert-Decision 'exposed-high-with-tracked-critical' 'block_exposed_unresolved' (Get-AdvisoryDisposition $highAndCritical $inventory $releaseId $asOf)
Assert-Decision 'exposed-high-with-unknown-critical' 'block_exposed_unresolved' (Get-AdvisoryDisposition $highAndUnknown $inventory $releaseId $asOf)
Assert-Decision 'complete-zero-findings' 'no_blocking_finding' (Get-AdvisoryDisposition $complete $inventory $releaseId $asOf)
Assert-Decision 'weekly-review-at-seven-days' 'no_blocking_finding' (Get-AdvisoryDisposition $weeklyBoundary $inventory $releaseId $asOf)

$seedResult = & (Join-Path $JavaHome 'bin/java.exe') (Join-Path $PSScriptRoot 'B43SeededCriticalMethod.java')
if ($LASTEXITCODE -ne 0 -or $seedResult -cne 'input=0 enabled=0') {
    throw 'The seeded critical method did not run with all eleven predicates false.'
}
Write-Output "seed-observation $seedResult complexity=12 observed-outcomes=11/22 (manual)"
Assert-Decision 'seeded-complex-and-under-covered' 'escalate_both' (Get-QualityDisposition 12 11 22)
Assert-Decision 'only-complex' 'escalate_complexity' (Get-QualityDisposition 11 20 20)
Assert-Decision 'only-under-covered' 'escalate_branch_outcomes' (Get-QualityDisposition 10 17 20)
Assert-Decision 'reference-quality-boundaries' 'within_reference' (Get-QualityDisposition 10 18 20)
