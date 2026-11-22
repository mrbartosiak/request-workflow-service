# B08 HTTP runtime inputs

On 2026-11-22 (Europe/Warsaw), the coordinator fetched the four published binaries and descriptors below from Maven Central under the [G0-B owner approval](g0-b-http-runtime-approval.md). Each file matched its published SHA-256 sidecar over HTTPS. The private acquisition cache uses the standard Maven coordinate layout at `tmp/phase5-development/b08-http-runtime/central`. The candidate build uses the installed Maven and an isolated copy of the previously qualified repository augmented with these verified files. [Core publication](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/11.0.29/) · [Annotations publication](https://repo.maven.apache.org/maven2/org/apache/tomcat/tomcat-annotations-api/11.0.29/)

| File | SHA-256 |
| --- | --- |
| `tomcat-embed-core-11.0.29.pom` | `bbabe2c23f5c254f7793bc3257382aa1bcaf2b08029babe63afd19b3c2453edb` |
| `tomcat-embed-core-11.0.29.jar` | `45302cad046c8b8c51ba80b34f2a01d1c75a66792587c3ac5e8fda9ff8abf837` |
| `tomcat-annotations-api-11.0.29.pom` | `f8a4685f08c446698e891e3c4189c899a15071a46e377e768bdc1cbdd7c1c068` |
| `tomcat-annotations-api-11.0.29.jar` | `9271ec566115f35ef84649f31f45704e1e7b4b94da74a59d0780d1faa4668379` |

The published core POM has one non-optional compile dependency on `org.apache.tomcat:tomcat-annotations-api:11.0.29`. The annotations POM has no dependencies. Neither POM declares a parent. The independent review checks the candidate's resolved closure and listening JVM classpath. [Maven Central core POM](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/11.0.29/tomcat-embed-core-11.0.29.pom) · [Maven Central annotations POM](https://repo.maven.apache.org/maven2/org/apache/tomcat/tomcat-annotations-api/11.0.29/tomcat-annotations-api-11.0.29.pom)

Apache lists CVE-2026-133325 for core 11.0.28–11.0.29, applicable to TLS 1.3 with TLS-PSK clustering. It lists the fix for 11.0.30, which the publisher still marks unreleased. The approved B08 connector is IPv4 loopback HTTP with no TLS or clustering. Spring lists 7.0.11 as the OSS fix for its November 19 advisories checked here on indexed data binding, request-path matching, and parameter-based content negotiation. The B08 stage uses the adopted Spring 7.0.11. [Apache advisories](https://tomcat.apache.org/security-11.html) · [Spring data binding advisory](https://spring.io/security/cve-2026-59374/) · [Spring path advisory](https://spring.io/security/cve-2026-59429/) · [Spring content negotiation advisory](https://spring.io/security/cve-2026-59398/)

Exact-version public Snyk entries for both newly resolved coordinates could not be retrieved. The public core index exposed up to 11.0.27 at recheck. This is the coverage limitation recorded in the owner-approved request. [Public core version index](https://security.snyk.io/package/maven/org.apache.tomcat.embed%3Atomcat-embed-core/versions) · [Scope and advisory limits](g0-b-http-runtime-request.md)
