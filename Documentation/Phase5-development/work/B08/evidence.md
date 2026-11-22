# B08 execution evidence · Protected Create and exact replay

Executed 2026-11-22 (Europe/Warsaw) by GPT-6.2 Sol xhigh (56). Candidate `dc42b9a6d83def238e2641b77bb85cec9b40c5e2`, tree `bdb2e2a861df764d58e3096b9294a8a809dc1a49`, parent `cadea474479bb885b45c9eb984870653ce682da1`, branch `codex/b08-persistent-create`. The checkout is clean and the 23-path change passes `git diff-tree --check HEAD^ HEAD`. `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. GPT-6.2 Astra xhigh (62) is assigned independent review. [Assignment](assignment.md) · [G0](../g0-kickoff.md) · [G1-U](../B04/g1-u-approval.md) · [B08 criteria](../../../Phase4-development-planning/first-slice.md#b08--first-runnable-durable-create-with-exact-retry)

## Verified stage

The opt-in `SyntheticCreateConfiguration` registers `POST /operation-keys` and `POST /requests` with the B03 validator, B06 synthetic bearer resolver and B07 signed keys. The default B01 context starts without synthetic routes. The configured organization comes from trusted synthetic policy. This fixture assigns the trusted originating person as the initial assignee at version 1.

The independent proof owner certifies the workflow endpoint, database and postmaster start instant against one writer epoch. The application reads those fields on the owned writable primary connection and rechecks them against the separate proof store. Preflight proves submitted access and live lineage before interpreting the signed key, and repeats the proof before arbitration and guarded lookup. A missing or mismatched certificate closes the route before a receipt query. Reconstructing or starting the application preserves access to the same certified live writer. A PostgreSQL process restart creates a different live identity and stays closed under the original certificate.

Workflow V003 adds a tentative claim on the existing V002 receipt primary key. The deferred self-reference enforces finalization at commit and preserves earlier finalized rows. The claim precedes the exclusive organization guard. Request, originating person, body, creation fact, assignment and finalized receipt share the same owned transaction. Independent identity reservation may survive rollback. A failed commit confirmation enters a fresh guarded resolution phase. A matching finalized receipt on certified live lineage can resolve that response. Missing or disputed evidence withholds the response. A later active execution still requires successful unique-key arbitration on the certified writer.

The shared guard protects fresh submitted access, original result grants, the exact stored digest and signed expiry. The independent proof append binds a correlation, organization, scoped key digest, committed request reference, result revision and generation. The scoped application emits a bounded status, Location and body only after guarded handoff completes. HTTP response writing follows lock release. The evidence distinguishes business commit and preparation. Client acknowledgment or transport delivery requires separate evidence.

## Results

| Check | Observation |
| --- | --- |
| Pinned-base red | The offline base passed 311 tests. A temporary targeted Spring MVC test expected unauthenticated Create to reach a protected route and return 401. The base returned 404. One failed assertion, exit 1. [Red log](../../../tmp/phase5-development/b08-fixture/logs/red-missing-protected-create.log) |
| Offline reactor | All 11 projects and all 311 tests passed with zero failures, errors or skips. The B08 real-engine oracle is opt-in. [Reactor log](../../../tmp/phase5-development/b08-fixture/logs/final-offline-verify.log) |
| V002 upgrade and pending claim | A populated V002 receipt survived V003 and reapplication exactly and beat a new claim on its original key. A tentative row was invisible to the receipt lookup in its own transaction. The database refused its unfinalized commit with SQLSTATE 23503. The workflow migration history was 1–3 and independent proof migration history was 1–2. |
| Application process restart | The PostgreSQL clusters stayed live while JVMs 12504 and 20900 ran sequentially and both exited. One Create and an alias retry with reordered JSON returned the same 201, Location and body with the original decimal-string revision. The observer counted one request, body, fact, assignment and receipt. The exact UTF-8 name/body and trusted origin/assignee were preserved. One independent reservation and two preparations shared one key digest and result. Test ingress used Spring MockMvc in each JVM. |
| Fresh keys and access | Two keys with the same decoded inputs created distinct references. Changed exact input on a disclosable original returned 409 `key_conflict`. A changed intent with a concealed original returned ordinary 404 with no reference or preparation. The fixture also checked unauthenticated/bad input, key issuance denial, missing submitted grant and wrong stable principal. |
| Expiry and independent proof cuts | Signed expiry and withdrawn genesis, writer epoch or live certificate caused zero claims and zero receipt reads. An active key inside the admission margin performed one guarded lookup and zero claims. Expiry reached during independent preparation preserved the committed effect and preparation and suppressed the result. A later attempt at expiry performed zero receipt SQL. |
| Known rollback and withheld handoff | The cut before receipt finalization produced zero committed local row delta and zero preparation. One independent provisional reservation was burned. With proof preparation disabled after commit, the endpoint withheld the response while observers saw the five-row effect. Replay recovered it under a fresh shared guard. A concurrent exclusive `NOWAIT` failed during preparation and succeeded after handoff. |
| Commit ambiguity | A real local commit followed by synthetic reply loss returned the one original result through a fresh guard. A missing receipt after synthetic abort and reply loss returned 503 `outcome_unknown` with no second execution in that call. The subsequent certified same-key retry committed once. A separate lost reply with the certificate withdrawn returned ordinary authority deferral with zero receipt reads and recovered only once proof returned. |
| Key before guard | An owned transaction had inserted its uncommitted claim while a separate session could still take the exclusive guard. The same-key contender timed out at the unique key and returned 503 with zero receipt reads. Three claim statements, one exclusive guard acquisition, two guarded receipt reads and one effect were observed. |
| Earlier generation | Absent and seeded unproved present prior-generation receipts returned indistinguishable 409 `retry_fenced` problem fields and header policy apart from correlation. Two guarded reads, zero claims and zero exclusive guards. The seeded older receipt was a test control. |
| Database process restart and teardown | After PostgreSQL restarted on the same workflow port and data, the old certificate caused 503 before any claim or receipt read. The final synthetic scope had nine request/body/fact/assignment effects and ten finalized receipts, including the seeded control. Proof counted 11 reservations and 10 preparations. There were zero pending claims. Run teardown stopped workflow PIDs 8216 and 26632 and proof PID 17076. `pg_ctl` reported stopped and ports 55480 and 55481 were free. |

All B08 real-engine assertions above passed in `b08-dual-03` with PostgreSQL 18.7, pgJDBC 42.7.14, UTF-8 server/client encodings and SCRAM. Workflow system identifier `7711050186986140136` and proof system identifier `7711050208819004120` differed. The database postmaster restart preserved the workflow system identifier while changing its live proof identity. [Decisive log](../../../tmp/phase5-development/b08-fixture/logs/dual-03.log)

The first real-engine run exposed the PostgreSQL 55P03 lock-timeout classification. The command-scoped JDBC runner now recognizes PostgreSQL lock and statement cancellation SQLSTATEs in addition to Spring timeout exceptions. The first follow-up used an earlier reactor JAR. The decisive run used fresh `clean verify` artifacts and recompiled launchers. [First real-engine run](../../../tmp/phase5-development/b08-fixture/logs/dual-01.log) · [Stale-JAR attempt](../../../tmp/phase5-development/b08-fixture/logs/dual-02.log)

Both exact B05 real-engine modes passed with 84 observed catalog constraints and B06 passed its existing independent reservation and guard faults. The B05 diagnostic labels name its predecessor migrations. Its assertions require all three workflow migration entries. Each run tore down its owned postmaster processes. [B05 empty](../../../tmp/phase5-development/b08-fixture/logs/b05-empty-regression.log) · [B05 prior](../../../tmp/phase5-development/b08-fixture/logs/b05-prior-regression.log) · [B06](../../../tmp/phase5-development/b08-fixture/logs/b06-regression.log)

## Reproduction

Run PowerShell from this B08 worktree. Provision an isolated copy of the approved B04 qualified Maven cache and use both B01 settings levels. The dual-cluster run used a local scope permitted to connect to G1-U-owned loopback ports 55480 and 55481. The launchers serialize their use of those ports. Generated database and application credentials travel through dedicated stdin or process memory. Native scratch files and named process logs remain inside the fixture scope. [B04 fixture contract](../../../fixtures/postgresql/README.md) · [G1-U](../B04/g1-u-approval.md)

~~~powershell
$phase = Split-Path (Get-Location)
$fixture = Join-Path $phase 'b08-fixture'
$cache = Join-Path $fixture 'repository'
$build = Join-Path $fixture 'launcher'
$logs = Join-Path $fixture 'logs'
$runs = Join-Path $fixture 'runs'
$settings = Join-Path $phase 'b01-tools/m2/settings.xml'
$bin = Join-Path $phase 'b04-fixture/runtime/pgsql/bin'
$env:JAVA_HOME = Join-Path $phase 'b01-tools/temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
New-Item -ItemType Directory -Force -Path $fixture,$build,$logs,$runs | Out-Null
if (!(Test-Path -LiteralPath $cache)) {
    Copy-Item -LiteralPath (Join-Path $phase 'b04-fixture/m2/qualified-repository') -Destination $cache -Recurse
}
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" clean verify
if ($LASTEXITCODE -ne 0) { throw 'Offline verification failed' }

[xml]$report = Get-Content -Raw -LiteralPath 'bootstrap/target/surefire-reports/TEST-com.company.requests.bootstrap.RequestsMvcSmokeTest.xml'
$runtime = ($report.testsuite.properties.property | Where-Object name -eq 'surefire.test.class.path').value
$extra = @(
    (Resolve-Path -LiteralPath 'adapter-jdbc/target/test-classes').Path,
    (Resolve-Path -LiteralPath 'adapter-authority/target/test-classes').Path,
    (Join-Path $cache 'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'),
    (Join-Path $cache 'org/checkerframework/checker-qual/3.55.1/checker-qual-3.55.1.jar')
)
$cp = ($build, $runtime, ($extra -join ';')) -join ';'
& $javac -encoding UTF-8 -classpath $cp -d $build fixtures/postgresql/QualifyPostgresql.java `
    fixtures/postgresql/B08MvcSession.java fixtures/postgresql/B08Postgresql.java `
    fixtures/postgresql/B05Postgresql.java fixtures/postgresql/B06Postgresql.java
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }

& $java -cp $cp B08Postgresql run $bin $runs b08-dual-reproduction
if ($LASTEXITCODE -ne 0) { throw 'B08 real-engine oracle failed' }
& $java -cp $cp B05Postgresql run $bin $runs b05-b08-reproduction-empty empty
if ($LASTEXITCODE -ne 0) { throw 'B05 empty fixture failed' }
& $java -cp $cp B05Postgresql run $bin $runs b05-b08-reproduction-prior prior
if ($LASTEXITCODE -ne 0) { throw 'B05 prior fixture failed' }
& $java -cp $cp B06Postgresql run $bin $runs b06-b08-reproduction
if ($LASTEXITCODE -ne 0) { throw 'B06 fixture failed' }
~~~

The temporary base-red source is retained in scratch. It ran against the pinned base with the same flags and `-pl bootstrap -am test '-Dtest=B08RouteRedTest' '-Dsurefire.failIfNoSpecifiedTests=false'`. Each real-engine run used a fresh ID. For interrupted B08 runs, after the fixture JVM exits, invoke `B08Postgresql cleanup` with the same binary path, runs directory and run ID. Ownership checks precede shutdown.

## Evidence hashes

| File | SHA-256 |
| --- | --- |
| B01 `settings.xml` | `e6617e035baec17673be3f90b4d169ca75e308121e6308f7e358dc2d5b07ac08` |
| `postgres.exe` | `4e67c961ac182a36c44a18fefcb5548d1ad5b40b252abb9cfa1259112d7faf75` |
| `postgresql-42.7.14.jar` | `73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c` |
| `checker-qual-3.55.1.jar` | `857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288` |
| `baseline-offline.log` | `8f44a56a17dd299ae472782c3ab36fd43b31b02874269cfe75fa66363aa9ad09` |
| `red-missing-protected-create.log` | `0f8da68d07cbc953047cf27675c3e04dbbbde146b240dcc02cc27257c39938b0` |
| `final-offline-verify.log` | `741132c6b856ab7de3a7b9998f14a2febc03060c575b1450c8ea36f7251d5ead` |
| `dual-01.log` | `da734ef0aefd5458dca8045c4be9311921fffd5d078bc03dda4b27755972f0a2` |
| `dual-02.log` | `35ea8624008a01af2dd6a6732f04b4a5fc76dd0a69dd53f14a2491872f2a953f` |
| `dual-03.log` | `8d3629f8f1ac54b6abfba2dbc8ca69ca1dbc05a28306dd31b2a2d6863528fbc3` |
| `mvc-worker-create.log` | `0dee2089d6d3880275665a4cd11e17f5a77c6a5fd0aa0d48c9fd3664f9618cf2` |
| `mvc-worker-replay.log` | `519ec1aca42135fbf1a7e4798513c6eb995164eba80965ff6e12aaa8a70d1133` |
| `b05-empty-regression.log` | `65b266fd5a9e651a8de4edac377c61d31455e7870557d259ab58de1053f4417c` |
| `b05-prior-regression.log` | `ce0cab18fdfd0d7d0ebe2f2c693ffb07f20542e7535759490b58166d136eda5f` |
| `b06-regression.log` | `ba26e64fa0b71ed282a9209bd4ce5fbdced684f1c7ae3e05ca93fd65a00ddf53` |

## Remaining acceptance

The evidence covers Spring MVC dispatch across separate application JVMs and real PostgreSQL. End-to-end Create and replay over a listening Servlet connector, with a connector process restart and real HTTP status and header evidence, await the standalone HTTP runtime disposition. The live proof certificate covers a single immutable generation. Restored receipt trust, writer replacement, mutable access and egress fencing use later B09–B12 designs. The fixture clock is a controlled test source. Connection-pool and service latency qualification remain later work. [Runtime gate](g0-b-http-runtime-request.md) · [Authority continuity](../../../Phase3-LLD/authority-continuity.md) · [Scope proof tickets](../../../Phase4-development-planning/scope-proof.md)
