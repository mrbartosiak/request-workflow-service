# Phase 3 identifier requirements

**Approved by the owner on 2026-11-22 (Europe/Warsaw)** These constraints bind both LLD candidates and refine DEC-13, including the prior HLD statement that publication IDs carry no ordering promise. [Owner selections](owner-kickoff.md) · [Prior HLD direction](../Phase2-HLD/decision-proposals.md)

- **Create reference.** Globally unique immutable opaque string. Clients must never parse, construct, or infer meaning from it.
- **Publish ID.** Positive, monotonically increasing signed 64-bit integer within each explicitly defined namespace. Gaps are permitted. Never reuse issued IDs after deletion, rollback, migration, backup restoration or disaster recovery. Preserve initial uniqueness across all initial types. Define assignment ordering and prove it under concurrent publication. Preserve the published ID on authorized retries and throughout any future approved re-publication behavior.
- **Restore safety.** Use a durable mechanism that accounts for IDs issued after the restored backup, such as a high-water mark outside the restored failure boundary or safe reserved ranges. Keep publishing blocked while proof is unavailable. Restoring a database sequence alone is insufficient. Both candidates must give a concrete old-backup recovery scenario and show all previously issued IDs are excluded.
- **Wire and display.** JSON transmits the signed 64-bit value as a lossless decimal string. Prohibit truncation and floating-point conversion. Decimal display may use ordinary digits. Display width and zero-padding are optional and may never change identity.
- **External mappings.** Persist explicit mappings scoped by source system, tenant and identifier type where applicable. Treat external IDs as scoped. Preserve published identifiers and mappings across migrations, restores and retries without silent reassignment or recycling.
- **Namespaces.** Document stable uniqueness boundaries. Namespace evolution must preserve existing identity and prevent collisions.

Use the common initial lifecycle as approved. The requirement about re-publication preserves identifier identity if a later variant is approved. [Workflow](../Phase2-HLD/workflow-and-history.md)
