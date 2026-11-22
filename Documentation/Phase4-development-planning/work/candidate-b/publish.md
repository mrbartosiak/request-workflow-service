# B16–B18 · Publish and persistent associations

### B16 · Independent journal reservation and numeric order

**Needs** B05 and B11. **Sources** Global namespace, exact signed range, journal cuts and gate. [Identifiers](../../../Phase3-LLD/identifiers.md#publish-assignment-and-proof) · [Publication journal](../../../Phase3-LLD/publication-journal.md) · [Witness records](../../../Phase3-LLD/witness-records.md)

**Red** Share namespace across types and organizations. Force delayed reserve reply, duplicate/mismatched attempt, known rollback after burn, exhausted range and stale epoch. Compare strict increasing assignment and exact signed-64-bit values above 2^53. **Green** Independent durable fixture authority, stable attempt IDs and PENDING/COMMITTED/ABORTED handling, fixture `PublishGate`, bounded deadlines and original lineage checks. **Stop** Capture high-water, reservations and durable gate order. Hand reservation service to B17/B29/B37.

### B17 · Commit Publish and confirm original result

**Needs** B12, B15 and B16. **Sources** Lock order, Publish guard, scoped result and confirmation proof. [Persistence](../../../Phase3-LLD/persistence.md) · [Resolver](../../../Phase3-LLD/proof-resolution.md#resolver-and-gates) · [Identifiers](../../../Phase3-LLD/identifiers.md)

**Red** Require named route, independent grant, exact decimal-string ID, monotonic commit order and unchanged original replay including response loss. Where the scope delta applies, withhold each required confirmation and then finish in both orders. Assert that protected ID and admission port stay closed before both confirmations and current scope proof. B19/B20 and B23 exercise integrated read and arm, with both confirmation orders in B40. Force rollback after reservation. **Green** Prepare applicable ordered scope delta before taking the namespace gate and reserving the number. Commit Publish state, fact, receipt and applicable local witnesses atomically. Require independent confirmation and guarded projection before release. Expose an application-owned append boundary for B22 and keep obligation triggers disabled until B22 proves atomic command-connection append. **Stop** Record commit versus proof order, burn, zero second effect and gated result. Hand Publish command and proof signals to B18/B19–B22/B34–B38.

### B18 · Immutable external mapping

**Needs** B10, B12 and B17. **Sources** Scoped external mapping identity, original Create/Publish evidence and restore. [Mapping rules](../../../Phase3-LLD/identifiers.md#external-mappings) · [Restore matrix](../../../Phase3-LLD/restore-evidence.md)

**Red** Try reserving before Create commit and after rollback, then after committed Create with proper scope. Reuse equal bytes in another tenant/type, test incompatible rebinding and missing original association. **Green** Independently durable mapping registry and verified local projection. Reconcile identical pair, quarantine conflicts and fence unproved keys. **Stop** Record exactly one accepted binding per scoped key, no provisional ref leakage and recovery contract. Hand mapping contract to B23/B26/B31.
