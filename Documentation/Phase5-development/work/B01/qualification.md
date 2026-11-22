# B01 · Build qualification before G0-B

**Disposition, 2026-11-22 Europe/Warsaw.** The installed Java 25 compiler and runtime passed the isolated Java smoke. Cached Maven and Gradle launched on that JDK. The Spring preflight failed on the missing exact Spring Framework 7.0.11 BOM and MVC JAR. The seed also has no project build or module graph to compile. The application smoke and B01 acceptance remain pending.

**Revision.** `PH4-PLAN-r1`, SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. Evidence against `98c5802c2efc512d14dabf0a6ab59bd567571754` on `codex/b01-qualification`. Executor GPT-6.2 Sol high (55). Assigned independent reviewer GPT-6.2 Astra medium (57), disposition pending. This is evidence-only with no candidate commit. Local probes used existing binaries. Network lookups used public metadata and advisories. [Assignment](assignment.md) · [G0 receipt](../g0-kickoff.md) · [B01](../../../Phase4-development-planning/foundation.md#b01--reproducible-spring-build)

## Local observations and reproduction

From the assigned worktree, `git rev-parse HEAD` returned the base above and `git status --short --branch` was clean before scratch. `git ls-files` found no tracked Java, JAR, WAR, POM, Gradle build/settings, or wrapper. `Get-Command java,javac -All` resolved first to Temurin 21.0.13+9 and also found Temurin 11.0.24. `java -version` and `javac -version` confirmed 21.0.13. `JAVA_HOME` was unset. `Get-Command mvn,gradle,mvnw,gradlew,docker,podman -ErrorAction SilentlyContinue` found no commands. Maven and Gradle home overrides were unset.

```powershell
$jdk = Join-Path $env:USERPROFILE '.jdks/openjdk-25.0.2/bin'
$scratch = Join-Path (Get-Location).Path '.b01-probe'
$null = New-Item -ItemType Directory -Path $scratch -Force
@'
public class B01Java25Smoke {
    static void main() {
        var version = Runtime.version();
        if (version.feature() != 25) {
            throw new AssertionError("Expected Java 25, got " + version);
        }
        IO.println("B01_JAVA25_SMOKE_OK runtime=" + version);
    }
}
'@ | Set-Content -LiteralPath (Join-Path $scratch 'B01Java25Smoke.java') -Encoding ascii
& "$jdk/javac.exe" --release 25 -d $scratch (Join-Path $scratch 'B01Java25Smoke.java')
& "$jdk/javap.exe" -verbose -classpath $scratch B01Java25Smoke | Select-String 'major version'
& "$jdk/java.exe" -cp $scratch B01Java25Smoke
& java -cp $scratch B01Java25Smoke
```

Absolute `java.exe -version` and `javac.exe -version` identified Oracle OpenJDK `25.0.2+10-69` and compiler `25.0.2`, x86-64. The first three smoke invocations returned 0, produced class-file major 69, and printed `B01_JAVA25_SMOKE_OK runtime=25.0.2+10-69`. The final PATH Java invocation returned 1 with `UnsupportedClassVersionError` and a supported maximum major of 65.

The standard wrapper caches held Maven 3.10.0 and 3.9.11 and Gradle 9.2.1. Run this version-only probe in a child shell so the variables stay process-local. The Maven launches each printed one localized access-denied line (`Odmowa dostępu.`) and returned 0 with the requested Oracle Java 25.0.2. The Gradle launch returned 0 with Java 25.0.2 for both launcher and daemon. These were existing distribution launches. Wrapper bootstrap and a project build remain unverified.

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/openjdk-25.0.2'
$env:MAVEN_SKIP_RC = 'true'
$mavens = Get-ChildItem -LiteralPath "$env:USERPROFILE/.m2/wrapper/dists" -Recurse -Filter mvn.cmd |
    Where-Object { $_.FullName -match 'apache-maven-(3\.10\.0|3\.9\.11)-bin' }
foreach ($maven in $mavens) {
    & $maven.FullName --version
    "maven_exit=$LASTEXITCODE"
}
$gradle = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/wrapper/dists" -Recurse -Filter gradle.bat |
    Where-Object { $_.FullName -match 'gradle-9\.2\.1-bin' } |
    Select-Object -First 1
$gradleProbeHome = Join-Path (Get-Location).Path '.b01-probe/gradle-user-home'
& $gradle.FullName --gradle-user-home $gradleProbeHome --offline --no-daemon --version
"gradle_exit=$LASTEXITCODE"
```

In the standard Maven repository, a recursive read-only listing under `org/springframework` found Framework 7.0.10 binaries and zero Spring 7.0.11 JARs or POMs. The default Gradle modules cache `~/.gradle/caches/modules-2/files-2.1` was absent. The reproducible first prerequisite check returned 1 and printed both missing coordinates. The Spring smoke has no application assertion result.

```powershell
$root = Join-Path $env:USERPROFILE '.m2/repository'
$targets = @(
    'org/springframework/spring-framework-bom/7.0.11/spring-framework-bom-7.0.11.pom',
    'org/springframework/spring-webmvc/7.0.11/spring-webmvc-7.0.11.jar'
)
$missing = $targets | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_) -PathType Leaf) }
if ($missing) {
    $missing | ForEach-Object { "B01_SPRING_PREREQUISITE_MISSING $_" }
    exit 1
}
'B01_SPRING_PREREQUISITE_PRESENT'
```

## Proposed qualification pins

| Option | Exact candidate and reproducibility | Open evidence |
| --- | --- | --- |
| **Local Gradle wrapper, recommended** | Gradle **9.9.2** binary ZIP, SHA-256 `971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b`, wrapper JAR SHA-256 `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`. Gradle supports running on Java 25 from 9.1.0. Use built-in Java library/application tooling, a Java 25 toolchain with auto-provisioning disabled, strict dependency locks and reviewed verification metadata. Version the wrapper and select a scratch Gradle user home. [Compatibility](https://docs.gradle.org/current/userguide/compatibility.html) · [Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) · [Checksums](https://gradle.org/release-checksums/) · [Dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) · [Dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html) | 9.9.2 and its wrapper were not run. The cached 9.2.1 launcher is local evidence. |
| Local Maven wrapper | Apache Maven **3.10.2**, Maven Wrapper **3.3.4**, `only-script`, explicit plugin pins, Maven Java 25 enforcer and compiler `release=25`. Exact plugin candidates: compiler **3.15.0**, Surefire **3.5.6**, Enforcer **3.6.2**, Dependency **3.10.0**. Manage indirect versions and review convergence and the dependency tree. Apache publishes distribution signatures and SHA-512, and the wrapper consumes SHA-256. Authenticate the released ZIP before deriving the wrapper checksum. [Maintained versions](https://maven.apache.org/docs/history.html) · [Distribution](https://maven.apache.org/download.cgi) · [Wrapper](https://maven.apache.org/tools/wrapper/) · [Wrapper release](https://github.com/apache/maven-wrapper/releases/tag/maven-wrapper-3.3.4) | Exact distribution checksum content and Maven 3.10.2 launch were unverified. The 3.10.0 and 3.9.11 launch warnings need investigation if Maven is selected. |
| Managed runner fallback | GitHub-hosted **`windows-2025`**, exact Temurin JDK **`25.0.5.1+2`**, same pinned wrapper, read-only repository token, record runner image build. Supplied checkout/setup actions can be commit-pinned and JDK archives verified. A hosted image label receives updates. [Windows runner inventory](https://github.com/actions/runner-images/blob/main/images/windows/Windows2025-Readme.md) · [Java setup and checksum verification](https://github.com/actions/setup-java) | Repository and runner access, checkout/setup commits, exact runtime, workflow and run are unqualified. Any source transfer or workflow activation needs separate owner scope. |

**Java candidate.** Use Temurin x64 HotSpot **25.0.5.1+2**, portable Windows archive `OpenJDK25U-jdk_x64_windows_hotspot_25.0.5.1_2.zip`, published asset SHA-256 `849d380ab0a64d9098e7d3a2de28beb73841c3b255c8284f019ccd8748c5eb5b`. Adoptium identifies this patched release for CVE-2026-114938 and Oracle lists its corresponding 25.0.5.1 patch from November 17. The installed 25.0.2 was suitable for the isolated compile/runtime inventory. Archive verification and execution on the new JDK remain pending. [Temurin security guidance](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/) · [Temurin assets](https://github.com/adoptium/temurin25-binaries/releases/expanded_assets/jdk-25.0.5.1%2B2) · [Oracle patch](https://docs.oracle.com/en-us/iaas/releasenotes/java-management/jdk-cspu-november-2026.htm)

### Direct and transitive metadata

| Scope | Exact candidate or expectation | Metadata basis |
| --- | --- | --- |
| Framework | `org.springframework:spring-framework-bom:7.0.11` with `spring-webmvc` for the HTTP adapter, `spring-jdbc` for JDBC adapters, and test-only `spring-test` for MVC context. Explicit direct `spring-context`, `spring-web` or `spring-tx` where the module imports their types. | Upstream 7.0.11 release, November 19. Spring 7 recommends JDK 25 and uses Jakarta EE 11. Exact 7.0.11 Maven POM bodies and bytes were inaccessible in this environment. [Release](https://github.com/spring-projects/spring-framework/releases/tag/v7.0.11) · [Platform baseline](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes) |
| Framework closure | Candidate Framework modules `spring-aop`, `spring-beans`, `spring-context`, `spring-core`, `spring-expression`, `spring-web`, `spring-tx` all **7.0.11** wherever brought in. Non-Spring expected coordinates: `commons-logging:commons-logging:1.3.5`, `org.jspecify:jspecify:1.0.1`, `io.micrometer:micrometer-observation:1.16.9` and `micrometer-commons:1.16.9`. | 7.0.10 cached POM shape used for the forecast. It declares observation **1.16.8**, commons-logging **1.3.5**, and JSpecify **1.0.1**. Tagged Spring Core 7.0.11 source confirms logging and JSpecify names. Micrometer 1.16.9 is a published candidate, and actual Framework 7.0.11 selections remain open. [Spring Core source](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.11/spring-core/spring-core.gradle) · [Micrometer release](https://github.com/micrometer-metrics/micrometer/releases/tag/v1.16.9) |
| Servlet and tests | `jakarta.servlet:jakarta.servlet-api:6.1.0` for compilation and mock servlet testing. `org.junit:junit-bom:6.1.3`, `org.junit.jupiter:junit-jupiter:6.1.3` and test runtime `org.junit.platform:junit-platform-launcher:6.1.3`. Expect Jupiter API/engine/params and Platform commons/engine at **6.1.3**, with the API support dependency versions to verify from the graph. | Publisher declarations. Set up the in-process mock servlet context without a network listener. [Servlet](https://jakarta.ee/specifications/servlet/6.1/) · [JUnit release](https://github.com/junit-team/junit-framework/releases/tag/r6.1.3) |
| B03 JSON and later Servlet runtime | `tools.jackson:jackson-bom:3.1.8` and `tools.jackson.core:jackson-databind:3.1.8`, with `jackson-core:3.1.8` and `com.fasterxml.jackson.core:jackson-annotations:2.21` expected. `org.apache.tomcat.embed:tomcat-embed-core:11.0.29` is a separate runtime candidate. | Jackson LTS series and tagged BOM. Tomcat 11.0.29 is the Jakarta EE 11 release publicized November 13. This proposal uses the Servlet API for the mock smoke and defers the container. [Jackson release](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1) · [Jackson BOM](https://raw.githubusercontent.com/FasterXML/jackson-bom/jackson-bom-3.1.8/pom.xml) · [Tomcat](https://tomcat.apache.org/) |
| Deferred qualification | JWT/OAuth2 candidate if identity design authorizes it: `org.springframework.security:spring-security-bom:7.0.9` and matching `spring-security-config`, `spring-security-web`, `spring-security-oauth2-resource-server`, and `spring-security-oauth2-jose`. Resolve Nimbus and any other transitive dependencies at that decision. B04 pgJDBC candidate `org.postgresql:postgresql:42.7.14`, with exact driver transitives and PostgreSQL minor to verify under B04/G1. Production pool and identity approvals remain separate. | Spring Security stable reference and pgJDBC release. [Spring Security 7.0](https://docs.spring.io/spring-security/reference/7.0/whats-new.html) · [pgJDBC 42.7.14](https://jdbc.postgresql.org/changelogs/2026-10-07-42.7.14-release/) |

The web tool could inspect Spring's release and tagged Spring Core source. Maven Central `repo.maven.apache.org`, `repo1.maven.org`, Sonatype exact artifact pages and Spring's artifact endpoint failed to return the 7.0.11 POMs. A shell metadata-only `Invoke-WebRequest` for the 7.0.11 MVC POM failed DNS (`Nieznany host`). These are retrieval gaps. No dependency graph was resolved, and exact released artifacts, checksums and actual versions still require an authorized resolution. Treat every closure entry as a candidate until independently checked.

## Advisory lookup on 2026-11-22

| Evidence | Applicability |
| --- | --- |
| Spring's 2026-11-19 Framework notices identify 7.0.11 as the OSS fix for indexed binding CVE-2026-59374, trailing-slash redirect CVE-2026-59394, route regex CVE-2026-59429 and SpEL CVE-2026-59430/59431. [Binding](https://spring.io/security/cve-2026-59374/) · [Redirect](https://spring.io/security/cve-2026-59394/) · [Route matching](https://spring.io/security/cve-2026-59429/) · [SpEL conversion](https://spring.io/security/cve-2026-59430/) · [SpEL selection](https://spring.io/security/cve-2026-59431/) | MVC input binding is relevant to B03. The seed has no executable MVC routes, redirect filter or input-aware SpEL. Recheck actual adapter configuration. The cached Framework 7.0.10 falls in the affected version range and is unsuitable as a substitute for this oracle. |
| Spring Security's 2026-11-19 CVE-2026-59273 covers 7.0.0–7.0.8 and is fixed in 7.0.9. CVE-2026-59400 is fixed in 7.0.9 as well. [Matcher advisory](https://spring.io/security/cve-2026-59273/) · [Client registration advisory](https://spring.io/security/cve-2026-59400/) · [Spring advisory index](https://spring.io/security/) | Track separately if the servlet security adapter is introduced. The qualification smoke requires no Security dependency, matcher input binding or OAuth2 client registration. The cached 7.0.8 is outside the proposal. |
| Snyk reported CVE-2026-59429 as SNYK-JAVA-ORGSPRINGFRAMEWORK-22727194 for `spring-web` versions below 7.0.11. Its MVC package list returned latest 7.0.10 and older direct findings. Exact `spring-webmvc:7.0.11` and Security candidate pages were unavailable. [Snyk advisory](https://security.snyk.io/vuln/SNYK-JAVA-ORGSPRINGFRAMEWORK-22727194) · [MVC package](https://security.snyk.io/package/maven/org.springframework%3Aspring-webmvc) | Individual package rows cover direct issues. The vendor's 7.0.11 fix determines the response to this CVE. A fresh exact direct/transitive Snyk result is still required before B01 completion. |
| Snyk lists pgJDBC CVE-2026-107314 for `[42.7.11,42.7.14)`, and CVE-2026-107315 for `[42.7.4,42.7.14)`. Exact Snyk coverage for Jackson 3.1.8, Tomcat 11.0.29 and pgJDBC 42.7.14 could not be independently confirmed. [pgJDBC release](https://jdbc.postgresql.org/changelogs/2026-10-07-42.7.14-release/) · [Authentication advisory](https://security.snyk.io/vuln/SNYK-JAVA-ORGPOSTGRESQL-20571163) · [Buffer reuse advisory](https://security.snyk.io/vuln/SNYK-JAVA-ORGPOSTGRESQL-20571178) | 42.7.14 is outside both reported ranges. B04 rechecks the selected driver and runtime profile before fixture use. Review Jackson in the B01 graph if included and Tomcat upon runtime selection. |

## G0-B request and successor handoff

1. Approve bounded B01 qualification with the **Gradle 9.9.2 wrapper**, **Temurin 25.0.5.1+2**, and the direct Framework, Servlet, JUnit and Jackson candidates above. Authorize obtaining and verifying those public tools and Maven Central dependencies/plugin artifacts, extracting the portable JDK and using isolated repository-local or temporary caches and process-local toolchain settings. Name the allowed repository or artifact mirror if Maven Central access requires one. Obtain reviewer agreement on exact wrapper and distribution hashes before use. Keep any material resolved-version variance pending owner disposition before adoption.
2. On that approval, build the module graph under `com.company.requests` and add an in-process `AnnotationConfigWebApplicationContext`/mock servlet startup with explicit `@Configuration` and `@Bean`. Assert Java release/runtime 25, `SpringVersion.getVersion()` equals 7.0.11, and context refresh exposes the configured MVC mapping and adapter beans. Exercise a negative control for an invalid version or edge. Record `gradlew.bat --version`, `gradlew.bat --no-daemon clean check`, per-module `dependencies --configuration compileClasspath/runtimeClasspath/testRuntimeClasspath`, and `dependencyInsight --dependency spring-core --configuration testRuntimeClasspath` for the smoke module. Record exact artifacts, checksums, selected transitive versions and dated Spring/Snyk recheck on the graph, then verify a repeat with `--offline`. Independent Astra review owns disposition at the exact implementation commit.
3. Enforce these directed project dependencies, using Maven or Gradle project names that map to the packages below. Check actual direct and transitive graphs and the domain compile classpath. One bootstrap composes the release. [Accepted assembly](../../../Phase3-LLD/wiring.md)

| Module | Permitted project dependencies |
| --- | --- |
| `domain` | none |
| `application` | `domain` |
| `adapter.http`, `adapter.jdbc`, `adapter.authority`, `integration`, `profile` | `application`, `domain` |
| `adapter.integration.jdbc`, `adapter.vendor` | `integration`, `application`, `domain` |
| `bootstrap` | all preceding modules |

When B01 passes and is integrated, hand B02 the pure `domain` module, dependency check and `gradlew.bat :domain:check`. Hand B03 the `adapter.http` module, explicit JSON candidate and mock MVC configuration, with `gradlew.bat :adapter-http:check` if that module ID is adopted. Hand B04 the Java 25 wrapper, JDBC module and advisory evidence, with `gradlew.bat :adapter-jdbc:check` for local checks. B04 fixture actions require G1-Q and exact fixture use requires G1-U. [Successor criteria](../../../Phase4-development-planning/foundation.md)
