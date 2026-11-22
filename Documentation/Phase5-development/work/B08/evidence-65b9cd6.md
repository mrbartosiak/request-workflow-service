# B08 correction evidence · Guarded expiry and resolution session loss

Executed 2026-11-22 (Europe/Warsaw) by GPT-6.2 Sol xhigh (56). Correction `65b9cd66cc59db4cb9797a04cf25733191de96db`, tree `6ea6f09a1d4845df5e83390c52829b40a851a7d9`, parent `dc42b9a6d83def238e2641b77bb85cec9b40c5e2`, branch `codex/b08-persistent-create`. The seven-path change passes `git diff-tree --check HEAD^ HEAD` and the checkout is clean. GPT-6.2 Astra xhigh (62) is assigned independent review. The original execution report keeps SHA-256 `80900b33f51f0050d58ec7b4397bda305a5018cd5c8de26882126c2321f56002`. [Original evidence](evidence.md) · [Assignment](assignment.md) · [G1-U](../B04/g1-u-approval.md)

## Red and fixes

- The focused regression on `dc42b9a` failed five of six cases. Expiry lost to `WITHHELD` after absence and concealment, and to `BUFFER_UNAVAILABLE` after empty or oversized preparation. Authority withdrawn during an absent read also bypassed the final proof check. A key already expired ahead of the lookup made no receipt call. [Offline red](../../../tmp/phase5-development/b08-fixture/logs/expiry-guard-red.log)
- With the expiry fix packaged and the original JDBC classifier, the real PostgreSQL session-loss cut reproduced HTTP 500. It committed the Create, injected lost commit confirmation and terminated the exact fresh workflow backend as its receipt read began. The PostgreSQL I/O exception and rollback failure were observed. Both owned postmasters stopped. [Real-engine red](../../../tmp/phase5-development/b08-fixture/logs/resolution-session-red.log)
- A successful guarded lookup now reaches final scope proof, local guard verification and signed freshness even when the tentative disposition is absence, concealment, exhausted budget or unavailable preparation. After valid authority proof, the cutoff takes precedence over the pending disposition and the global command budget. The pre-lookup check still blocks keys already expired. The original result grant remains a requirement for success or conflict, and output remains bounded.
- Only the B08 bounded guarded-release transaction maps a known JDBC resource failure or JDBC/transaction exception with a connection-class SQLSTATE in its cause chain to the typed resolution failure. Create reports that failure as 503 `outcome_unknown`. Database preflight and effect transaction classifications remain on their existing path. A nonresource receipt exception and unexpected mutation errors retain the generic fault behavior.

## Final verification

| Check | Result |
| --- | --- |
| Offline reactor | `clean verify` passed all 317 tests and 11 projects. Six focused expiry tests passed, including rejection before lookup and authority lost after lookup. [Reactor log](../../../tmp/phase5-development/b08-fixture/logs/release-fix-offline-verify.log) |
| Cutoff reached in the shared guard | Absent active, absent past-generation, untrusted present past-generation, concealed target and failed independent preparation produced 409 `key_expired` with one allowed receipt read and zero claims per case. The failed preparation appended no proof. Successful preparation followed by cutoff preserved its independent evidence and kept the HTTP result withheld. The next expired request did no receipt SQL. |
| Existing nonexpired dispositions | Missing and untrusted past-generation receipts returned equivalent `retry_fenced` problems. Active absence inside the margin and failed preparation returned `outcome_unknown`. Concealed active original returned 404. Missing current authority proof deferred without a receipt read. Existing conflict and exact 201 replay continued to pass. |
| Real resource failures | After a durable Create and lost commit reply, only the fresh receipt-reading backend 25532 was terminated. The HTTP result was 503 `outcome_unknown` without Location or a result reference, with one committed effect and zero additional preparation. The certified same-key retry returned 201 and added one preparation with zero additional effects. A synthetic `XX000` receipt fault returned sanitized 500. Terminating the final guard recheck backend 31840 after a later preparation again returned sanitized 503 and preserved its preparation without another business effect. |
| Fixture and teardown | Application JVMs 28268 and 10504 returned equal 201 results across process exit. Final observer counts were 10 effects, 11 finalized receipts including a seeded old control, 12 reservations, 12 preparations and zero tentative claims. PostgreSQL 18.7 and pgJDBC 42.7.14 ran on separate owned loopback clusters. Workflow PIDs 13700 and 37868 and proof PID 12644 stopped. The B06 predecessor fixture passed with the same build and also freed both ports. [B08 decisive log](../../../tmp/phase5-development/b08-fixture/logs/release-fix-dual.log) · [B06 regression](../../../tmp/phase5-development/b08-fixture/logs/release-fix-b06.log) |

## Reproduction and hashes

Use the isolated cache, `-o -B -C` settings and launcher classpath from the original evidence. The focused command is `-pl application -am test '-Dtest=GuardedReleaseExpiryTest' '-Dsurefire.failIfNoSpecifiedTests=false'`. For the expiry red, add the correction's focused test source to a temporary archive of `dc42b9a`. For the session-loss red, package the expiry-only source change and use the extended fixture with the original JDBC classifier. Rebuild packaged reactor artifacts and recompile the launchers before the green runs. [Toolchain and full commands](evidence.md#reproduction)

~~~powershell
& $java -cp $cp B08Postgresql run $bin $runs b08-release-green
& $java -cp $cp B06Postgresql run $bin $runs b06-b08-release-fix
~~~

| Log | SHA-256 |
| --- | --- |
| `expiry-guard-red.log` | `b11a4b41221a5403d2a8d8bd6747b2f8406ec1cf61c5ee289402fa12ed4ce47e` |
| `expiry-fix-before-session-classification.log` | `b8afdc65a9daa6a321056e06f12a8e7a0e8fb3556f6b02521d0eb4e1700d098e` |
| `resolution-session-red.log` | `720032952f0b433a5986132e0207b9b7ceea350950e023d7db077b4fe1ef4162` |
| `release-fix-offline-verify.log` | `aedf0e5f7f920dba4c4e8ec066b09a26868d56fecccfc43b3ebde480cfae43cf` |
| `release-fix-dual.log` | `c022dd422b3870bdfa19d586c0470e934d95f6112349f4aec5b2cd967c642ce1` |
| `release-fix-b06.log` | `4e958a23bac408a668651ed7c7551705e88d85d895943ed85d69567308757c18` |

The real-engine application calls use Spring MockMvc. Listening HTTP and connector-restart evidence await the standalone runtime disposition. The certificate and test clock remain scoped to the immutable synthetic writer generation. [Runtime gate](g0-b-http-runtime-request.md)
