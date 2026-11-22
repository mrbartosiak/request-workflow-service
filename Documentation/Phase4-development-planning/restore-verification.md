# B37–B40 · Restore and proof evidence

### B37 · Rewind, previous writers, cursors and observations · P9

**Needs** B21, B31 and B32. **Sources** Old backup, generation and old receipt variants. [P9](../Phase3-LLD/postgres-evidence.md) · [Recovery](../Phase3-LLD/recovery.md)

**Red** Delay original writer, restore old backup, reuse the same revision on different facts and exercise old token/cursor/key. Compare missing versus concealed past-generation receipts. **Green** Deterministic checks prove writer and egress fenced, new epoch/ordinal 1 ordering, old token/cursor failure, exact successful prior replay where allowed and uniform fenced failure otherwise. **Stop** Capture generations, effect and reserve counts, normalized headers/body, guard order and newly authorized result. Hand evidence to B44/B51/B55.

### B38 · Publication and mapping through rewind

**Needs** B18, B31, B32 and B34. **Sources** ID watermark and original association recapture. [Restore variants](../Phase3-LLD/restore-evidence.md) · [Journal](../Phase3-LLD/publication-journal.md)

**Red** Backup at 817. Prove or withhold original 818 with lost response and with missing confirmation. Burn 819, include 820 committed and 821 reserved reply lost. Delay reserve after terminal closure. Test provisional Create mapping and committed mapping recovery. **Green** Assert original complete effects or target fence, namespace gate on incomplete coverage and 822 or higher only with full proof. Neither a delayed reply nor absent restored row authorizes reuse. **Stop** Report state, original association, receipt, high-water, provisional ref isolation, proof gates and zero second remote effects. Hand suite to B51/B55.

### B39 · Authority, hold and receipt continuity after backup

**Needs** B27, B30, B31, B32, B33 and B36. **Sources** Scope authority, retention, normalized old key and inbound coverage. [Restore matrix](../Phase3-LLD/restore-evidence.md) · [Authority drill](../Phase3-LLD/authority-continuity.md) · [Inbound restore](../Phase3-LLD/inbound-recovery.md)

**Red** Post-backup revoke, reassign and purge, then run protected reads/commands/replays/arming before and after complete proof. Run expired, old concealed, missing, matching and changed receipt cases, late revoke and lost ACK inbound branch, comparing another certified scope. **Green** Add constrained matrix proving no protected release/effect on gaps, no revived payload, former actor stays denied, new actor acts with fresh context and trusted inbound does not duplicate. Force restricted payload release before and after durable purge. Require an authorized view before and zero exposure after. **Stop** Capture lookups, normalized responses, ledger and remote/inbox counts, including forwarded evidence for applicable P9/E6/E7. Hand results to B52–B55.

### B40 · Prepare, confirmation and client outcome cuts

**Needs** B11, B24, B31, B32 and B34. **Sources** All six cut rows, witness definitions and proof gate order. [Proof evidence](../Phase3-LLD/proof-evidence.md) · [Witnesses](../Phase3-LLD/witness-records.md)

**Red** Drop or delay scope prepare/repeat/abort. Vary confirmation order. Substitute mismatched or missing lineage. Cut before enqueue, after enqueue with and without independent handoff proof, and after evidenced client receipt. Include alternate-instance exact replay, claims and restored loss. **Green** Record every cut with no public receipt cue or extra business effect while fenced, and exactly one existing obligation becomes newly eligible after confirmed proof, with zero second mutation, ID, fact, receipt or obligation. Classify only positively acknowledged missing effects as lost acknowledgments. **Stop** Attach original attempt/digest, positive proof and response, load lineage and exact durable counts. Hand loss taxonomy and evidence to B44/B55.
