# B47 Java 25 package boundary pilot

On 23 November 2026, both local rehearsals imported the exact B25 product classes with ArchUnit 1.5.1. The package and cycle rules passed for the product and valid assembly. The domain-to-Spring, HTTP-to-JDBC adapter and two-adapter cycle controls failed as expected. Each strict offline reactor passed all 436 cases across 20 suites. Independent B47 review is pending. [B47 acceptance](../../../Phase4-development-planning/tool-pilots.md#b47--t6-r-package-boundary-pilot) · [T6-R decision](../../../Phase3-LLD/tooling.md) · [Approved wiring](../../../Phase3-LLD/wiring.md)

## Inputs and provenance

- `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. G6-P approved ArchUnit core 1.5.1 and SLF4J API 2.0.19 for local scratch execution after B25 integration. The worker reran SHA-256, SHA-512 and byte-length verification in each rehearsal against verified input manifest `1227c89691c9b123097be84083d3099f7c1efc6bf22f39d4201b494fd6bbe568`. The two JARs total 4,676,012 bytes. [Approval](g6-p-approval.md) · [Pins and published checksums](../../../../tmp/phase5-development/b47-tools/verified-inputs.csv)
- Exact integrated and published B25 commit `87f8da7090936393808139bf1cd368739214b7bb`, tree `d15b2b7f0b11e2f4a80af0b95ce92babfd40a676`. Both final source exports matched the independent B25 source archive SHA-256 `daa36961a189dbd9cf05e10efce03fe8d5ae03f7f5e65c9c8ba0353dd0349d04`. The PR rehearsal used the default profile. The release rehearsal used `synthetic-http`. Each built a fresh archive with Maven 3.9.16, B01 settings and a scratch copy of the already qualified B25 offline repository. [B25 review](../B25/review-87f8da7.md) · [B25 publication receipt](../g0-publication-87f8da7.md) · [PR build record](../../../../tmp/phase5-development/b47-pilot/pr-lf/build.json) · [Release build record](../../../../tmp/phase5-development/b47-pilot/release-lf/build.json)
- Temurin HotSpot `25.0.5.1+2` supplied `java`, `javac` and `jdeps`. The local B01 JDK archive again matched SHA-256 `849d380ab0a64d9098e7d3a2de28beb73841c3b255c8284f019ccd8748c5eb5b`. Every product and seeded class had the Java class-file magic, minor `0` and major `69`. ArchUnit imported 418 default and 420 synthetic HTTP product classes, matching the per-JAR inventories. The release profile adds two bootstrap classes. [B01 JDK qualification](../B01/jdk-provisioning.md) · [PR JAR inventory](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/product-jars.csv) · [Release JAR inventory](../../../../tmp/phase5-development/b47-pilot/release-lf-check/product-jars.csv)
- The first source export inherited Windows `core.autocrlf=true`. Its `adapter-integration-jdbc` JAR differed from B25's reviewed JAR by 30 CRLF endings in `db/integration/outbox_scheduling_v1.sql`. Every class entry and all other JAR entries matched. LF normalization made the SQL contents equal. The final rehearsals use `git -c core.autocrlf=false archive` and verify the reviewed archive digest before building. The first importer smoke also exposed that a plain JAR path produced an empty import. The final harness uses explicit JAR locations and asserts the complete expected inventory and two product anchors. [Build script](../../../../tmp/phase5-development/b47-pilot/Build-B47.ps1) · [ArchUnit harness](../../../../tmp/phase5-development/b47-pilot/B47Boundaries.java)
- The final release contains all 420 reviewed B25 class entries byte-for-byte. Seven whole JAR hashes match B25. The three other JARs contain 31 older SQL resources that use LF in the B47 packages and CRLF in the B25 packages. The entry sets match and every changed entry is identical after line-ending normalization. The default and synthetic HTTP builds match on nine JAR hashes, with the expected bootstrap profile difference. [Whole JAR comparison](../../../../tmp/phase5-development/b47-pilot/jar-equivalence.csv) · [SQL entry comparison](../../../../tmp/phase5-development/b47-pilot/jar-resource-variance.csv) · [Product checkout check](../../../../tmp/phase5-development/b47-pilot/workspace-check.txt)

## Product dependency comparison

The Maven reactor graph assertion passed in each profile. It verifies the ten modules and all 26 declared direct project dependencies at `compile` scope. The domain POM also bans Spring and reactor artifacts. B01 recorded failure of both a temporary unauthorized direct POM edge and a Spring dependency added to domain. [Reactor graph test](../../../../bootstrap/src/test/java/com/company/requests/bootstrap/ReactorGraphTest.java) · [Domain POM](../../../../domain/pom.xml) · [B01 controls](../B01/build-evidence.md#red-and-green-evidence)

ArchUnit and the manual `jdeps` inspection found the same 19 cross-module bytecode edges in both profiles. Each edge is permitted. The product cycle rule passed. The seven further declarations belong to the two empty modules or bootstrap's permitted dependencies on them. `adapter-vendor` and `profile` each produce a JAR with zero classes in this revision. The valid seeded assembly exercised their seven permitted edges. [PR Maven graph](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/maven-project-edges.csv) · [Release Maven graph](../../../../tmp/phase5-development/b47-pilot/release-lf-check/maven-project-edges.csv) · [PR ArchUnit product](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/archunit-product.log) · [Release ArchUnit product](../../../../tmp/phase5-development/b47-pilot/release-lf-check/archunit-product.log)

| Origin | Permitted project targets checked by Maven | Product bytecode |
| --- | --- | --- |
| `domain` | None | None. `jdeps` reports `java.base` only. |
| `application` | Domain | All permitted edges observed. |
| `adapter-http`, `adapter-jdbc`, `adapter-authority` | Application, domain, for each adapter | All six permitted edges observed. |
| `integration` | Application, domain | Both permitted edges observed. |
| `adapter-integration-jdbc` | Application, domain, integration | All three permitted edges observed. |
| `adapter-vendor` | Application, domain, integration | Zero product classes. All three edges exercised by the valid specimen. |
| `profile` | Application, domain | Zero product classes. Both edges exercised by the valid specimen. |
| `bootstrap` | All nine other modules | Seven observed. Both empty-module edges exercised by the valid specimen. |

ArchUnit imported `@Configuration` on `RequestsConfiguration`, its `@Import(RequestsMvcConfiguration.class)` reference and the application `RequestActionCommand` reference to domain `Lifecycle`. `jdeps` shows the lifecycle reference and the Spring `@Import` type. It omits the `RequestsMvcConfiguration` class value on that annotation. `javap -v` confirms the value is in `RuntimeVisibleAnnotations` in both builds. The permitted bootstrap-to-HTTP module edge remains visible in `jdeps` through other classes. This is a class-level inspection difference. [Manual JDK review](../../../../tmp/phase5-development/b47-pilot/manual-jdeps-review.txt) · [PR `javap`](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/javap-bootstrap.log) · [Release `javap`](../../../../tmp/phase5-development/b47-pilot/release-lf-check/javap-bootstrap.log)

The product `RequestsMvcSmokeTest` checked Spring 7.0.11, Java 25 bytecode and two fresh in-process MVC initializations. `LifecycleTest` passed all 156 cases in each build, covering the state/action matrix for the initial types, the four terminal paths, edits, input constraints and the policy decision boundary. [PR test suites](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/surefire-summary.csv) · [Release test suites](../../../../tmp/phase5-development/b47-pilot/release-lf-check/surefire-summary.csv) · [Lifecycle design](../../../Phase3-LLD/domain.md)

## Positive and negative controls

Each control was compiled separately with `javac --release 25` and imported as an overlay on the product JARs. The exact same cases ran at both checkpoints. ArchUnit rule failures returned exit `1`. `jdeps` returned exit `0` for every case and reported the edges for manual classification. [Control sources](../../../../tmp/phase5-development/b47-pilot/fixtures) · [PR ArchUnit timing and exits](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/timings.csv) · [Release ArchUnit timing and exits](../../../../tmp/phase5-development/b47-pilot/release-lf-check/timings.csv)

| Case | ArchUnit package / cycle | Manual JDK check |
| --- | --- | --- |
| Product only | Pass / pass | 19 permitted direct project edges. |
| Valid assembly | Pass / pass | Bootstrap `@Configuration` and `@Bean` assembly references seeded vendor and profile types. Vendor uses application, domain and integration types. Profile uses application and `Lifecycle`. |
| Domain to Spring | Fail / pass | `DomainSpringSpecimen` calls `org.springframework.core.SpringVersion` from Spring Core 7.0.11. |
| HTTP to JDBC adapter | Fail / pass | `HttpJdbcSpecimen` references `adapter.jdbc.JdbcTransactionRunner`. |
| Two-adapter cycle | Fail / fail | `CycleDeliveryJdbc` and `CycleVendor` reference each other. ArchUnit reports `adapter-integration-jdbc → adapter-vendor → adapter-integration-jdbc` as a cycle between distinct slices. |

## Cost and limits

The following wall times include local process startup. The two checkpoints ran concurrently on the same workstation. The manual inspection took 101.2 seconds elapsed across both checkpoint traces and the annotation follow-up in one local observation. It checked the 19 observed edges against the 26 declared edges, then read the product anchors and control traces. [PR process timings](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/timings.csv) · [Release process timings](../../../../tmp/phase5-development/b47-pilot/release-lf-check/timings.csv) · [Manual inspection notes](../../../../tmp/phase5-development/b47-pilot/manual-jdeps-review.txt)

| Measured work | PR | Release |
| --- | ---: | ---: |
| Maven clean verify | 72.685 s | 73.946 s |
| ArchUnit product-only invocation | 7.091 s | 8.282 s |
| `jdeps` product-only invocation | 2.338 s | 1.961 s |
| ArchUnit product plus all four controls | 33.263 s | 36.327 s |
| `jdeps` product plus all four controls | 8.729 s | 7.657 s |
| Compile four control sets | 12.069 s | 11.829 s |

Maven enforces declared reactor dependencies and scopes, including declarations without corresponding class references. ArchUnit applies the approved package graph and cycle rule to compiled class references. The scratch rule uses a JDK-only external boundary for `domain`, `application`, `integration` and `profile` and permits external references in adapters and bootstrap. `jdeps` prints the class references, leaving allowance and cycles to manual review. The static checks cover the analyzed JARs and controls. Reflective access, runtime configuration and service behavior depend on separate tests such as the MVC bootstrap and lifecycle suites. The release rehearsal builds the synthetic profile locally and runs in-process tests. [PR JDK product trace](../../../../tmp/phase5-development/b47-pilot/pr-lf-check/jdeps-product.log) · [Release JDK product trace](../../../../tmp/phase5-development/b47-pilot/release-lf-check/jdeps-product.log)

The ArchUnit core JAR contains shaded ASM and Guava packages. The only separate pilot library is the pinned SLF4J API. It reports a no-provider warning and runs with its no-operation logger. The local checks verify artifact identity and compatibility. The current advisory assessment for the exact JARs, including shaded components, stays open for the build and security review associated with G6-M. Compulsory use requires a separate G6-M decision on the reviewed pilot. [Acquisition and upstream metadata](g6-p-request.md) · [G6-M authority](../../../Phase4-development-planning/deferrals-operating.md)

## Reproduce locally

From the workspace root in PowerShell 7, use fresh scratch output names. The scripts locate the existing qualified dependencies, verify the reviewed source archive and pinned tool bytes, build offline, and write JAR inventories, Maven graph and test summaries, ArchUnit and `jdeps` transcripts, and per-command timings. `Run-B47.ps1` analyzes the Maven packages and keeps every fixture under its analysis directory. [Build script](../../../../tmp/phase5-development/b47-pilot/Build-B47.ps1) · [Analysis script](../../../../tmp/phase5-development/b47-pilot/Run-B47.ps1)

```powershell
$pilot = './tmp/phase5-development/b47-pilot'
& "$pilot/Build-B47.ps1" -Checkpoint pr -Output "$pilot/repro-pr"
& "$pilot/Run-B47.ps1" -BuildDirectory "$pilot/repro-pr" -Output "$pilot/repro-pr-check"
& "$pilot/Build-B47.ps1" -Checkpoint release -Output "$pilot/repro-release"
& "$pilot/Run-B47.ps1" -BuildDirectory "$pilot/repro-release" -Output "$pilot/repro-release-check"
```

For the JDK comparison, start with the unindented JAR-to-JAR lines in `jdeps-product.log`, compare their project targets with `maven-project-edges.csv`, then inspect the class lines for `RequestsConfiguration`, `RequestActionCommand`, `Lifecycle` and the seeded classes in their respective `jdeps-*.log` files. The JDK command uses `--multi-release 25 -verbose:class -filter:none` with the resolved compile, provided and runtime JAR paths. The analysis requires a successful exit and no unresolved class dependencies. Inspect class-valued annotations with the same JDK's `javap -v -classpath <bootstrap-jar> com.company.requests.bootstrap.RequestsConfiguration`.

The 84-entry SHA-256 manifest has SHA-256 `57b712695809eb36752a5c0b3d97df15c743d5e4f10f27c5bbd4e16f6d906de2`. [Scratch evidence manifest](../../../../tmp/phase5-development/b47-pilot/pilot-evidence.sha256)
