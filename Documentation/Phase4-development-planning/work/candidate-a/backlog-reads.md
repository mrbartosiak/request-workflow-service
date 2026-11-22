# Phase 5 backlog · protected reads and retention

## APP-04 Live keyset and authorized browse filter

**Needs** `PG-04`. **Scope** Ordered SQL and cursor codec. **Red** Test over 10 visible rows plus hidden matches, immutable epoch/ordinal/ref ties with byte order, exact name/state AND and filter masking before lookahead, page 1–50, and wrong cursor principal/filter/size/order/generation/expiry. **Green** Build live keyset relation and opaque bounded cursor. Apply lookahead under same fresh shared guard as handoff. **Stop/handoff** Record fixed-set traversal, changed membership, 400/restart inputs and no hidden count or continuation effects. End-to-end terminal/proof release regression follows. [Browse steps and cursor](../../../Phase3-LLD/queries.md) · [E3](../../../Phase3-LLD/verification.md)

## APP-05 Detail, history and page release races

**Needs** `APP-03`, `APP-04`, `PRF-02`. **Scope** Full view and masked history/metadata with end-to-end release tests. **Red** Require distinct history/read/delete-metadata grants. Force Delete before and after detail/context and a size-one page handoff including the lookahead row. Assert hidden fields, token usability and correct cursor disappear when Delete wins. **Green** Join three read paths, guarded current authorization, confirmed publication projection and bounded handoff. Show only complete ordered facts with allowed fields and creation null, plus descriptive current revision when granted. **Stop/handoff** Record HTTP, authority and DB evidence with no actor/reason/body/timestamp in history. Publish verification extends this regression. [History and details](../../../Phase3-LLD/queries.md) · [E3/E4 forced release order](../../../Phase3-LLD/verification.md)

## APP-06 Hold, logical purge and physical cleanup

**Needs** `APP-05`, `PRF-03`. **Scope** Synthetic timed retention and independent hold/purge ledger. **Red** With the fixture clock, assert 30/90-day eligibility, both hold/purge/read orders, prior hold blocks cleanup, retired version cannot revive after failed cleanup, preserved ordered facts, and scoped access before purge only. **Green** Implement bounded cleanup with independent logical retirement, guarded idempotent deletion and restore application seam. Keep retained payload access under synthetic scopes. **Stop/handoff** Capture ledger ordering, attempt/time and no leaked data. `REC-03` proves rewind and `PROD-04` owns operational access. [Retention and ordering](../../../Phase3-LLD/retention.md) · [Witness ledger](../../../Phase3-LLD/witness-records.md)
