# Phase 6 change loop and exact review

1. Open a review finding with current plan/build/fixture hashes, requirement IDs, confirmed behavior or evidence gap and affected users/operations. Link the smallest reproduction or static rationale.
2. Describe the proposed change, options, why it preserves or revises approval, migration/rollout effects and evidence needed. Owner approves important changes. Keep predecessor assumptions and approvals traceable.
3. Amend conceptual assumptions/features/acceptance and HLD/LLD only where needed. Re-review changed design and its downstream meanings. Then issue a new planning revision entry with changed cards, dependencies, milestones, gates and affected evidence references. Preserve stable task IDs, or explicitly link superseding IDs to retired ones.
4. Execute changed scope via a new red/green/refactor record, reviewer verification and affected-case reruns. Include the new plan revision and task ID in commits, for example `plan(PH4-r2,APP-05)`. Preserve unversioned endpoint paths. Handle wire compatibility, runtime migrations and internal event versions independently.
5. Review the exact merged revision, regenerate manifest and retain acceptance separately. Mark previous evidence stale where the code, contract, fixture or approval scope changes. Update root README and assumptions as applicable for the next source submission. [Owner Phase 6 direction](../../../output/Assumptions-initial-HumanMade.txt) · [Phase obligations](../../../Phase1-conceptual/assumptions.md#phase-obligations)

## Candidate exact package

| Revision | Review change |
| --- | --- |
| `PH4-A-r1` | Assembled independent candidate and submitted the 21-payload snapshot for review. |
| `PH4-A-r2` | Added access-changing command proof, early and interview release advisories and coverage, paired T3 behavior prerequisites and exact-target production matrix. |

`manifest.sha256` lists every authored Markdown payload in this directory, alphabetically by filename. Each line contains the lowercase SHA-256 of exact bytes, two ASCII spaces, then the filename. UTF-8 without BOM and LF delimiters apply to the manifest. It excludes itself. The independent reviewer records the hash of the exact manifest and checked payload count separately. This record supports review and owner approval of a selected or synthesized plan. [Prior handoff pattern](../../../Phase3-LLD/phase-4-handoff-acceptance.md)

From the candidate directory, regenerate in PowerShell after closing edits.

```powershell
$artifactRoot = (Get-Location).Path
[string[]]$artifactFiles = @(Get-ChildItem -LiteralPath $artifactRoot -File -Filter '*.md' | ForEach-Object Name)
[Array]::Sort($artifactFiles, [System.StringComparer]::Ordinal)
$artifactRows = foreach ($artifactName in $artifactFiles) {
    $artifactPath = Join-Path $artifactRoot $artifactName
    $artifactHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$artifactHash  $artifactName"
}
$artifactManifest = Join-Path $artifactRoot 'manifest.sha256'
[IO.File]::WriteAllText($artifactManifest, (($artifactRows -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
Get-FileHash -LiteralPath $artifactManifest -Algorithm SHA256
```
