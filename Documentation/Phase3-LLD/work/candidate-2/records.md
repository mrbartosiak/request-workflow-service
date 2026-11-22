# Logical persistence contracts

**Scope** F-01–09, Q-02/03/10/11/13, DEC-03/05/08/11/13. **Status** Logical invariants required. Schemas and PostgreSQL mapping are proposed evidence design. [HLD consistency](../../../Phase2-HLD/local-consistency.md)

| Record | Logical keys, invariant and links |
| --- | --- |
| request_header | PK ref, globally unique. Org/type/name/origin person/creation order immutable. State, revision and history ordinal checked positive. Ownership/assignment and decision-version references are scoped. Publication namespace/value both present exactly when PUBLISHED initially. Unique pair (publication namespace, value) where present, value in positive signed 64-bit range. |
| request_body | PK/FK ref, UTF-8 body. Required at creation; may be absent following authorized purge. |
| lifecycle_change | PK (ref, ordinal), FK ref. Create ordinal 1 and from absent. Store from/to, time and protected actor link. Current state/ordinal equal last row. Earlier rows immutable. |
| lifecycle_reason | PK/FK (ref, ordinal). Only for Delete/Reject. Required in the commit, purge separately after retention. |
| scope_gate, scoped_grant, decision_fact | Scope gate PK org. Grants keyed by scope/principal/action/field and versioned within the gate. Facts keyed by org/ref/kind with source version, freshness deadline and approved context values. Holds, assignments and security-relevant evidence use common locking. |
| operation_receipt | Unique (trusted issuer, org, stable principal, key ID). Canonical intent digest/version, action, target/type/scope, result state/revision, optional published ID, expiry and original outcome class. FK ref on successful binding. In-flight placeholder uses this key in the same transaction; invariant check requires complete receipt at commit. |
| global_creation, publish_serial | Single creation counter per active generation; dedicated publication lock row per stable namespace. Values never wrap. |
| publication_witness | PK journal attempt ID. Unique (namespace, value), unique ref for initial lifecycle. Preserve generation, association, result revision and scoped receipt identity without requiring the expirable receipt row to survive. |
| scope_authority_witness | PK (scope, independent sequence), version/digest and writer generation. Bind the local authority change to the pending journal entry. |
| external_mapping | Unique (source system, source tenant, identifier type, external ID canonical bytes). Immutable internal ref and optional publication identity, scoped destination evidence. Mapping registry is authoritative for non-reuse across recovery. |
| delivery_obligation / delivery_attempt | Unique effect ID and per-effect attempt counter, FK to local command/effect or inbound processing obligation. Persist claims, statuses and proof references. |
| inbound_receipt | Unique trusted connector/source tenant/event identity. Bind target, source revision and intent digest, with effect or durable processing obligation on first accepted receipt. |

Key access paths are request lookup by ref with scope enforcement, ordered history by PK, receipt by its scoped unique key, and eligible browse rows by org and descending creation tuple, with authorized exact-name/current-state options. Persist name equality key as its validated UTF-8 bytes when database text equality depends on collation. Use separate access paths for delayed dispatch (destination, state, availableAfter) and eligibility for redaction. State/action legality, required reason at change, history contiguity and atomic completeness are enforced through the single mutation boundary with constraints where locally expressible. Credentials restrict direct writes; migrations and restores run integrity attestation before opening the service. Outside authority records and delivery keys are described separately. [Transactions](transactions.md) · [Identifiers](identifiers.md) · [Delivery](delivery.md)
