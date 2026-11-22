# B33 handoff for B34 and B39

Candidate `195636a332cc9d31f197dabe8dd19913198dda83` exercised the adopted Create, Edit, Verify and Accept routes in two service JVMs over qualified workflow and proof stores. Each worker uses the normal packaged MVC, authorization, transaction, receipt and release paths. Child lifecycles, key gates and backend markers stay on the scratch launcher classpath. The reproduction instructions describe the port lease and ownership marker. [Fixture](../../../../tmp/phase5-development/b33-key-contention/fixtures/postgresql/b33/README.md) · [Execution evidence](evidence.md)

## B34 commit and retry cuts

- Use `B33App` for raw requests, fixed trusted time, independent process and backend identities, and bounded child shutdown. Use B32's `AFTER_CLAIM` and `BEFORE_COMMIT` to associate phases with the actual JDBC lease. Preserve the application's 200 ms lock deadline when scheduling the contender.
- Use the `B33Evidence` state inventory and fingerprint for Create receipt, workflow, ordinal and reservation invariants. Its exact success comparator covers HTTP status, stable wire headers, Location and response body bytes. Extend the inventory for publication mappings, obligations, and scope or publication witnesses involved in the cut.
- The B33 concealment gate reuses the B32 gate transport at the point where B's claim returned zero, before receipt lookup. B34's transport or commit uncertainty needs its own distinct cut. [B34 acceptance](../../../Phase4-development-planning/db-verification.md#b34--commit-response-and-retry-uncertainty--p3p5)

## B39 normalized receipt and access comparisons

- Reuse the submitted-denial and original-result-denial probes. The former stops before receipt claim or lookup. The latter observes a duplicate claim and a guarded lookup after a certified change to current disclosure. The raw 404 comparator checks equal stable headers and exact normalized problem fields, with only Date and per-attempt correlation values normalized.
- Capture workflow and independent release evidence at the same explicit cut. A legitimate same-key replay can add preparation and handoff records while business rows, creation ordinal and reservations remain fixed. Conflict, concealment and expiry require unchanged business and release evidence apart from the separately audited scope operation.
- Add B39's original-lineage proof provider, backup/restore and purge probes to this comparison. The B33 fixture uses current writer generation and live scope projection. [B39 acceptance](../../../Phase4-development-planning/restore-verification.md#b39--authority-hold-and-receipt-continuity-after-backup)
