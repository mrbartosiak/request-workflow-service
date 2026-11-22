# G0-B owner approval · B08 local HTTP runtime

The owner replied “Approved” to the exact B08 runtime request SHA-256 `7d8c94aeb89bc166a962b292e66b961fa4edc414bf604d8787aa09a0bd45c5a8`. This approves acquisition from Maven Central, strict checksum verification, build use and local synthetic runtime use for `org.apache.tomcat.embed:tomcat-embed-core:11.0.29` and `org.apache.tomcat:tomcat-annotations-api:11.0.29` after verifying the expected dependency closure and connector restrictions. [Exact request](g0-b-http-runtime-request.md) · [G0 kickoff](../g0-kickoff.md)

The authorized setup uses a single HTTP connector bound to `127.0.0.1`, synthetic credentials and data, and the existing G1-U fixture scope. Clustering and TLS-PSK are excluded. The request documents the Apache advisory applicability and the public Snyk coverage limit to recheck during qualification. Material resolution differences, additional exposure and production runtime use retain their separate dispositions. [G1-U](../B04/g1-u-approval.md) · [Action gates](../../../Phase4-development-planning/choices.md#later-authorizations)

The integrated B08 core base is `65b9cd66cc59db4cb9797a04cf25733191de96db`, tree `6ea6f09a1d4845df5e83390c52829b40a851a7d9`, under `PH4-PLAN-r1` SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. [B08 core integration](integration.md)
