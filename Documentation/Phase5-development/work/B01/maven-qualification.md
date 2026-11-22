# B01 · Installed Maven qualification addendum

**2026-11-22, Europe/Warsaw.** The owner selected the installed `mvn` for B01. The Windows machine PATH already includes Apache Maven 3.9.16. This Codex process inherited a PATH that omitted it. Prepending that machine entry in a child PowerShell made Maven available and a process-local Java home switched it to Java 25. The qualification request below uses the installed Maven directly.

This addendum records the Maven-specific change against `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, base `98c5802c2efc512d14dabf0a6ab59bd567571754` and the original independently reviewed report SHA-256 `e3f109c7923d977eb0d202c293498420b222f48a679cea32b540ec61e379fe3d`. The report's module graph, Java smoke, dependency candidates and advisory gaps carry forward. Addendum reviewer disposition is pending. [Qualification](qualification.md) · [Original independent review](qualification-review.md) · [G0 receipt](../g0-kickoff.md)

## Executable and offline preflight

From the isolated `codex/b01-qualification` checkout, `Get-Command mvn` initially found nothing and the machine PATH contained `C:\Program Files\Apache\apache-maven-3.9.16\bin`. Both version commands below resolved to `C:\Program Files\Apache\apache-maven-3.9.16\bin\mvn.cmd` and reported Apache Maven **3.9.16** (build commit `2bdd9fddda4b155ebf8000e807eb73fd829a51d5`) with exit 0. The first printed Temurin **21.0.13** and the second printed Oracle OpenJDK **25.0.2**. Both emitted the localized line `Odmowa dostępu.` (access denied). Its source is unverified. Maven also reached project-model processing in the offline probe below. [Apache Maven 3.9.16 release history](https://maven.apache.org/docs/history.html)

```powershell
Get-Command mvn -ErrorAction SilentlyContinue
$mavenBin = 'C:/Program Files/Apache/apache-maven-3.9.16/bin'
([Environment]::GetEnvironmentVariable('Path', 'Machine') -split ';') -contains
    ($mavenBin -replace '/', '\')
$env:Path = "$mavenBin;$env:Path"
$env:MAVEN_SKIP_RC = 'true'
Get-Command mvn | Select-Object -ExpandProperty Source
mvn --version
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/openjdk-25.0.2'
mvn --version
```

In the same process, create a scratch Maven POM with `modelVersion` 4.0.0, project `com.company.requests:b01-offline-bom-probe:1.0.0`, packaging `pom`, and a single `dependencyManagement/dependencies/dependency` for `org.springframework:spring-framework-bom:7.0.11` of type `pom` and scope `import`. Place `pom.xml` and a `settings.xml` containing `<settings/>` under a scratch directory. The offline probe used the same isolated settings at both levels and a scratch local repository.

```powershell
$existing = Join-Path $env:USERPROFILE '.m2/repository/org/springframework/spring-framework-bom/7.0.11/spring-framework-bom-7.0.11.pom'
Test-Path -LiteralPath $existing -PathType Leaf
$scratch = Join-Path (Get-Location).Path '.b01-maven-probe'
$settings = Join-Path $scratch 'settings.xml'
$pom = Join-Path $scratch 'pom.xml'
$cache = Join-Path $scratch 'repository'
mvn -o -B -s $settings -gs $settings "-Dmaven.repo.local=$cache" -f $pom validate
```

The standard-cache BOM check returned `False`. The offline Maven model validation reached project scanning and returned 1 for `Non-resolvable import POM` with `org.springframework:spring-framework-bom:pom:7.0.11 (absent)`. Maven reported it could not access Central in offline mode. This result identifies a dependency prerequisite. Spring MVC startup and the module graph assertions remain pending. The probe used existing software and isolated scratch output. [3.9.16 CLI offline and settings options](https://maven.apache.org/ref/3.9.16/maven-embedder/cli.html)

## Revised G0-B scope for Maven

1. Qualify using the owner-selected installed Apache Maven **3.9.16** and process-local `Path` and `JAVA_HOME`. Request authorization to acquire and verify only the portable Windows x64 **Temurin 25.0.5.1+2** toolchain, `OpenJDK25U-jdk_x64_windows_hotspot_25.0.5.1_2.zip`, published SHA-256 `849d380ab0a64d9098e7d3a2de28beb73841c3b255c8284f019ccd8748c5eb5b`. This pin includes the November JDK security update. The installed Oracle JDK provided probe evidence. Maven wrapper acquisition is unnecessary for B01. [Temurin release assets](https://github.com/adoptium/temurin25-binaries/releases/expanded_assets/jdk-25.0.5.1%2B2) · [JDK security guidance](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/)
2. Authorize isolated Maven Central access or a named approved mirror for Framework **7.0.11**, Servlet **6.1.0**, JUnit **6.1.3**, Jackson **3.1.8** and their required dependencies, plus the selected Maven lifecycle and verification plugins and their dependencies. Pin `maven-compiler-plugin:3.15.0`, `maven-surefire-plugin:3.5.6`, `maven-enforcer-plugin:3.6.2` and `maven-dependency-plugin:3.10.0` as candidates, and declare exact lifecycle plugin versions in the candidate POM. Preserve isolated portable extraction and the scratch Maven repository. Earlier Maven Central POM metadata access failed DNS in the restricted shell. Actual plugin and application graphs, artifact verification and the dated Spring/Snyk review still need a successful dependency resolution. [Dependency candidates](qualification.md#direct-and-transitive-metadata) · [Compiler release configuration](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.15.0/examples/set-compiler-release.html)
3. On approval, create the Maven reactor and the previously specified module graph and MVC context oracle. Assert Maven 3.9.16, Java runtime feature 25 with the selected JDK, compiler release 25, Framework 7.0.11, permitted reactor dependency edges and a Spring-free domain classpath. Capture `mvn --version`, `mvn -B -C clean verify`, `mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree -Dverbose`, and `mvn -o -B clean verify` using the same scoped settings and repository. Record the MVC context result, graph and version negative controls, resolved coordinates and artifact checksums. B02 and B03 can use `mvn -B -pl domain -am verify` and `mvn -B -pl adapter-http -am verify` if those reactor IDs are adopted. B04 can use `mvn -B -pl adapter-jdbc -am verify` for local build checks. Fixture qualification and use remain governed by G1-Q and G1-U. [B01–B04 acceptance criteria](../../../Phase4-development-planning/foundation.md) · [Action gates](../../../Phase4-development-planning/choices.md#later-authorizations)

G0-B remains pending for the patched JDK, dependency acquisition and candidate implementation. The exact installed Maven executable has passed the launch probe. No application build, dependency download, fixture access or global configuration change occurred in this follow-up.
