# G6-P request · B46 OpenAPI diff pilot

B46 requires approval of the exact pilot tool and trial scope. This Windows x64 host has no `oasdiff` on PATH. The proposed pin is the official `oasdiff` v1.39.0 `oasdiff_1.39.0_windows_amd64.tar.gz`, SHA-256 `956a73df88e03efdf3fcc5f327446cc176fefe4adcea1978b0fef464a1ee8901`. [Official release assets](https://github.com/oasdiff/oasdiff/releases/expanded_assets/v1.39.0) · [B46 acceptance](../../../Phase4-development-planning/tool-pilots.md#b46--t3-r-api-diff-pilot)

The approval would cover downloading the pinned archive from its official GitHub release into `tmp/phase5-development/b46-tools`, checking the published hash before extraction, and running the portable tool locally against the reviewed B45 spec and seeded compatibility fixtures. The cases cover removed routes, a string-to-number publication ID change, a safe addition and an unchanged baseline. Capture output and exit status for the intended pull request and release comparisons, then compare them with the existing Spring and manual results. [Accepted tooling decision](../../../Phase3-LLD/tooling.md)

The trial uses the seeded baseline until a release baseline is approved. Adoption of a compulsory gate has its own G6-M disposition. This request is pending owner approval and independent B45 integration.
