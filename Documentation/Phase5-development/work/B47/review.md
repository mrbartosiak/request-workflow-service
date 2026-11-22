# B47 independent local pilot review

**APPROVED** for the B47 test-only local PR and release rehearsals at B25 `87f8da7090936393808139bf1cd368739214b7bb`, tree `d15b2b7f0b11e2f4a80af0b95ce92babfd40a676`. Both checkpoints passed the product and valid assembly and rejected each seeded violation for the expected cause. The verdict covers the executor report with SHA-256 `3f9f11ece578d305774a660af12bb7a381820925d320e858b02b3f75af627cbd`. GPT-6.2 Sol xhigh (56) executed the pilot and GPT-6.2 Astra xhigh (62) independently reviewed it on 23 November 2026. [Executor report](evidence.md) · [B47 acceptance](../../../Phase4-development-planning/tool-pilots.md#b47--t6-r-package-boundary-pilot)

## Authority and byte identity

The owner’s G6-P receipt binds the two approved tool pins to a local trial after B25 integration. I checked its request digest, the B25 approval, all 30 accepted Phase 4 plan entries, and all 84 distinct files in the pilot manifest with SHA-256 `57b712695809eb36752a5c0b3d97df15c743d5e4f10f27c5bbd4e16f6d906de2`. ArchUnit 1.5.1 and SLF4J API 2.0.19 matched both the authorized SHA-256 pins and the locally retained publisher SHA-512 values. The Temurin 25.0.5.1+2 archive and the four JDK tools used in review verified. The product POMs and sources match the B25 checkout. The two pilot JARs appear only on the scratch tool classpath. [G6-P approval](g6-p-approval.md) · [B25 integration](../B25/integration.md) · [Independent provenance checks](../../../../tmp/phase5-development/b47-independent/provenance.log)

A fresh Git export matched the reviewed B25 archive, SHA-256 `daa36961a189dbd9cf05e10efce03fe8d5ae03f7f5e65c9c8ba0353dd0349d04`. All 418 PR and 420 release class entries matched the corresponding B25 bytecode. Every product and seeded class used minor 0 and major 69. The synthetic HTTP profile adds `SyntheticHttpMain` and `SyntheticConnectorEvidence` to bootstrap. Three release JARs differ from the retained B25 JARs only in the line endings of 31 SQL resources. I inspected the retained strict offline Maven records and Surefire XML. Each profile passed 436 tests in 20 suites, including the reactor graph, 156 lifecycle cases and three MVC smoke cases. [Provenance verifier](../../../../tmp/phase5-development/b47-independent/provenance.py)

## Independent tool results

I compiled the reviewed ArchUnit harness and each fixture afresh in separate PR and release directories and ran ArchUnit, `jdeps` and `javap` against the verified packages. The independent assertions checked per-module class counts, concrete dependency endpoints and process exits at both checkpoints. [Independent replay](../../../../tmp/phase5-development/b47-independent/replay.py) · [PR output](../../../../tmp/phase5-development/b47-independent/pr) · [Release output](../../../../tmp/phase5-development/b47-independent/release)

| Input | Package / cycle | Exit | Decisive observation at both checkpoints |
| --- | --- | ---: | --- |
| Product | Pass / pass | 0 | ArchUnit and JDK class traces found the same 19 permitted cross-module edges. |
| Valid assembly | Pass / pass | 0 | Seeded bootstrap, vendor and profile classes exercised all seven additional permitted edges. |
| Domain → Spring | Fail / pass | 1 | `DomainSpringSpecimen.frameworkVersion()` calls `SpringVersion.getVersion()`. |
| HTTP → JDBC adapter | Fail / pass | 1 | `HttpJdbcSpecimen.transactionRunner()` references `JdbcTransactionRunner.class`. |
| Two-adapter cycle | Fail / fail | 1 | `CycleDeliveryJdbc` and `CycleVendor` reference one another. The cycle names the distinct `adapter-integration-jdbc` and `adapter-vendor` slices. |

The resolved Maven graphs match the approved ten modules and 26 direct compile-scope edges. The product JARs for `adapter-vendor` and `profile` contain zero classes. The valid fixture covers all 26 edges when combined with the product. All ten `jdeps` invocations exited zero with resolved dependencies. At class level, both tools see `RequestActionCommand` referencing `Lifecycle`. For `RequestsConfiguration`, `jdeps` prints the Spring `Import` annotation type and omits its class value. Both independent `javap -v` traces show `RequestsMvcConfiguration.class` in `RuntimeVisibleAnnotations`, corroborating the ArchUnit import. [Approved wiring](../../../Phase3-LLD/wiring.md) · [PR disassembly](../../../../tmp/phase5-development/b47-independent/pr/javap-bootstrap.log) · [Release disassembly](../../../../tmp/phase5-development/b47-independent/release/javap-bootstrap.log)

## Cost and disposition

I reconciled every cost in the executor’s table to the recorded process timings or build record. The manual review recorded 101.2 seconds across both traces and the annotation follow-up. My concurrent local replays took 32.605 s PR and 34.433 s release for all five ArchUnit invocations, and 5.945 s PR and 6.121 s release for all five `jdeps` invocations. These measurements include process startup. [PR timings](../../../../tmp/phase5-development/b47-independent/pr/summary.json) · [Release timings](../../../../tmp/phase5-development/b47-independent/release/summary.json)

The release checkpoint used a local synthetic profile. Static rules cover class references. Runtime wiring and lifecycle behavior have separate test evidence. The executor’s observation script can complete after reporting a product-rule violation, so the independent replay explicitly required product exit zero and two passing rules. Mandatory CI enforcement and review of advisories, including ArchUnit’s shaded dependencies, remain separate G6-M decisions. [Pilot driver](../../../../tmp/phase5-development/b47-pilot/Run-B47.ps1) · [G6-M disposition](../../../Phase4-development-planning/deferrals-operating.md)
