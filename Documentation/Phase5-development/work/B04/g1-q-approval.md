# G1-Q owner approval · Qualify the local PostgreSQL fixture

**Approved 2026-11-22 (Europe/Warsaw).** The owner replied “Approved, continue” directly after the G1-Q request. The approved request SHA-256 is `2d13a56aa0a75d19b70c9dbe4911f5e9950dd8da8715230b94144b529a4ef388`. This is the scoped qualification authorization for B04. [Exact request](g1-q-request.md) · [G0](../g0-kickoff.md)

**Authority and base** `PH4-PLAN-r1` manifest SHA-256 `f14d62ef661d686d50353bdb5f2a16466d25308dd432016dbf8305f4d915d4e6`, local `master` at `bdfbc00ec21a39aec37354e9ab77ce552a75feee`. Use the adopted B01 Maven and Java toolchain and B03 integration baseline. [B01 integration](../B01/integration.md) · [B03 integration](../B03/integration.md)

**Scope** Acquire and verify EDB PostgreSQL 18.7 Windows x64 `postgresql-18.7-1-windows-x64-binaries.zip` and Maven Central pgJDBC `42.7.14` plus its required driver dependency closure. Extract into workspace scratch. Start and stop disposable PostgreSQL processes bound to loopback, with separate workflow and proof data directories and credentials, for B04 version, role, lock, backup and restore qualification. Keep synthetic data, credentials and logs in scratch. Local review may independently repeat the same bounded checks. [B04](../../../Phase4-development-planning/foundation.md#b04--qualify-the-isolated-postgresql-evidence-environment)

Qualification covers user-owned local processes and their scratch backup and restore copies. Fixture reuse for B05 and B06 requires G1-U on the reviewed environment, access, provisioning, backup and teardown scope. [Action gates](../../../Phase4-development-planning/choices.md#later-authorizations)
