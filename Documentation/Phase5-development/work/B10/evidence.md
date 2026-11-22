# B10 execution evidence · Effective access updates

Candidate `9b46a1a4d850e9d17746a2a6887dc8e6dd41f291`, tree `38e841447b58540b729c033436288515c4e5cd6a`, on clean branch `codex/b10-effective-access`. The direct parent is approved B11 `4c449f91216149b43c55360bdd2e82efe75932f1`. Both Git dates are 2026-11-22 22:25:00 Europe/Warsaw. GPT-6.2 Sol xhigh (56) executed the candidate. Independent GPT-6.2 Astra xhigh (62) review is pending. [Assignment](assignment.md) · [B11 release base](../B11/rebase-4c449f9.md)

The governing plan is `PH4-PLAN-r1`, manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. The verification here ran within G1-U on owner-approved synthetic data and the qualified portable PostgreSQL fixture. [Owner approval](../g0-remaining-plan-approval.md) · [G1-U](../g1-u-wave2-approval.md) · [B10 criteria](../../../Phase4-development-planning/scope-proof.md#b10--effective-grant-assignment-and-visibility-updates)

## Effective point and current policy

The opt-in profile routes trusted, operation-specific synthetic operators through the exclusive organization guard. A mutation reads its exact prior state on the owned command connection, prepares a canonical UTF-8 v1 delta in the independent journal, and atomically applies the local change, increments the guard policy version, and appends the exact witness. The mutation returns pending confirmation after local commit. The separate audited B11 resolver verifies the original committed workflow witness or rollback and installs the matching checkpoint under its own exclusive guard. [Mutation service](../../../../tmp/phase5-development/b10-effective-access/application/src/main/java/com/company/requests/application/EffectiveScopeUpdates.java) · [Canonical change](../../../../tmp/phase5-development/b10-effective-access/application/src/main/java/com/company/requests/application/EffectiveScopeChange.java) · [V006](../../../../tmp/phase5-development/b10-effective-access/adapter-jdbc/src/main/resources/db/workflow/V006__effective_scope_access.sql)

Current key issuance and submitted Create use the current typed grants. Create arbitration and effects prove the current scope and live writer under the exclusive guard. Replay and field readers verify the B11 checkpoint, its exact local projection and the certified live writer on the bound command connection. The shared release guard covers the result check, current minimum fields, preparation and bounded handoff. Per-target facts can conceal the target or selected fields. Assignment affects private field membership, and a move keeps immutable origin while incrementing the assignment version. [Guarded access](../../../../tmp/phase5-development/b10-effective-access/application/src/main/java/com/company/requests/application/CurrentAccess.java) · [Gate](../../../../tmp/phase5-development/b10-effective-access/application/src/main/java/com/company/requests/application/EffectiveScopeGate.java) · [Profile](../../../../tmp/phase5-development/b10-effective-access/bootstrap/src/main/java/com/company/requests/bootstrap/SyntheticEffectiveAccessConfiguration.java)

## Native observations

| Check | Exact observation |
| --- | --- |
| Stale-grant red | On the native confirmed revoke at scope version 4, the retained immutable B11 submitted-access path accepted the old Create grant. The control exited 1 at the deny assertion. The observation is a preflight failure. |
| Role boundaries | Separate logins authenticated for workflow command, original observer, projector, independent Create proof, journal preparation and resolver. The preparation login could append delta bytes and advance sequence, and lacked raw delta SELECT and release preparation INSERT. The certified resolver lacked direct attempt-state UPDATE. The command role had assignment-column UPDATE but no UPDATE on request origin or assignment identity. An assignment-only operator could move a trusted assignee. Wrong operator, permission, organization, target, resolver scope and a fabricated unlocked guard were rejected. |
| Shared handoff first | PostgreSQL reported the visibility writer blocked on `FOR UPDATE` after 3 ms. The earlier protected handoff won. After the confirmed field mask, retry concealed the original receipt and did not serialize or prepare it. Current field membership differed by assignment. |
| Exclusive mutation first | PostgreSQL reported a protected retry waiting on `FOR SHARE` after 4 ms. While the writer's grant revocation remained pending, Create and field reads deferred. Following audited confirmation, replay and a fresh-effect attempt using an unused previously minted key were denied without any new request, reservation or preparation. Independent issuance and field grants remained usable. |
| Exclusive effect first | PostgreSQL reported the revocation writer waiting on `FOR UPDATE` after 8 ms. The earlier Create effect and finalized receipt survived the later confirmed revocation. A subsequent replay was denied before serialization. |
| Projection and durability cuts | A mismatched workflow checkpoint and a withdrawn independent confirmation each fenced field access until the exact proof was restored. A denied witness INSERT rolled back the local grant and version. A simulated lost reply after durable prepare stopped all local effects. Both numbered unresolved attempts fenced issuance until an audited proof of original rollback restored availability. |
| Final coordinates | Five committed changes advanced policy version 1 to 6 and created five exact witnesses. Two audited aborts advanced the confirmed journal prefix to 7 while the policy version remained 6. |

The independent journal bytes, SHA-256, full transaction coordinate, before/after policy versions and local witness matched for all five effective changes. The earlier cold-connection run observed the release-first guard order but the waiting writer exceeded the existing 200 ms deadline with SQLSTATE 55P03. The final native fixture used two prewarmed pgJDBC connections per role and kept the application deadlines and independent PostgreSQL wait observations. [Exact green log](../../../../tmp/phase5-development/b10-fixture/logs/b10-native-green-06.log) · [Cold-connection diagnostic](../../../../tmp/phase5-development/b10-fixture/logs/b10-native-green-04.log)

| Sequence | Version | Exact delta SHA-256 from the green run |
| --- | --- | --- |
| 1 | 1 → 2 | `6dd6bfe52ed5672f364c074db9d907e9f1abd191a997f8970e2ecbc5e0d2788e` |
| 2 | 2 → 3 | `a8fae428885bb0d456abb195058d13503cce4adfecdeb1e764078de0f6590bf0` |
| 3 | 3 → 4 | `f753d44d10bb91d1dfb5a398e2d97748c0fd890c44877e4c2b6f50fecfe75b66` |
| 4 | 4 → 5 | `9a6353e29c4c74abc301b086abbfc5f1c2b4eda957444a1b085a19395d200d6e` |
| 5 | 5 → 6 | `72fe0b44c9e0795c7dfb142c3f3477a9ed913e1f956d00bc118a50ab5cddd7c5` |

## Regression and reproduction

The candidate passed strict offline `-Psynthetic-http clean verify` across 11 modules with 320 tests, 10 suites, and zero failures, errors or skips, including the three B41 evidence tests. Exact native B11 passed 22 checks, including the independently verified physical backup and isolated restore on 55482. The immutable B08 listening HTTP regression passed two application lifetimes with identical HTTP 201 body and Location, one committed five-row effect, one reservation and two preparation rows. Both application listeners closed. All owned PostgreSQL processes stopped, and loopback ports 55480, 55481 and 55482 were clear at 22:44:28.4166527 Europe/Warsaw. [Port record](../../../../tmp/phase5-development/b10-fixture/logs/final-port-check.log)

Use adopted Temurin 25.0.5.1+2, Maven 3.9.16, both isolated Maven settings arguments, and a private copy of the approved `b08-http-integrated/repository` under the scratch directory. The launcher uses the approved PostgreSQL 18.7 binary directory, pgJDBC 42.7.14 and Checker Qual 3.55.1. Use an owned scratch directory adjacent to the candidate worktree, reserve the three G1-U ports before running, and use fresh IDs.

~~~powershell
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" -Psynthetic-http clean verify
& ./fixtures/postgresql/Run-B10.ps1 -Mode compile -Scratch $scratch
& ./fixtures/postgresql/Run-B10.ps1 -Mode green -Scratch $scratch -RunId b10-green-example
& ./fixtures/postgresql/Run-B10.ps1 -Mode red -Scratch $scratch -RunId b10-red-example
& ./fixtures/postgresql/Run-B10.ps1 -Mode b11 -Scratch $scratch -RunId b11-b10-example
& ./fixtures/postgresql/Run-B10.ps1 -Mode b08 -Scratch $scratch -RunId b08-b10-example
~~~

Red expects exit 1 and `B10 assertion: Expected AccessDeniedException` after the stamped revoke. The other modes expect exit 0. The workflow and proof migration owner and all runtime roles use per-run synthetic SCRAM credentials. The selected shareable execution artifacts contained zero matches for bearer, private-key delimiters, credential-bearing JDBC URL and password-assignment patterns. Native raw files remain in private fixture scratch.

| Artifact | SHA-256 |
| --- | --- |
| [B10 launcher](../../../../tmp/phase5-development/b10-effective-access/fixtures/postgresql/Run-B10.ps1) | `d8d743a9934173e769d3d78395711e58669c5b38aabab58212ae3725875e2fec` |
| [Strict offline verification](../../../../tmp/phase5-development/b10-fixture/logs/candidate-offline-verify-02.log) | `e9b013f4eb3fb5b0dca42be69d8c6e5c0ba598123c803a9469a83c1aa3775838` |
| [B10 native green, 23 PASS](../../../../tmp/phase5-development/b10-fixture/logs/b10-native-green-06.log) | `d346865676fda2d47f042f8e554f574d7949f102826872e207f10b1197ef9c2e` |
| [B10 legacy preflight red](../../../../tmp/phase5-development/b10-fixture/logs/b10-legacy-red-01.log) | `b778f1198f800bdd0d9a1d02388c7cdb17206419cdc2b84ebe2ad2c5bafd5ebc` |
| [First bounded guard-wait diagnostic](../../../../tmp/phase5-development/b10-fixture/logs/b10-native-green-04.log) | `774bbea12f55ae89980624c5380073e1e9c52c2df3e697ba687f45df91b05831` |
| [B11 native regression, 22 PASS](../../../../tmp/phase5-development/b10-fixture/logs/b11-b10-regression-01.log) | `5a07de53dcd497229e9af819de667d789b1d9a3b29c36f4dcca51dfea8472740` |
| [B11 physical backup verification](../../../../tmp/phase5-development/b10-fixture/runs/b11-b10-regression-01/010-pg_verifybackup-6051050073820802654.log) | `0754d7d15ec01e79210439c42400038c0590df438c3a0b0f404b47d782030ea3` |
| [B08 listening HTTP restart regression](../../../../tmp/phase5-development/b10-fixture/logs/b08-b10-wire-regression-01.log) | `05ed6163152e36d119079da1616bcebf11cd14828db4e12d94f6f965d3c4f2c7` |
| [Listener checks after every completed run](../../../../tmp/phase5-development/b10-fixture/logs/final-port-check.log) | `e761677ed5f18c85d30f1dc4f44e30b85dbc883867f9aa5eabbe1d5cf18985c3` |

## Successor handoff

- B12 can use the current submitted and result checks in the guarded phase, with `EffectiveScopeGate` checking the B11 checkpoint and writer against the bound connection. Preserve the ordering through the bounded handoff and keep receipt lookup and preparation inside the guarded decision.
- B13–B15 can consume guarded assignment and security-fact versions and bind those current values into context checks. Use the same exclusive organization guard for changes that affect access. Resolve the stable person's identity from the trusted binding and retain the original person's identity independently.
- B19–B21 can use `CurrentAccess.permittedFields` inside the owned shared or exclusive guard to exercise current mask, assignment and row concealment. `JdbcEffectiveScopeStore` exposes scoped local state for guarded change planning and should be used with `EffectiveScopeGate` when composing a protected reader. The synthetic field mapping derives from the configured Create-result grant. Later read-specific grants and projections follow their individual ticket contracts.
