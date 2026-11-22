# B08 execution evidence · Listening HTTP and restart

Executed 2026-11-22 (Europe/Warsaw) by GPT-6.2 Sol xhigh (56). Candidate `9469405a1e4b8bb1a345158f6b54d93ea59772ce`, tree `e82759ffe33f9492143d05b1e3e4ada65054e084`, parent `65b9cd66cc59db4cb9797a04cf25733191de96db`, branch `codex/b08-http-restart`. The seven-path commit passes `git diff-tree --check HEAD^ HEAD`. Porcelain status is empty. The accepted `PH4-PLAN-r1` SHA-256 is `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`. GPT-6.2 Astra xhigh (62) is assigned independent review. [Assignment](http-runtime-assignment.md) · [G0-B](g0-b-http-runtime-approval.md) · [G1-U](../B04/g1-u-approval.md)

## Build and runtime

On the pinned parent, the strict offline build packaged successfully. Java exited 1 with `ClassNotFoundException` for the proposed listening entry point. The candidate's default artifact and resolved graph omit that class and Tomcat. The `synthetic-http` profile includes the listening entry point. Default and profile `clean verify` each passed all 11 reactor projects and 317 tests in nine suites with zero failures, errors or skips. Both builds used Maven 3.9.16 and Temurin 25.0.5.1+2, `-o -B -C -ntp`, and the same qualified Central-only XML for user and global settings. [Pinned-base red](../../../tmp/phase5-development/b08-http-runtime/executor-logs/baseline-entrypoint.log) · [Default build](../../../tmp/phase5-development/b08-http-runtime/executor-logs/default-final-clean-verify.log) · [Profile build](../../../tmp/phase5-development/b08-http-runtime/executor-logs/http-final-clean-verify.log)

The profile graph differs from the default graph by `org.apache.tomcat.embed:tomcat-embed-core:11.0.29` with its sole compile child `org.apache.tomcat:tomcat-annotations-api:11.0.29`. I verified both JARs and both POMs against the acquired Central SHA-256 sidecars and rechecked the private cache copies. The listening JVMs used 30 packaged JARs derived from the bootstrap reactor graph and the standalone fixture POM's runtime classpath for pgJDBC 42.7.14 and Checker Qual 3.55.1. The runtime entries exclude the provided Servlet API JAR, Spring Test, JUnit, fixture classes and reactor test outputs. Tomcat core supplies Servlet 6.1. [Input provenance and hashes](http-runtime-inputs.md) · [Graph delta](../../../tmp/phase5-development/b08-http-runtime/executor-logs/resolved-graph-delta.txt) · [Classpath hashes](../../../tmp/phase5-development/b08-http-runtime/executor-logs/http-runtime-classpath-sha256.txt)

The bootstrap constructs a single Tomcat NIO connector with the literal IPv4 loopback address and plaintext HTTP. It disables the server shutdown socket, host automatic deployment, web context naming and Spring request-detail logging. The startup uses instance configuration and the existing Spring MVC DispatcherServlet and synthetic Create wiring. Runtime code uses no cluster and configures no TLS-PSK. The Apache CVE-2026-133325 condition requires both TLS 1.3 and TLS-PSK clustering. Both are excluded by the owner-approved connector. The coordinator record documents the public Snyk exact-version coverage limit. [Bootstrap source](../../../tmp/phase5-development/b08-http-restart/bootstrap/src/synthetic-http/java/com/company/requests/bootstrap/SyntheticHttpMain.java) · [Apache advisory](https://tomcat.apache.org/security-11.html) · [Coverage record](http-runtime-inputs.md)

## Exact wire run

| Check | Observation |
| --- | --- |
| Listener and application restart | JVMs 26884 and 25572 ran sequentially on `127.0.0.1:51699`. Windows socket inventory identified exactly one listening TCP socket for each PID. After each JVM exited 0, a new direct TCP connection was refused. The fixture used Java HTTP/1.1 with direct loopback and no TLS session. |
| Key and unauthenticated Create | `POST /operation-keys` returned 201 with the signed key and expected expiry. An unauthenticated Create with the same body and key returned 401 `authentication_required`, no Location or protected reference, zero effect rows, zero reservations and zero release preparations. |
| Create and replay | Both requests used the identical UTF-8 intent, signed key and owner bearer. Each returned HTTP 201 with equal Location, equal strictly decoded UTF-8 JSON body, and equal content type and no-store headers. The body carried the same ref, action, state and decimal-string revision 1. Location SHA-256 `7378cc9c0e3585baed47843525492ea86b2013dece6b1686561654c9cb2c0cd3`. Body SHA-256 `0ec030656541e9ea38420a285ab18c1394778b2a11ed924d111023815ae1bc31`. |
| Durable local effect | After each authorized call, the separate workflow observer saw one request, body, creation fact, assignment and finalized receipt, with zero tentative receipts. The first-call join checked exact UTF-8 fields, trusted originating person and initial assignee, initial assignment version and creation fact revision. |
| Independent release | After Create, the proof observer counted one reservation and one release preparation. After restart and replay it counted the same reservation and two separate correlations for one key digest, organization, result ref, revision and writer generation. |
| Database continuity and diagnostics | Workflow and proof system IDs were `7711062501199216604` and `7711062530555926368`. PostgreSQL 18.7 reported the same postmaster start instants at the end as before the first app started. Both application logs passed exact scans for absence of fixture passwords, bearer tokens, key, signing/intent secrets and disclosed ref. |

The wire fixture used workflow postmaster 37252 and proof postmaster 20208. It passed and stopped both owned postmasters. `pg_ctl` returned stopped status 3 for each, and the fixture confirmed that ports 55480 and 55481 were free. The private application stdin carried the credentials and signing material. The HTTP calls supplied the required bearer or operation key through headers. The fixture recorded only hashes of Location and the successful body in its transcript. Tomcat emitted shutdown diagnostics about Java module opens for optional webapp classloader leak checks. Both application JVMs exited cleanly. [Wire transcript](../../../tmp/phase5-development/b08-http-runtime/executor-logs/wire-final.log) · [First application log](../../../tmp/phase5-development/b08-http-runtime/runs/b08-http-wire-final/http-create.log) · [Second application log](../../../tmp/phase5-development/b08-http-runtime/runs/b08-http-wire-final/http-replay.log)

The original `B08Postgresql run` mode passed separately after the wire run. It covered the existing MockMvc subprocess, contention, rollback, withheld-release and expiry paths. The deliberately terminated JDBC sessions and the explicit uncertified PostgreSQL restart belonged to this regression. The final observer saw ten effects, twelve reservations, twelve preparations and zero pending claims. The run stopped both workflow postmasters and the proof postmaster and again confirmed both ports free. Both fixture commands exited 0. [Regression transcript](../../../tmp/phase5-development/b08-http-runtime/executor-logs/mvc-regression.log) · [Exit record](../../../tmp/phase5-development/b08-http-runtime/executor-logs/fixture-exits.txt)

## Reproduction

Use a fresh extraction of the [candidate archive](../../../tmp/phase5-development/b08-http-runtime/executor-logs/b08-http-9469405.tar) or the exact local commit. Copy the G1-U B04 qualified Maven repository into new B08 scratch, and copy the four verified Tomcat files with their SHA-256 sidecars into their respective coordinate folders. If seeding a fresh Maven cache from the acquired Central files, each coordinate folder also needs an ASCII `_remote.repositories` sidecar with the exact JAR and POM basenames followed by `>central=` on separate lines.

Run the candidate README's B08 build, resolved classpath, compile and wire commands with a fresh `b08-` run ID. Use `run` in place of `wire` with another fresh ID to reproduce the existing MVC and PostgreSQL fault oracle in a separate run. The README also gives the direct `SyntheticHttpMain` invocation and the private stdin format for a provisioned synthetic scope. [Candidate commands](../../../tmp/phase5-development/b08-http-restart/fixtures/postgresql/README.md#b08-listening-http-and-restart)

| Artifact | SHA-256 |
| --- | --- |
| `b08-http-9469405.tar` | `d8c0ea1b076d4c040c8e99fcb3e0e277150abe1c3258efa11a37669105ef6ea9` |
| B01 Central-only `settings.xml` | `e6617e035baec17673be3f90b4d169ca75e308121e6308f7e358dc2d5b07ac08` |
| `baseline-build.log` | `34d5c3ecd255ca321476528ac2feb05cd19ef407dd13a75239a4deab2b73cb8a` |
| `baseline-entrypoint.log` | `558e4d14ae3eaae39929fc3b770ab72fa913395bbd163f4d9d4b7fdf67071f5b` |
| `default-final-clean-verify.log` | `d2f12ba7b2884a9e07aa804dac0501eb2b2f6dddf557746979459ede55aeaace` |
| `http-final-clean-verify.log` | `a56b8e053a400deb05626dad16f17182c7ef93db10e4827682d6fb2e2e9401a8` |
| `bootstrap-default-graph.json` | `542b76d318f92bf84bec92bbf83b30b5bb7bae261d3eb957b0ae84bad9d86120` |
| `bootstrap-http-graph.json` | `71d527932837c5fd5c30fd40c37a8ea490ffb921013de5618dfb2fab078e58bd` |
| `resolved-graph-delta.txt` | `d112b6cb1aceaceb8116505c9c27e438d42d6834eda7fe77848d7bd8ac4e0cc3` |
| `approved-pair-sha256.txt` | `3d4bbde0a9d588355071a380c06a48e58ab7731b3667eafe3b984e4c27758ce2` |
| `http-runtime-classpath-sha256.txt` | `35a1f047538657b6b86ed59cf17654b78ec11b4c63734992365a3a17884c3761` |
| `postgresql-classpath.log` | `2e901cc6a44bad8c8facb787f4a9feb1b22b147e91bded7402ce1de3c61dcdbc` |
| `wire-final.log` | `e994618b60ea8984d676c61f25b588d2bc088828a88b29dbb425d6e488f5d7ec` |
| `http-create.log` | `4d8940634e10d0d393a32a9124fc68d2630033ecedd5728dbbbd12c28cf949a2` |
| `http-replay.log` | `c8a431bdaadeacacd43e10980f30b32d9f1878fc81fa6cf5dba5d2ce2ae77f83` |
| `mvc-regression.log` | `39cd411ee9f61e28120f394218fa2811dd5f5b81105fb229c0d9ef605111eb1c` |
| `fixture-exits.txt` | `23faa58eca8edc20f902c87f15da5f2a2423e45517d880a184f9b6b9c4286da1` |
