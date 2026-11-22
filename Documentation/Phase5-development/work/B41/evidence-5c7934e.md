# B41 correction evidence · Body read classification

**Candidate** `5c7934e572545af263e412e28e043ad981d1112b`, tree `3529a7d512cb578f3f998674398ddbc250f25cab`, on `codex/b41-body-read-evidence`. Direct parent `1fc1d65f0372698f31f5f331771f2b8ec864fef2`, the revision in the initial Astra review. GPT-6.2 Sol high (55) executed the correction. The candidate changes the validator and one MVC test source. Archive SHA-256 `732a9c7aeade835e8a415329ef45255610375786db6a2ee6a15a34583b8dfadb`. [Candidate archive](../../../tmp/phase5-development/b41-fix/candidate-5c7934e.tar) · [Initial review](review.md) · [Original execution evidence](evidence.md)

## Correction

Body reads now distinguish the servlet stream from gzip decoding. The gzip adapter stores any checked failure raised by the servlet stream, rethrows it to the decoder and checks the recorded failure even when the decoder consumes it during member lookahead. A checked exception during gzip construction still follows the decoder's inflater cleanup path. The transport classifier checks the cause chain for `SocketTimeoutException`. Decoder EOF on a completely supplied HTTP entity remains a validation failure. The decompressed limit remains 128 KiB. The existing problem schema, status handling and structured record schema apply. [Validator](../../../tmp/phase5-development/b41-structured-evidence/adapter-http/src/main/java/com/company/requests/adapter/http/RestInputValidator.java#L230)

## Bounded red and green

The first run exported the exact reviewed parent and copied in only the new test source. The single focused JUnit test ran and failed all four outcome assertions. The same test passed on the corrected revision in both clean builds. It goes through the ingress filter, current Create controller, validator and MVC problem advice. The fault inputs were bounded `ServletInputStream` doubles and in-memory bodies. [Exact-parent red](../../../tmp/phase5-development/b41-fix/logs/red-mvc.log) · [Test source](../../../tmp/phase5-development/b41-structured-evidence/bootstrap/src/test/java/com/company/requests/bootstrap/RequestEvidenceTest.java#L153)

| Input at the servlet boundary | MVC status | Outcome | Stage |
| --- | --- | --- | --- |
| Wrapped socket read timeout after one identity byte | 400 | `TRANSPORT_TIMEOUT` | `TRANSPORT` |
| Wrapped socket read timeout at gzip header read | 400 | `TRANSPORT_TIMEOUT` | `TRANSPORT` |
| Ordinary servlet read `IOException` during gzip lookahead | 400 | `TRANSPORT_FAILURE` | `TRANSPORT` |
| Fully supplied in-memory gzip body with incomplete trailer | 400 | `INVALID_INPUT` | `VALIDATION` |

The four responses retained the `invalid_input` problem type, problem media type and no-store header. Each returned a correlation ID that matched one start, one error and one outcome. The oracle found four distinct IDs, 12 records, zero command transactions and zero matches for the seeded bearer, idempotency key, body and exception message. The existing direct-timeout MVC contract and the identity and gzip checks for exactly 128 KiB and one byte over also passed. The latter exercise successful gzip decoding at the accepted boundary and 413 beyond it. [MVC report](../../../tmp/phase5-development/b41-fix/logs/default-request-evidence.xml) · [HTTP boundary report](../../../tmp/phase5-development/b41-fix/logs/default-http-boundary.xml)

The new evidence ends at the in-process servlet boundary. The earlier Astra review supplies the packaged connector's observed 408 on the reviewed parent. Exact corrected wire behavior and a fresh database restart are separate from this offline qualification. [Initial packaged observations](review.md)

## Frozen build evidence

The exact default and `synthetic-http` offline clean builds each passed 320 tests in ten suites and 11 reactor projects, with zero failures, errors or skips. The default JAR excluded both opt-in listener classes and the profile JAR contained them. Both bootstrap graphs matched the corresponding approved B08 graphs byte for byte. The artifact manifest SHA-256 is `08df63931adf4ae17d7a6295de92ae4d1f02c33be94c9f5048ecea56fcb460c8`. [Default build](../../../tmp/phase5-development/b41-fix/logs/default-clean-verify.log) · [Profile build](../../../tmp/phase5-development/b41-fix/logs/profile-clean-verify.log) · [Artifact manifest](../../../tmp/phase5-development/b41-fix/in-process-artifacts.sha256)

## Reproduction

Use the adopted Maven 3.9.16 and Temurin 25.0.5.1+2, both `b01-tools/m2/settings.xml` arguments and a private copy of the qualified cache. Run from the exact candidate checkout. Reproduce the red by exporting the reviewed parent and copying only the candidate's `RequestEvidenceTest.java` into that export before the focused command. [Executed wrapper](../../../tmp/phase5-development/b41-fix/build.ps1) · [Runtime approval](../g0-b-wave2-approval.md)

~~~powershell
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" clean verify
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" -Psynthetic-http clean verify
& $maven -o -B -C -ntp -s $settings -gs $settings "-Dmaven.repo.local=$cache" `
    '-Dtest=RequestEvidenceTest#classifiesBodyReadFailuresAndCompleteMalformedGzipByOrigin' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
~~~
