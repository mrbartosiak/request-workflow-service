# G6-P approval for the B47 local pilot

On 23 November 2026 the owner approved the two exact Maven Central JARs and local execution in workspace scratch once B25 passed independent review and integration. The coordinator verified that entry at `87f8da7090936393808139bf1cd368739214b7bb`. Request SHA-256 `78de530cfb16f5d4b537b252fc50d1a288433d40887c14597f5435ab5fb79394`. [Exact request](g6-p-request.md) · [B25 integration](../B25/integration.md)

| Maven Central artifact | SHA-256 of downloaded JAR |
| --- | --- |
| ArchUnit core 1.5.1 | `a4dbfc51c90005ad6ac9967672a7efb43aa6928287a57238b28e60e0a75c5c6f` |
| SLF4J API 2.0.19 | `e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb` |

Each JAR matched Maven Central's published SHA-512. The verified input manifest has SHA-256 `1227c89691c9b123097be84083d3099f7c1efc6bf22f39d4201b494fd6bbe568`. Both pilot rehearsals use these same artifacts, Java 25, the approved package graph and seeded control classes. Compulsory adoption has its own G6-M gate. [Verified input manifest](../../../../tmp/phase5-development/b47-tools/verified-inputs.csv) · [ArchUnit checksum](https://repo.maven.apache.org/maven2/com/tngtech/archunit/archunit/1.5.1/archunit-1.5.1.jar.sha512) · [SLF4J checksum](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.19/slf4j-api-2.0.19.jar.sha512)
