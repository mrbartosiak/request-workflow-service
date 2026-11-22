# B49 offline fixture review

**Verdict. Approved for the offline stage.** The candidate reproduces the fixed offer schedule and reconciles every planned offer from simulated observations. The executable two-instance quick benchmark, target preflight, sampled durable effects and measured performance results remain pending. B36 is an owner-supplied downstream development assumption. [Assignment](assignment.md) · [Planning assumption](../g0-downstream-development-assumption.md) · [B49 delivery](../../../Phase4-development-planning/delivery.md) · [Performance profile](../../../Phase3-LLD/performance.md)

## Source and reproduction

The reviewed commit is `13a13d58806b7d83b871fecf61188b691cb2d6f6`, tree `efe66f071849cfc6041096226a2927188d4bcfc9`. Published B42 `b489f38f7d35fefbb0156fc32d52e274cb17beee` is its sole parent and matches the locally recorded publication ref. Both Git timestamps are 2026-11-22 22:54:00 +01:00. The parent diff adds exactly `B49Smoke.java`, `B49SmokeControls.java` and `README.md` in `fixtures/postgresql/b49/`. Each file matched its committed blob and manifest entry. The supplied manifest SHA-256 is `224f72435c0325e434096ea1304988c1497f62de8847360829f1d57c7467f751`. The supplied control log SHA-256 is `ee22b594be6bed18f2c430f93d477e1be3596f342f0f7206ea88c13cdcd71434`.

The following commands reproduce the strict compilation and simulated controls from the repository root. The review used fresh scratch directory `tmp/phase5-development/b49-offline-review-e6723f48-c0b6-4a4b-89ff-6828917a572f`. Both compilation and execution exited zero. The review control output has SHA-256 `a83f4855bd5cbafdeebcbf1fe1f10d095ea93ad322d756161926a3fe520f0207`. [Review output](../../../../tmp/phase5-development/b49-offline-review-e6723f48-c0b6-4a4b-89ff-6828917a572f/review-controls.log)

```powershell
$jdkBin = Resolve-Path 'tmp/phase5-development/b01-tools/temurin/jdk-25.0.5.1+2/bin'
$src = 'tmp/phase5-development/b49-offline-smoke/fixtures/postgresql/b49'
$classes = (New-Item -ItemType Directory -Path ('tmp/phase5-development/b49-offline-review-' + [guid]::NewGuid())).FullName
& "$jdkBin/javac.exe" --release 25 -Xlint:all -Werror -d $classes "$src/B49Smoke.java" "$src/B49SmokeControls.java"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& "$jdkBin/java.exe" -cp $classes B49SmokeControls
if ($LASTEXITCODE -ne 0) { throw 'Controls failed' }
```

## Verified results

| Control | Independent review result |
| --- | --- |
| Schedule | IDs 1–3,000 fall in the half-open measurement interval [10 s, 40 s). The first offer is at 10.000 s, the last at 39.990 s, and consecutive offers are 10 ms apart. The eight operations cycle 375 times. Each instance receives 1,500 offers. A/B is 188/187 for Create, Verify, Reject and Publish, and 187/188 for Edit, Accept, Delete and Browse. |
| Mixed evidence | All 3,000 offers reconcile. Eight full responses comprise two eligible successes, one eligible conflict and five unexpected results. One timeout, one terminal transport failure, one explicit unstarted and 2,989 missing records complete the denominator. The timely full-response share is 7/3,000 and the timely eligible share is 2/3,000. |
| Incomplete or malformed evidence | A 550 ms deadline reached in the queue contributes a timeout sample. A known start with missing completion retains its start and counts as missing. With all evidence absent, each operation retains 375 missing offers and has zero latency samples. All 12 malformed-evidence controls passed, including forbidden IDs, duplicate IDs, instance mismatch and inconsistent timestamps. |
| Quantiles and threshold | The 375 simulated Create completions give nearest-rank max/p50/p95/p99 of 750/376/714/744 ms and 250/375 at or under 500 ms. The mixed control includes full responses and deadlines in its nine latency samples. Threshold shares use all planned offers, including failures and omissions. |
| Independent boundary check | A reviewer scratch control fed reverse-ordered evidence with 125 completions per operation at each of 500 ms − 1 ns, exactly 500 ms and 500 ms + 1 ns. Dispatch was delayed until 10 ns before the threshold. Every operation gave 250/375 timely by both definitions, with 2,000/3,000 overall. All eight p50 values were exactly 500 ms and p95, p99 and maximum were 500 ms + 1 ns. Completions extending past 40 s were retained for offers scheduled within the window. [Reviewer source](../../../../tmp/phase5-development/b49-offline-review-e6723f48-c0b6-4a4b-89ff-6828917a572f/ReviewBoundary.java) |

The candidate README identifies the remaining launch inputs. These include the exact Java 25/Spring 7.0.11 build, two processes with separate pools on shared PostgreSQL, workflow and distinct durable proof identities, authorized fixture grants, an independent read-only observer, trusted clocks and independently paced dispatch. It calls for eight authenticated routes with current authority, publication witness, scope confirmation and disclosure, a protected spool and sink on both instances, live vendors disabled, prepared targets/keys/observations and ≥1,000 eligible browse rows plus hidden matches. Warm-up, target identity checks, sanitized records, sampled durable effects and measured results belong to the future target run outside CI. [Candidate README](../../../../tmp/phase5-development/b49-offline-smoke/fixtures/postgresql/b49/README.md) · [Accepted environment](../../../Phase4-development-planning/environment.md)
