# G0-B decision request · Adopt the verified B01 Maven build

**Pending owner disposition.** Adopt candidate `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a`, tree `609960118f998695048a143bc90755a5907a97f0`, on integration base `98c5802c2efc512d14dabf0a6ab59bd567571754` under `PH4-PLAN-r1`, SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. The requested action is to fast-forward the local integration branch to that exact revision and run the integrated checks before dispatching eligible successors. [Qualification authorization](g0-b-approval.md) · [Build evidence](build-evidence.md) · [Independent review](build-review.md)

| Item | Verified selection |
| --- | --- |
| Toolchain | Installed Apache Maven 3.9.16 and portable Temurin HotSpot `25.0.5.1+2`, Java compilation release 25 |
| Project pins | Spring Framework 7.0.11, Jakarta Servlet API 6.1.0, JUnit 6.1.3, Jackson 3.1.8, and the resolved transitive versions in the evidence |
| Build-only changes from review | BeanUtils 1.11.0 in Maven Dependency Plugin 3.10.0 and Commons IO 2.22.0 in Maven Compiler Plugin 3.15.0. A POM-only Git line-ending rule preserves the embedded Maven metadata across Windows checkouts. |
| Resolved Maven inputs | 423 JAR and POM hashes, manifest SHA-256 `99bbd64147bc95522e523d82bd889308ebe0076bab698b9811d90f609c88fb37` |
| Build products | 10 matching online and offline JAR hashes, manifest SHA-256 `d0af10e1c5779881c9eab76713e463ddec984018dd0708fc333b0adc36fd73e1` |

The dated Spring and Snyk recheck documents the advisories inspected, the fixed affected plugin versions and the public Snyk coverage gaps. Adoption would accept that limited advisory evidence for the local synthetic build. Production dependency and tool adoption retain their separate gates. [Advisory recheck](build-evidence.md#dated-advisory-recheck) · [Action gates](../../../Phase4-development-planning/choices.md#later-authorizations)
