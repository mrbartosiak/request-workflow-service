# B26 port-free checkpoint

The B26 worktree is `tmp/phase5-development/b26-inbound-ack` on `codex/b26-inbound-ack`. HEAD is the independently approved B23 commit `9494969b4aca3880aac04dea2f6f502a0b1fa63a`, tree `2e1e8acab87317fad8ba8e1faa3e9641cacdc3f1`. Its author and committer dates are both 2026-11-22 22:43 Warsaw time. The checkpoint has 13 untracked source and fixture files. Staging, committing and integration are pending the coordinator's exact parent and date.

All source additions are B26-owned. They cover the new typed notice, source verifier, coordinator and inbox contracts under `integration`, the JDBC inbox and owner migration under `adapter-integration-jdbc`, the approved new opt-in `SyntheticInboundConfiguration.java`, and the private `fixtures/postgresql/b26` launcher. Existing tracked paths and dependencies are untouched. The SHA-256 manifest records every checkpoint source exactly as present in the worktree. [Source manifest](../../../../tmp/phase5-development/b26-fixture/logs/source-sha256.txt)

## Port-free checks

The targeted red ran six tests against the placeholder coordinator. Five failed as expected. The targeted green ran six tests successfully and compiled the JDBC adapter. Final strict offline `clean verify` passed 354 tests across 18 suites in both default and `synthetic-http` packages, with zero failures, errors or skips. Those runs include the final secret-copy and route-uniqueness assertions. [Targeted red](../../../../tmp/phase5-development/b26-fixture/logs/port-free-red.log) · [Targeted green](../../../../tmp/phase5-development/b26-fixture/logs/port-free-green.log) · [Default build](../../../../tmp/phase5-development/b26-fixture/logs/default-verify.log) · [Profile build](../../../../tmp/phase5-development/b26-fixture/logs/profile-verify.log)

The package scan checked 26 default JARs and 30 profile JARs. The synthetic listener appears in the profile. The B26 fixture was compiled externally against the profile artifacts and excluded from that runtime classpath.

| Control | SHA-256 |
| --- | --- |
| Targeted red | `410b509b7632397b7f84f1c089d485836d9110df6709d0f5fba81d50d2464228` |
| Targeted green | `ca57595928a01116123fae884187b7836d485fa1d499fa8304fd2f922e642b72` |
| Default build | `a413e87d7eed4c3184de798806bcefb915b64c7286e654416f484370b5f4b56d` |
| Profile build | `8f0e8e0450b6a1b8788e6a27dd767d1a7d35a1e5c39cedd69d452387f62908ba` |
| Default package scan | `564c020fb31909c8735991c1b2a000d0d5a2614d00cfa02bbc6ca59f8fe840a0` |
| Profile package scan | `816109892a4c747c1810f0e3ba91d3a3285358a3bd884c2f6f0cbd5659a0cc98` |
| B26 private fixture isolation | `a7718837073d337208a31863c5d24aa0e412cd6feeb05e29e6b73e24db3a9d74` |
| Packaged runtime manifest | `8d8b87ffb47f4126f85d64cdbcbeef5539d8cb3e57cdae6bb89dc81027eb346d` |
| Frozen source manifest | `c9c73255dbcfa36925d660bcb8e09ea2febc84bf86ee8e303d55518af21dd08f` |

## Native verification pending

After transfer of the 55480–55482 lease and refresh onto the coordinator's exact reviewed base, run from the B26 checkout with a fresh evidence directory and a unique run ID.

~~~powershell
$workspace = (Resolve-Path '../../..').Path
$scratch = Join-Path $workspace 'tmp/phase5-development/b26-native-fixture'
$b26 = './fixtures/postgresql/b26/Run-B26.ps1'
& $b26 -Action Build -Workspace $workspace -Scratch $scratch -RunId b26-candidate
& $b26 -Action Run -Workspace $workspace -Scratch $scratch -RunId b26-candidate
~~~

If the run is interrupted, use `-Action Cleanup` for the same scratch and run ID while holding the lease. The native wrapper starts only the two loopback fixture services. Its run directory begins `b10-b26-`.

The native fixture compiles but its PostgreSQL migration, role grants, deferred failure, pre-COMMIT visibility, concurrent retry, lost COMMIT reply, target and source rejection, and restart persistence assertions await execution. B26 has used no fixture ports. [Fixture instructions](../../../../tmp/phase5-development/b26-inbound-ack/fixtures/postgresql/b26/README.md) · [Successor contracts](handoff.md)
