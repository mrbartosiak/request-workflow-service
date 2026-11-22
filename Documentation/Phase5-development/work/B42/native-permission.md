# B42 local fixture approval

On 23 November 2026 the owner replied “Continue approved” to the pending request for the B42 two-JVM loopback fixture. The test uses the two owned HTTP service JVMs, disposable PostgreSQL on fixture ports 55480 and 55481, and synthetic test data. The existing preflight also checks that port 55482 is free. [B42 assignment](assignment.md)

The coordinator verified corrected candidate `dc0299d0c0b9b77ffe886db2826b6299c3393a8e`, the 65 prepared inputs and the 102 private launcher classes before launch. Prepared manifest SHA-256 `624dc4794213e908f1e3413deee9a2c32ec4a3b66ae9c3b18e31daaebd96ba57`. The earlier candidate remains preserved for the startup compatibility regression. [Independent offline review](review-dc0299d.md) · [Prepared checkpoint](../../../../tmp/phase5-development/b42-v2-execution/checkpoint.md)
