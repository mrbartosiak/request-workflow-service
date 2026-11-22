# Candidate review and reproducibility

**Scope** PH4-B-r1. The SHA-256 manifest lists every authored Markdown payload for this candidate, including this document. Paths are relative to this folder, alphabetically sorted. Each row uses lowercase hex, two ASCII spaces and the filename. The manifest uses ASCII and LF. The independent reviewer attests the manifest's own SHA-256 and records dispositions separately. Any payload change requires a fresh manifest and reviewer verification of the resulting bytes. [Manifest](manifest.sha256) · [Plan index](index.md)

## Reviewer checks

- Read the accepted Phase 3 receipt and verify its listed manifest SHA-256 and 30 payloads. Resolve source references against that design, including the later owner identifier rules. [Acceptance](../../../Phase3-LLD/phase-4-handoff-acceptance.md) · [Exact source manifest](../../../Phase3-LLD/work/reviews/lld-r2-manifest.md) · [Source verification](../../../Phase3-LLD/sources.md)
- From this candidate directory, generate or independently compare the exact manifest with the PowerShell below. Require exactly one row per payload and no untracked payload omissions. Read the manifest digest for the review receipt. [Manifest](manifest.sha256)
- Check local link targets and source anchors, one definition per B01–B56, an acyclic graph from each `Needs` line, and the `Red`, `Green` and `Stop` fields. Compare cross-branch handoffs, all seven deferrals and E/P assignments with the source. Identify any independently loaded module over 5,000 characters. [Milestones](milestones.md) · [Feature trace](trace-features.md) · [Quality trace](trace-quality.md)
- Record actual candidate byte, source, link and ticket checks separately from proposed runtime verification. Confirm all owner decisions retain proposal status unless a later exact receipt is linked. [Evidence protocol](execution.md) · [Choices](choices.md)

```powershell
$rows = Get-ChildItem -File -Filter '*.md' | Sort-Object Name | ForEach-Object {
  $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
  '{0}  {1}' -f $digest, $_.Name
}
$expected = ($rows -join "`n") + "`n"
$actual = [IO.File]::ReadAllText((Join-Path (Get-Location) 'manifest.sha256'))
if ($actual -cne $expected) { throw 'Candidate manifest mismatch' }
Get-FileHash -Algorithm SHA256 -LiteralPath './manifest.sha256'
```

The executor checked the accepted source manifest and 30 payloads, candidate links and anchors, 56 unique ticket definitions with their five required fields, and an acyclic dependency graph. All 28 payload modules fit within 5,000 characters. Backend, demo, PostgreSQL, load, restore and live vendor evidence remain planned. The independently probed JDK is a preflight result. [Readiness](environment.md)
