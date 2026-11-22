# B43 · Local security advisory and critical quality evidence

**Scope** E7, Q-06 and Q-07, executed on 23 November 2026 (Europe/Warsaw). B43 uses the approved B01 reactor, B02 lifecycle checks and B45 contract baseline. The initial reactor and inventory execution checked B45 `231589477a114b00fc127855e0598a0d56dc6727`, tree `8b07b3fb595eec3c2968740ff3c0962cf3f2b299`. The approved instrumented Lifecycle run checked B24 `e550379a754a686b85903d233c9d7d2061bd5d7a`, tree `1e3791a5a3f0274e2ce4dbe2512e406927820d82`. `PH4-PLAN-r1` matched its approved SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, and all 30 entries matched. Sol xhigh (56) executed. Independent Astra xhigh (62) disposition is pending. [B43 acceptance](../../../Phase4-development-planning/operating-verification.md#b43--security-advisory-and-critical-quality-controls--e7) · [Standing Phase 5 authority](../g0-remaining-plan-approval.md) · [D6-R](../../../Phase3-LLD/operations.md)

## Resolved project dependencies

The private exporter walks every reactor module's selected JSON dependency tree, including the parent POM, using the pinned Maven Dependency Plugin 3.10.0 output. For each selected node it retains the profile, module, direct or transitive relationship, scope, exact coordinate and parent chain. The external index is one row per coordinate, type and classifier with the observed scopes and relationship flags. The input manifest pins each raw Maven tree by SHA-256. All outputs sort ordinally and use UTF-8 with LF. The default and synthetic HTTP inventories include compile, provided and test resolutions. The standalone fixture POM, Maven plugin class realms, JDK and OS packages, and artifacts assembled outside Maven's reactor graph require their own inventories. [Exporter](../../../../fixtures/b43-release-evidence/Export-B43Inventory.ps1) · [Reactor and plugin configuration](../../../../pom.xml) · [B01 plugin realm review](../B01/build-evidence.md)

The synthetic HTTP profile contributes the direct embedded Tomcat core 11.0.29 and transitive Tomcat annotations API 11.0.29. The public advisory review records dated zero-finding results for six exact Snyk coordinates, scoped Spring range comparisons, a version-applicable and locally unexposed Tomcat issue, and the remaining exact-version source gaps. Both local review dispositions are incomplete. The evidence owner routes the open items to the build/security lead and repeats the exact lookup against the release inventory. [Dated advisory records](advisory-review.md)

| Profile | Selected dependency rows | External coordinate count and relationships | Maven tree inputs |
| --- | ---: | --- | ---: |
| Default | 119, with 26 reactor direct, 23 external direct and 70 external transitive rows | 27 total. 7 direct only, 17 transitive only and 3 with both relationships. | 11 |
| Synthetic HTTP | 121, with 26 reactor direct, 24 external direct and 71 external transitive rows | 29 total. 8 direct only, 18 transitive only and 3 with both relationships. | 11 |

Both B45 profiles passed all 432 tests in 19 suites and 11 reactor projects. Each export repeated byte for byte. The fresh synthetic HTTP export matched the earlier independently verified B45 archive across the resolved rows, external index and raw tree input manifest. A disposable copy missing the domain tree failed before writing outputs. Placing the reactor parent's JSON at the domain path also failed before writing outputs. Restoring the exact domain JSON regenerated all three files byte for byte. These checks used PowerShell 7.6.9. [Prior B45 verification](../B45/evidence.md#reproduction)

The reactor POMs and Lifecycle source and tests are byte-identical across B45 and B24. The independently built B24 synthetic HTTP trees reproduced the sealed B45 exports. The final B43 verification on exact B24 ran both offline `clean verify` profiles, and each passed 436 tests in 20 suites and 11 reactor projects. Every export matched its repeat and the corresponding B45 file byte for byte. The final synthetic HTTP external coordinate set matched all 29 rows in the dated Snyk ledger. [Dated coordinate ledger](snyk-lookups.tsv)

## Critical quality sample

The owner approved official JaCoCo `0.8.16` for local measurement of three B02 Lifecycle methods. On exact B24, the agent instrumented `com.company.requests.domain.Lifecycle` while the existing `LifecycleTest` ran under Maven 3.9.16 and Temurin `25.0.5.1+2` in strict offline mode. All 156 tests passed. The CLI reported `0.8.16.202610240946` and used the exact class file from the test run. The table compares the three approved methods to the reference maximum cyclomatic complexity of 10 and minimum branch coverage of 90%. [JaCoCo release and checksum](https://www.jacoco.org/jacoco/download/jacoco-0.8.16.zip.sha256.txt) · [Coverage and complexity counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html) · [Lifecycle](../../../../domain/src/main/java/com/company/requests/domain/Lifecycle.java) · [Test cases](../../../../domain/src/test/java/com/company/requests/domain/LifecycleTest.java)

| Selected method | JaCoCo cyclomatic complexity | JaCoCo branches covered / total | B45 manual trace |
| --- | ---: | ---: | --- |
| `Lifecycle.nextFromStart` | 2 | 2/2 | `startOnlyAllowsCreate`, 21 cases. CREATE and ineligible actions. |
| `Lifecycle.next` | 2 | 2/2 | `exactMatrixForExistingRequests`, 126 cases. Eligible and ineligible pairs. |
| `Lifecycle.transition` | 4 | 5/5 | `allFourTerminalPathsHaveContiguousHistory`, 3 cases, plus the transition matrix. The publication-ID decision and all three reason outcomes. |

All three methods meet the reference, with 9/9 branches covered in the local test run. The earlier B45 disassembly and Surefire trace gave the same nine outcomes. The final B24 profile builds matched the class digest from the instrumented run, and the synthetic HTTP domain JAR contained that same class. Both profiles passed all 156 Lifecycle tests and `ReactorGraphTest`. The approved agent and reporter ran from workspace scratch. The production critical-path set and its coverage, concurrency, migration and release coverage, compulsory checks and exception authority remain open. G6-M and G7 govern further tool and policy adoption. [Approved build graph test](../../../../bootstrap/src/test/java/com/company/requests/bootstrap/ReactorGraphTest.java) · [Production gate ownership](../../../Phase4-development-planning/deferrals-operating.md)

## Reference decision cases

The opt-in fixture checks the approved example dispositions. Missing inventory, missing or stale review, missing prerelease review, review bound to another release or digest, and partial source coverage block the reference decision. An applicable unresolved exposed high blocks. A documented unexposed critical item is tracked, and an undocumented case requires an exposure decision. The complete review with zero findings and the seven-day weekly boundary pass. These examples use an explicit rehearsal release ID and seven-day comparison, and attach the actual supplied inventory digest. [Seeded decision fixture](../../../../fixtures/b43-release-evidence/Test-B43ReferenceCases.ps1)

The seeded method has eleven independent `if` decisions. The private Java run returned `input=0 enabled=0`. Given the source predicates, this exercises all eleven false outcomes and supplies zero true outcomes, for 11/22 manually observed outcomes and cyclomatic complexity 12. The combined case escalates both measures. Separate controls escalate one measure at a time and accept the exact 10 and 90% reference boundaries. The fixture runs by explicit invocation outside the Maven reactor. [Seeded Java method](../../../../fixtures/b43-release-evidence/B43SeededCriticalMethod.java)

## Reproduction

Use PowerShell 7, Maven 3.9.16, Temurin 25.0.5.1+2 and the already qualified local cache. Use the approved settings for both settings flags. Run serially from the exact checkout and export each profile immediately after its build, because the next `clean` replaces the trees. No service port is required. The settings SHA-256 is `e6617e035baec17673be3f90b4d169ca75e308121e6308f7e358dc2d5b07ac08`. Maven's launcher emitted a single `Odmowa dostępu.` diagnostic before startup, also present in the independent B45 log. Both B43 commands returned exit 0 with `BUILD SUCCESS`. [B01 Maven adoption](../B01/g0-b-adoption.md) · [Prior B45 verification](../B45/evidence.md#reproduction)

```powershell
$phase = Split-Path $PWD
$out = Join-Path $phase 'b43-release-evidence-run/reproduction'
New-Item -ItemType Directory -Path $out | Out-Null
$runtime = Join-Path $phase 'b01-tools'
$env:JAVA_HOME = Join-Path $runtime 'temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$settings = Join-Path $runtime 'm2/settings.xml'
$maven = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$cache = Join-Path $out 'repository'
Copy-Item -LiteralPath (Join-Path $phase 'wave2-integrated/repository') -Destination $cache -Recurse
$flags = @('-o', '-B', '-C', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$cache")
$export = './fixtures/b43-release-evidence/Export-B43Inventory.ps1'

& $maven @flags clean verify
if ($LASTEXITCODE -ne 0) { throw 'Default verification failed.' }
& $export -TreeRoot $PWD -Profile default -OutputDirectory $out
& $maven @flags -Psynthetic-http clean verify
if ($LASTEXITCODE -ne 0) { throw 'Synthetic HTTP verification failed.' }
& $export -TreeRoot $PWD -Profile synthetic-http -OutputDirectory $out
& './fixtures/b43-release-evidence/Test-B43ReferenceCases.ps1' `
    -InventoryPath (Join-Path $out 'synthetic-http-resolved.tsv') -JavaHome $env:JAVA_HOME
& (Join-Path $env:JAVA_HOME 'bin/javap.exe') -p -c -classpath 'domain/target/classes' `
    com.company.requests.domain.Lifecycle
```

Repeat the exporter to a clean output directory and compare the three digests. For the missing and wrong-root controls, copy the exact root and module POMs and tree files into a scratch overlay while omitting the domain JSON. Repeat with the root tree at the domain path. Both runs must reject the input before creating the output directory. Restore the domain tree and compare the generated hashes to the complete source outputs.

To reproduce the approved coverage sample, use exact B24 and the same Maven settings, cache and Temurin. Verify the approved agent and CLI digests before launching the targeted suite. The reporter reads the exact compiled Lifecycle class and writes an XML report. Read the `BRANCH` and `COMPLEXITY` counters for the three named methods. [JaCoCo CLI reporting](https://www.jacoco.org/jacoco/trunk/doc/cli.html)

```powershell
$jacoco = Join-Path $phase 'b43-tools/v0.8.16'
$agent = Join-Path $jacoco 'jacocoagent.jar'
$cli = Join-Path $jacoco 'jacococli.jar'
$coverage = Join-Path $out 'coverage'
New-Item -ItemType Directory -Path $coverage | Out-Null
$data = Join-Path $coverage 'Lifecycle.exec'
$report = Join-Path $coverage 'Lifecycle.xml'
$class = 'domain/target/classes/com/company/requests/domain/Lifecycle.class'
$agentArg = "-DargLine=-javaagent:$agent=destfile=$data,append=false,includes=com.company.requests.domain.Lifecycle"
& $maven @flags -pl domain '-Dtest=LifecycleTest' $agentArg clean test
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle coverage run failed.' }
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -jar $cli report $data --classfiles $class --xml $report
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle report generation failed.' }
```

## Approved instrumented coverage fingerprints

The owner receipt `Documentation/Phase5-development/work/B43/coverage-approval.md` in the Phase 5 workspace is sealed at `6f524ab467d01854266799abd35c21318bcf2b13e8c1e852766f674ed4a41f13`. Raw measurement and report files are in `tmp/phase5-development/b43-tools/lifecycle-e550379`. The verified copy, exact class and receipt are under `tmp/phase5-development/b43-release-evidence-run/b24-coverage-linked`. Its `coverage-link.sha256` digest is `e2bcc67c63a228736428446a9348088391fb581f2f5ce70ba842caf04c46f6e4`. The approved archive and verified JARs are in the B43 tools scratch directory.

| Artifact | SHA-256 |
| --- | --- |
| Official `jacoco-0.8.16.zip` | `a7b90535b30a7159dab62d8db0b276ed1f7bc73b7c2354d314503cd34b9c00ba` |
| `jacocoagent.jar` | `37ae2f374ad77e85c59c6e5b46565226c3ac3cb460ac19de0bcb844ac2f43e33` |
| `jacococli.jar` | `1f2a806225623fbd9f53d1ee0e42a6975323de0adebe01c12fc464ed147d756a` |
| `Lifecycle.class` | `1edaeca805f6d6204533a65b623d6fa210fd051697b876c17c6e1a0855e8d955` |
| `Lifecycle.exec` | `b1083b538a9f77173bec8f9af713ca5bf7d9b8d659891a82fdc54b8c8c9d846a` |
| `Lifecycle.xml` | `a6068c1e53529ecd82f388341fbc5933c1f810afd37de85199ba844509b21d96` |
| `three-methods.json` | `3e12b1087e92ad9015e89c0d160beaea0dea778c40c24bc4c16b5a0468d1352b` |
| `maven-lifecycle.log` | `2da45d5b7cf4a3ee16592cee6654873fee309dee9045da1b4d9f82e8bd83bab9` |

## Initial checkpoint fingerprints

The raw outputs are in `tmp/phase5-development/b43-release-evidence-run/b45-initial` in the Phase 5 workspace. These fingerprints cover the two profile builds, inventories, branch census inputs and all controls.

| File | SHA-256 |
| --- | --- |
| `default-clean-verify.log` | `78a5e12f7f8bd5e49a0dc1e5c3a4bf40c3a101c23337819cb66af8dc2314c839` |
| `synthetic-http-clean-verify.log` | `b845ea5f4b5aaf355de33863d91468ccaf9aaacb058dbdfbc905ecc72157ad46` |
| `default-resolved.tsv` | `80d2a859b1f4ba850d9f2e3e3a40d3333f99ca9c8cb7702781761a9593761eb9` |
| `synthetic-http-resolved.tsv` | `7f000aef75941bcd693c7426e78ede943a90782b46575604fc18d1aae8d33913` |
| `default-external.tsv` | `86aa43f49f8a811b7b9608247e22fbee977be71d875290520cdeaef72a4c3991` |
| `synthetic-http-external.tsv` | `3d898a1cea5c0e3e8a53b7c2431d4da185ca51c3f645fca53998901fe2838587` |
| `default-tree-inputs.tsv` | `c3e9f0702036c8ee14999b4b58e3705474ac2baa5c9bb3ad284ed38a78502b9b` |
| `synthetic-http-tree-inputs.tsv` | `b307aef7b118f4ecb9d96ae594471508d492716d224189236990baaff83e8376` |
| Both disassembly logs | `5ba3e104ae88f6de73445e2e708d7e49effda484aaf4f9a3dd33a963d91f657a` |
| `default-lifecycle-test.xml` | `eeeba33613fcf897437761b8e3f4a706809a9f569dd0a6b3a647731bc90d4eb6` |
| `synthetic-http-lifecycle-test.xml` | `f6f3b3ceade35d984313ee14526ec70e754c133ae616b13a249d3689129ac7aa` |
| `inventory-input-controls.log` | `30ec254698735659f42fa36b7e219a555304c108730e80561c33aedecbffc8d8` |
| `reference-cases.log` | `ba9014bb7b7b2eded52e37845eef047e5d0f89b4cccfd75b66990ddffaeba8fb` |

## Final B24-based verification fingerprints

The raw outputs are in `tmp/phase5-development/b43-release-evidence-run/b24-final`. Both Maven runs returned exit 0 with `BUILD SUCCESS`. The repeated decision transcript contains all 18 passing assertions and matches the B45 transcript. The synthetic HTTP package scan found zero private B43 classes across ten module JARs. All six inventory files have the B45 fingerprints above.

| File | SHA-256 |
| --- | --- |
| `default-clean-verify.log` | `946178dfd510ca1e760c034b6fff52bb648ac40466f9bd1dcb4f58d1357c1a5f` |
| `synthetic-http-clean-verify.log` | `ba031b083b42e207b4ea0e105fb1fd8b1b3e81ff8aa989b758c319e984ddd01c` |
| `verification-summary.log` | `393206aa4201b372478a4ddf1bf6bfcf202fab8565b0d8126a0d7f797aacadd8` |
| `package-class-check.log` | `c2ed45eda69c9049b6735fc3f7c3cbf8ec379aa6aeec0cf4a8751cd16dc0d7e7` |
| `reference-cases.log` | `ba9014bb7b7b2eded52e37845eef047e5d0f89b4cccfd75b66990ddffaeba8fb` |

## Handoff

- B48 can cite the two offline regression configurations, B45 contract cases and the bounded quality sample. Attach the advisory source gaps to the private evidence package and tie them to its exact snapshot. [B48 acceptance](../../../Phase4-development-planning/delivery.md#b48--private-interview-walkthrough-and-source-only-package)
- B55 can use the evidence layout for target review. Record the target's exact dependencies, configuration, exposure, owners and policy, and collect the applicable production operating evidence. [B55 acceptance](../../../Phase4-development-planning/production-release.md#b55--production-operating-policy-and-topology-rehearsal)
- B56 needs the complete current weekly and prerelease reviews, an approved risk disposition and agreed critical-path evidence for the exact release build. This rehearsal's incomplete public source coverage remains a blocker under the reference rule. G6-M and G7 govern real release checks. [B56 acceptance](../../../Phase4-development-planning/production-release.md#b56--final-production-release-decision)
