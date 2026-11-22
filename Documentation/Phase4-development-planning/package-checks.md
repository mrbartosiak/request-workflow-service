# Plan review and reproducibility

**Scope** PH4-PLAN-r1. The SHA-256 manifest covers every authored Markdown payload at the top level of this Phase 4 directory, including this document. The signed candidates and independent review records retain their separate exact digests. Paths are relative to this folder, alphabetically sorted. Each row uses lowercase hex, two ASCII spaces and the filename. The manifest uses ASCII and LF. The independent reviewer attests the manifest's own SHA-256 and records dispositions separately. Any payload change requires a fresh manifest and reviewer verification of the resulting bytes. [Manifest](phase4-plan-r1.sha256) · [Plan index](development-plan-ai-clarity.md)

## Reviewer checks

- Read the accepted Phase 3 receipt and verify its listed manifest SHA-256 and 30 payloads. Resolve source references against that design, including the later owner identifier rules. [Acceptance](../Phase3-LLD/phase-4-handoff-acceptance.md) · [Exact source manifest](../Phase3-LLD/work/reviews/lld-r2-manifest.md) · [Source verification](../Phase3-LLD/sources.md)
- From the Phase 4 directory, compare the exact manifest with the PowerShell below. Require one row per authored plan payload. Verify both candidate digests and reviewer records separately. Read the manifest digest for the review receipt. [Manifest](phase4-plan-r1.sha256) · [Candidate provenance](source-comparison.md)
- Check local link targets and source anchors, one definition per B01–B56, an acyclic graph from each `Needs` line, and the `Red`, `Green` and `Stop` fields. Compare cross-branch handoffs, all seven deferrals and E/P assignments with the source. Identify any independently loaded module over 5,000 characters. [Milestones](milestones.md) · [Feature trace](trace-features.md) · [Quality trace](trace-quality.md)
- Record actual plan byte, source, link and ticket checks separately from proposed runtime verification. Confirm all owner decisions retain proposal status unless a later exact receipt is linked. [Evidence protocol](execution.md) · [Choices](choices.md)

```powershell
$rows = Get-ChildItem -File -Filter '*.md' | Sort-Object Name | ForEach-Object {
  $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
  '{0}  {1}' -f $digest, $_.Name
}
$expected = ($rows -join "`n") + "`n"
$actual = [IO.File]::ReadAllText((Join-Path (Get-Location) 'phase4-plan-r1.sha256'))
if ($actual -cne $expected) { throw 'Plan manifest mismatch' }
Get-FileHash -Algorithm SHA256 -LiteralPath './phase4-plan-r1.sha256'
```

The executor checked the accepted source manifest and 30 payloads, both signed candidate inventories, canonical links and anchors, 56 unique ticket definitions with their five required fields, and an acyclic dependency graph. All plan payload modules fit within 5,000 characters. Backend, demo, PostgreSQL, load, restore and live vendor evidence remain planned. The independently probed JDK is a preflight result. [Readiness](environment.md)
