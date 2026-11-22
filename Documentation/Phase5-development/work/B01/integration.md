# B01 integration and handoff

On 2026-11-22 (Europe/Warsaw), the coordinator fast-forwarded local `master` from `98c5802c2efc512d14dabf0a6ab59bd567571754` to approved candidate `056c4e0bbb74df16b16684aad5fa1dc82cf1f22a`. [G0-B adoption](g0-b-adoption.md) · [Independent review](build-review.md)

On the integrated checkout, Maven 3.9.16 with Temurin `25.0.5.1+2` and the approved isolated cache passed the offline `clean verify` across all 11 reactor projects with all four tests passing. All ten JAR hashes matched the reviewed build. The integration run log SHA-256 is `819351f70f7cfb25710fe8e3fe4369f9d75bebb445fe4bb0604eee6f996d2029`. [Exact reproduction](build-evidence.md#reproduction) · [Offline integration log](../../../tmp/phase5-development/b01-tools/logs/b01-integrated-offline-verify.log)

Use the adopted Maven settings, validated JDK, and `mvn -o -B -C clean verify` with the approved cache. Use `-pl domain -am verify` for B02, `-pl adapter-http -am verify` for B03 and `-pl adapter-jdbc -am verify` for local B04 build checks. B04 fixture qualification requires G1-Q. [Incoming ticket criteria](../../../Phase4-development-planning/foundation.md)
