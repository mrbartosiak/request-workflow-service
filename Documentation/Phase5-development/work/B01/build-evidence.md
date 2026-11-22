# B01 Maven reactor and MVC qualification

**Executed 2026-11-22 (Europe/Warsaw).** The ten-module reactor passed the online and offline builds with four tests. The dispatcher started and stopped in two fresh Spring web application contexts backed by mock servlet contexts. The Spring version, invalid module edge and Spring-free domain controls produced their intended failures. Candidate `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a` includes scoped POM line-ending normalization and two plugin-local dependency patches from independent review. The public Snyk recheck has the coverage gaps below. [B01 acceptance](../../../Phase4-development-planning/foundation.md#b01--reproducible-spring-build) · [Approved wiring](../../../Phase3-LLD/wiring.md)

**Authority and revision.** `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, base `98c5802c2efc512d14dabf0a6ab59bd567571754`, candidate tree `609960118f998695048a143bc90755a5907a97f0`. GPT-6.2 Sol high (55) executed on clean `codex/b01-qualification`. GPT-6.2 Astra medium (57) is assigned independent review. [G0 receipt](../g0-kickoff.md) · [G0-B approval](g0-b-approval.md) · [Assignment](assignment.md)

## Reproduction

In PowerShell from the isolated candidate checkout at `tmp/phase5-development/b01-qualification`, use the provisioned JDK and installed Maven. The isolated settings block repository IDs other than `central`. The effective Central source is `https://repo.maven.apache.org/maven2`. Both the user and global settings flags use the same standalone file. Its SHA-256 is `e6617e035baec17673be3f90b4d169ca75e308121e6308f7e358dc2d5b07ac08`. [Settings](../../../tmp/phase5-development/b01-tools/m2/settings.xml) · [JDK provisioning and publisher hash](jdk-provisioning.md)

```powershell
$toolsRoot = Join-Path (Split-Path $PWD) 'b01-tools'
$mavenRoot = Join-Path $toolsRoot 'm2'
$settings = Join-Path $mavenRoot 'settings.xml'
$repo = Join-Path $mavenRoot 'patched-repository'
$env:JAVA_HOME = Join-Path $toolsRoot 'temurin/jdk-25.0.5.1+2'
$env:MAVEN_SKIP_RC = 'true'
$mvn = 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd'
$flags = @('-B', '-C', '-ntp', '-s', $settings, '-gs', $settings, "-Dmaven.repo.local=$repo")
$onlineFlags = @(
    '-Daether.connector.connectTimeout=15000',
    '-Daether.connector.requestTimeout=20000',
    '-Daether.connector.http.retryHandler.count=0'
)

& $mvn --version
& $mvn @flags @onlineFlags clean verify
& $mvn @flags @onlineFlags verify org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree -Dverbose
& $mvn -o @flags clean verify
```

For first resolution, use a new repository directory and the scoped sandbox allowance for Maven Central. Maven reported `3.9.16` build `2bdd9fddda4b155ebf8000e807eb73fd829a51d5` and Eclipse Adoptium `25.0.5.1`. The provisioned Java runtime reported Temurin `25.0.5.1+2-LTS`. The compiler used release 25. [Maven version log](../../../tmp/phase5-development/b01-tools/logs/maven-version.log) · [Maven CLI](https://maven.apache.org/ref/3.9.16/maven-embedder/cli.html)

## Red and green evidence

| Check | Result |
| --- | --- |
| First prerequisite on pinned base | `clean verify` exited 1 with `MissingProjectException` at the clean seed. This offline probe used the installed Oracle OpenJDK 25.0.2 before Temurin provisioning completed. [Seed log](../../../tmp/phase5-development/b01-tools/logs/seed-red.log) |
| MVC, Java and runtime version | On Temurin, the bootstrapped dispatcher used the MVC handler mapping and the MVC handler adapter had a Jackson 3 converter. Two independent `MockServletContext` instances each initialized the servlet and closed. The smoke checked runtime feature 25, bootstrap class-file major 69 and `SpringVersion.getVersion()` equal to 7.0.11. [Online log](../../../tmp/phase5-development/b01-tools/logs/maven-online-verify.log) |
| Approved module graph | The pinned Dependency Plugin writes each module's effective Maven dependency tree during the normal lifecycle. The bootstrap test compares the root module set and each module's actual direct project edges and scopes to the accepted map. Domain's positive dependency tree has zero children. [Verbose tree](../../../tmp/phase5-development/b01-tools/logs/maven-dependency-tree.log) |
| Wrong project edge | Temporarily add compile dependency `com.company.requests:adapter-authority:${project.version}` to `adapter-http/pom.xml`. Offline `clean verify` built the resulting Maven graph and failed the architectural assertion. The expected HTTP edges are `application:compile` and `domain:compile`. Actual also contained `adapter-authority:compile`. Exit 1. [Edge control](../../../tmp/phase5-development/b01-tools/logs/negative-project-edge.log) |
| Spring dependency in domain | Temporarily add managed `org.springframework:spring-core` to `domain/pom.xml`, then run offline `-pl domain -am verify`. Enforcer rejected `org.springframework:spring-core:jar:7.0.11` on domain's classpath. Exit 1. [Domain control](../../../tmp/phase5-development/b01-tools/logs/negative-domain-spring.log) |
| Wrong actual Spring version | Copy the qualified cache to a separate control directory. In that copy only, update the `spring-core-7.0.11.jar` manifest using Temurin `jar --update --file <copied-jar> --manifest <control-manifest>`, where the ASCII manifest contains `Implementation-Version: 7.0.10` and a trailing blank line. Run offline `verify -Dtest=RequestsMvcSmokeTest#usesJava25BytecodeAndRuntimeAndSpring7011 -Dsurefire.failIfNoSpecifiedTests=false` against the copied cache. The live Spring version assertion expected 7.0.11 and read 7.0.10. Exit 1. The vendor JAR hash in the qualifying cache matched before and after. [Version control](../../../tmp/phase5-development/b01-tools/logs/negative-spring-version.log) |
| Final clean repeats | With original source bytes restored and the qualifying cache active, the offline run passed all four tests with zero failures, errors and skips. SHA-256 matched for all ten online and offline module JARs in this checkout. [Offline log](../../../tmp/phase5-development/b01-tools/logs/maven-offline-verify.log) · [Online hashes](../../../tmp/phase5-development/b01-tools/logs/reactor-online-jar-sha256.txt) · [Offline hashes](../../../tmp/phase5-development/b01-tools/logs/reactor-offline-jar-sha256.txt) |

The initial MVC run passed its three checks and exposed an incorrect XPath return type in the prototype graph test. The final graph oracle uses Maven's resolved dependency tree for edges and JDK XPath for the root module list. The first verbose tree also exposed a test-scoped direct Spring Core dependency overriding its compile path. The candidate places Spring Core and Commons Logging on the compile path and manages JSpecify 1.0.1 for both Spring and JUnit. The fresh-cache online evidence and offline controls were recorded against the first candidate `4817043efef83792e56e0f0f86846a32150381a9` with the same Maven and Java inputs as the revised candidate. [Initial verify log](../../../tmp/phase5-development/b01-tools/logs/maven-initial-verify.log) · [Earlier dependency tree](../../../tmp/phase5-development/b01-tools/logs/maven-before-scope-tree.log)

## Fresh archive repeat after review

Independent review of the first candidate found that Windows `core.autocrlf=true` produced CRLF POMs in a fresh archive. All ten JAR hashes then differed from the executor's LF worktree. For the bootstrap JAR, only the embedded POM differed. Revision `6c21767df9c79b16530ab60ba9b72c2767a9bd1b` adds `pom.xml text eol=lf` in `.gitattributes`, covering the reactor POM basenames. The Windows Git configuration remained in effect during this repeat. All 11 archived POMs had LF and matched the worktree POM bytes.

After setting the variables in Reproduction, build the exact candidate from the worktree and from a fresh ZIP archive. Compare the corresponding ten JAR SHA-256 values, using one path per module. The command uses the latest candidate, which retains the POM line-ending rule.

```powershell
$candidate = '056c4e0bbb74df16b16684aad5fa1dc82cf1f22a'
$archive = Join-Path $toolsRoot 'b01-offline-recheck.zip'
$fresh = Join-Path $toolsRoot 'b01-offline-recheck'
git config --show-origin --get core.autocrlf
git archive --format=zip "--output=$archive" $candidate
Expand-Archive -LiteralPath $archive -DestinationPath $fresh
$modules = @(([xml](Get-Content pom.xml -Raw)).project.modules.module)
$pomPaths = @('pom.xml') + @($modules | ForEach-Object { "$_/pom.xml" })
foreach ($pomPath in $pomPaths) {
    $archivedPom = Join-Path $fresh $pomPath
    if ([IO.File]::ReadAllBytes($archivedPom) -contains [byte]13) {
        throw "Unexpected CR in $pomPath"
    }
    if ((Get-FileHash -LiteralPath $pomPath).Hash -ne (Get-FileHash -LiteralPath $archivedPom).Hash) {
        throw "POM bytes differ for $pomPath"
    }
}
& $mvn -o @flags clean verify
Push-Location -LiteralPath $fresh
try {
    & $mvn -o @flags clean verify
} finally {
    Pop-Location
}
foreach ($module in $modules) {
    $jar = "$module/target/$module-0.1.0-SNAPSHOT.jar"
    if ((Get-FileHash -LiteralPath $jar).Hash -ne (Get-FileHash -LiteralPath (Join-Path $fresh $jar)).Hash) {
        throw "JAR bytes differ for $module"
    }
}
```

The normalization-only revision passed all four tests twice and matched all ten original LF JAR checksums. Its local source ZIP SHA-256 is `bec272e4c1a606dfc952e90698280394ef5a702887dd85f339ab33f8176a345a`. Both JAR checksum lists have SHA-256 `d0af10e1c5779881c9eab76713e463ddec984018dd0708fc333b0adc36fd73e1`. [Normalization worktree log](../../../tmp/phase5-development/b01-tools/logs/maven-revised-worktree-offline-verify.log) · [Normalization archive log](../../../tmp/phase5-development/b01-tools/logs/maven-revised-archive-offline-verify.log) · [Normalization archive JAR hashes](../../../tmp/phase5-development/b01-tools/logs/reactor-revised-archive-jar-sha256.txt)

## Resolved versions and checksums

The root POM imports Spring, JUnit and Jackson BOMs at the resolved versions below. Enforcer 3.6.2 checks Maven 3.9.16, Java major 25, compiler release 25, duplicate declarations and dependency convergence. Domain also bans Spring and reactor dependencies. The Maven tree shows 27 selected external JAR coordinates across compile, provided and test scopes.

| Group | Artifacts and exact versions | Scope |
| --- | --- | --- |
| `org.springframework` | `spring-aop`, `spring-beans`, `spring-context`, `spring-core`, `spring-expression`, `spring-jdbc`, `spring-tx`, `spring-web`, `spring-webmvc` at 7.0.11 | compile |
| `org.springframework` | `spring-test` 7.0.11 | test |
| `tools.jackson.core` | `jackson-databind`, `jackson-core` at 3.1.8 | compile |
| `com.fasterxml.jackson.core` | `jackson-annotations` 2.21 | compile |
| `io.micrometer` | `micrometer-observation`, `micrometer-commons` at 1.16.9 | compile |
| `commons-logging` | `commons-logging` 1.3.5 | compile |
| `org.jspecify` | `jspecify` 1.0.1, including JUnit's path managed from 1.0.0 | compile |
| `jakarta.servlet` | `jakarta.servlet-api` 6.1.0 | provided |
| `org.junit.jupiter` | `junit-jupiter`, `junit-jupiter-api`, `junit-jupiter-engine`, `junit-jupiter-params` at 6.1.3 | test |
| `org.junit.platform` | `junit-platform-launcher`, `junit-platform-engine`, `junit-platform-commons` at 6.1.3 | test |
| Test API support | `org.apiguardian:apiguardian-api:1.1.2`, `org.opentest4j:opentest4j:1.3.0` | test |

Executed plugins were Maven Clean 3.5.0, Enforcer 3.6.2, Resources 3.5.0, Compiler 3.15.0, Dependency 3.10.0, Surefire 3.5.6 and JAR 3.5.1. Install and Deploy are pinned at 3.1.4 and are outside the verified lifecycle. The original cache had 121 JARs and 302 POMs. The patched candidate cache has 119 JARs and 304 POMs, including build artifacts and their transitives. Maven used strict checksums in both resolutions. All 423 cached POMs and JARs in each cache have SHA-1 sidecars that matched a local recheck. The SHA-256 manifests give the Maven Central-relative path of each checked file. [Original manifest](../../../tmp/phase5-development/b01-tools/logs/maven-central-artifacts-sha256.txt) · [Patched manifest](../../../tmp/phase5-development/b01-tools/logs/maven-patched-central-artifacts-sha256.txt)

| First candidate evidence file | SHA-256 |
| --- | --- |
| `maven-central-artifacts-sha256.txt` | `57561d80aef20684df3f4e4bb161d90dbed9c8e777056e5ae2b747553351bd7b` |
| `maven-dependency-tree.log` | `da01d8288c6136b21af19d2204871ae72be47a21877d3ecce169135968b52144` |
| `maven-online-verify.log` | `29d23abaa9c72f7343564eedfb1d09fe75440d88f23b3d5d94e50810418e9045` |
| `maven-offline-verify.log` | `d451d32ebb645b273ee8aa141e4941f2eedbc746fb69d625dc91ba56b8c2c342` |
| Both reactor JAR checksum lists | `d0af10e1c5779881c9eab76713e463ddec984018dd0708fc333b0adc36fd73e1` |

## Dated advisory recheck

- Spring's November 19 notices identify resolved 7.0.11 as the OSS fix for MVC binding CVE-2026-59374, multipart CVE-2026-59389, redirect CVE-2026-59394, content negotiation CVE-2026-59398, route matching CVE-2026-59429 and SpEL CVE-2026-59430/59431. Keep MVC input and configuration applicability in B03. The candidate runs a mock servlet context with no request mappings, redirect filter, external input or SpEL from caller input. [Binding](https://spring.io/security/cve-2026-59374/) · [Multipart](https://spring.io/security/cve-2026-59389/) · [Redirect](https://spring.io/security/cve-2026-59394/) · [Negotiation](https://spring.io/security/cve-2026-59398/) · [Route matching](https://spring.io/security/cve-2026-59429/) · [SpEL conversion](https://spring.io/security/cve-2026-59430/) · [SpEL selection](https://spring.io/security/cve-2026-59431/)
- Spring also lists 7.0.11 as the OSS fix for streaming WebFlux CVE-2026-59418, HTTP client observations CVE-2026-59419 and WebSocket/STOMP CVE-2026-59390/59391/59392. B01 has no reactive endpoint, HTTP client or messaging wiring. Spring Security is absent from the resolved graph. Its separate client and matcher advisories belong to future authorized identity dependency selection. [Streaming](https://spring.io/security/cve-2026-59418/) · [Client](https://spring.io/security/cve-2026-59419/) · [WebSocket](https://spring.io/security/cve-2026-59390/) · [STOMP](https://spring.io/security/cve-2026-59391/) · [SockJS](https://spring.io/security/cve-2026-59392/) · [Spring advisory index](https://spring.io/security/)
- Snyk's advisory ranges place Spring Web 7.0.11 outside CVE-2026-59429 and CVE-2026-59398 and Spring Core 7.0.11 outside CVE-2026-59418. The coordinator's Jackson package lookup found the 3.1.8 entries. Snyk and FasterXML identify Databind 3.1.8 as the fix for CVE-2026-110879. The Jackson Core range checked for GHSA-vvjm-vc27-799r applies to the 3.2 line. B01 uses Jackson for a registered MVC converter and parsing local dependency trees. [Snyk route](https://security.snyk.io/vuln/SNYK-JAVA-ORGSPRINGFRAMEWORK-22727194) · [Snyk negotiation](https://security.snyk.io/vuln/SNYK-JAVA-ORGSPRINGFRAMEWORK-22727213) · [Snyk streaming](https://security.snyk.io/vuln/SNYK-JAVA-ORGSPRINGFRAMEWORK-22727152) · [Snyk Databind](https://security.snyk.io/package/maven/tools.jackson.core%3Ajackson-databind) · [Snyk Core](https://security.snyk.io/package/maven/tools.jackson.core%3Ajackson-core) · [Snyk CVE](https://security.snyk.io/vuln/SNYK-JAVA-TOOLSJACKSONCORE-21761838) · [FasterXML Databind](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph) · [FasterXML Core](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-vvjm-vc27-799r)
- Micrometer and Commons Logging publisher advisory pages listed no published issues in the checked indexes. JUnit's publisher advisory found in the recheck covers the `junit-platform-reporting` artifact in an older range. That artifact does not appear in this reactor. [Micrometer advisories](https://github.com/micrometer-metrics/micrometer/security/advisories) · [Commons Logging security](https://commons.apache.org/proper/commons-logging/security.html) · [JUnit GHSA](https://github.com/junit-team/junit-framework/security/advisories/GHSA-m43g-m425-p68x)
- Public Snyk exact-version URLs were attempted for all 27 selected JAR coordinates. They returned unavailable, cache-miss, timeout or redirect-loop results through the executor's web tool. The Spring MVC package listing stopped at 7.0.10. The package and advisory-range observations above cover the cited findings. Exact-version package coverage across the full application/test graph and a scanner result for the application or build plugin closure remain unverified. The independent B01 reviewer owns the disposition of this gap. [Snyk MVC package](https://security.snyk.io/package/maven/org.springframework%3Aspring-webmvc)

## Maven plugin findings and patches

Independent review identified two vulnerable artifacts in plugin class realms on the normalization-only revision. The harness used pinned source and approved public build artifacts. Reachability of vulnerable behavior with those inputs remained unverified. The latest candidate applies plugin-local dependency pins in the root POM and retains the approved plugin versions. The resolved application and test dependency trees match the earlier tree.

| Plugin realm | Before and after | Advisory evidence |
| --- | --- | --- |
| Maven Dependency Plugin 3.10.0 | `commons-beanutils:commons-beanutils` 1.9.4 to 1.11.0 | Apache fixes CVE-2025-48734 in 1.11.0. Snyk's exact 1.11.0 page reported zero direct findings in the package. [Apache notice](https://lists.apache.org/thread/s0hb3jkfj5f3ryx6c57zqtfohb0of1g9) · [Apache issue](https://issues.apache.org/jira/browse/KAFKA-19359) · [Snyk 1.11.0](https://security.snyk.io/package/maven/commons-beanutils%3Acommons-beanutils/1.11.0) |
| Maven Compiler Plugin 3.15.0 | `commons-io:commons-io` 2.11.0 to 2.22.0, the version already in the Maven JAR Plugin realm | Apache fixes CVE-2024-47554 in 2.14.0. Snyk's package listing reported zero direct findings for 2.22.0, while the executor's direct version-page lookup returned a cache miss. [Apache Commons IO advisory](https://commons.apache.org/proper/commons-io/security.html) · [Snyk package listing](https://security.snyk.io/package/maven/commons-io%3Acommons-io) |

To inspect actual plugin classpaths, run `& $mvn -o @flags -X verify` in PowerShell. Retain only realm names from `Populating class realm` lines, their Commons IO and BeanUtils `Included:` entries and the result line. The patched excerpt shows BeanUtils 1.11.0 in Dependency Plugin and Commons IO 2.22.0 in Compiler Plugin. The affected versions were absent across the captured realms. [Before patch](../../../tmp/phase5-development/b01-tools/logs/maven-before-patch-plugin-realms.log) · [After patch](../../../tmp/phase5-development/b01-tools/logs/maven-patched-plugin-realms.log)

The patched commit passed the fresh-cache online build, the verbose dependency tree goal and both offline builds. All four tests passed in each build. With Windows `core.autocrlf=true`, all 11 POMs in the patched archive had LF and matched the worktree. All ten module JARs matched across the patched online, offline worktree and archive builds and also matched the original LF reference. The patched source ZIP SHA-256 is `8a82361a7621c20720394d5d2868f0ed244ffb303bc9d73dcb95340a9dab97c2`. [Patched online log](../../../tmp/phase5-development/b01-tools/logs/maven-patched-online-verify.log) · [Patched verbose tree](../../../tmp/phase5-development/b01-tools/logs/maven-patched-dependency-tree.log) · [Patched worktree log](../../../tmp/phase5-development/b01-tools/logs/maven-patched-worktree-offline-verify.log) · [Patched archive log](../../../tmp/phase5-development/b01-tools/logs/maven-patched-archive-offline-verify.log) · [Patched archive JAR hashes](../../../tmp/phase5-development/b01-tools/logs/reactor-patched-archive-jar-sha256.txt)

| Patched qualification evidence | SHA-256 |
| --- | --- |
| `commons-beanutils-1.11.0.jar` | `9e44ba68ec9a3f21286fa2a8bbb003b735c0f69101bb43144b79f4f8aaa74709` |
| `commons-io-2.22.0.jar` | `2b9a7b1f726fb86216dbd2c8321eabe0221dbd5b1be81c18e1cb53811b104758` |
| `maven-patched-central-artifacts-sha256.txt` | `99bbd64147bc95522e523d82bd889308ebe0076bab698b9811d90f609c88fb37` |
| `maven-patched-online-verify.log` | `5f1856f7cc59b40eecb537f8bd673873673cc7b571acf3ef16ebdc21ab5eac76` |
| `maven-patched-dependency-tree.log` | `03c6beee8bb4e444febdc948942b978f78b034ae4b46b40a6c73ad925e7746d1` |
| `maven-patched-worktree-offline-verify.log` | `6e5b5b1e500c5d6dece81d2e596ed1ce718ef80b202bae7866492c8375713dd2` |
| `maven-patched-archive-offline-verify.log` | `d0e30e57578041f988b3a16c8ff766b920dbbbf9f3998c9293ac204841989b73` |
| `maven-patched-plugin-realms.log` | `33a0558ba93d52ac7688e120cd5508d986442c83ffdb75439939969fad4f5037` |
| All three patched JAR checksum lists | `d0af10e1c5779881c9eab76713e463ddec984018dd0708fc333b0adc36fd73e1` |

## Successor entry points

With the same flags and populated repository, `mvn -o -B -pl domain -am verify`, `mvn -o -B -pl adapter-http -am verify` and `mvn -o -B -pl adapter-jdbc -am verify` each succeeded. B02 owns `com.company.requests.domain`, B03 owns `com.company.requests.adapter.http`, and B04 starts from `adapter-jdbc` build qualification. B04 fixture qualification and fixture use remain subject to G1-Q and G1-U. The bootstrap module owns composition and the full-reactor test. [B02–B04 stop criteria](../../../Phase4-development-planning/foundation.md) · [Domain log](../../../tmp/phase5-development/b01-tools/logs/successor-domain.log) · [HTTP log](../../../tmp/phase5-development/b01-tools/logs/successor-adapter-http.log) · [JDBC log](../../../tmp/phase5-development/b01-tools/logs/successor-adapter-jdbc.log)
