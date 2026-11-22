# B29 owner approval for local loopback execution

On 23 November 2026 the owner directly approved running the B29 recovery fixture with local loopback access outside the shell that failed its readiness probe. The scope is the already qualified disposable PostgreSQL runtime, the fixture's own temporary processes and ports 55480–55482 on the workspace machine. The first two attempts stopped before the B29 checks and their exact owned processes and PID files were cleared. [Assignment](assignment.md) · [Restricted-shell startup evidence](../../../../tmp/phase5-development/b29-recovery-epoch/Documentation/Phase5-development/work/B29/evidence-b26-startup.md)

The coordinator refreshed the isolated worktree to approved B45 `231589477a114b00fc127855e0598a0d56dc6727` with all twelve prior source digests preserved and verified the ports were free. B29 holds the only fixture lease for the approved loopback run and its teardown.
