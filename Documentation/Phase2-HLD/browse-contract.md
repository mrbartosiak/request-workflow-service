# Browse filters, permissions and paging

**Scope** Paginated list, with proposed wire path `GET /requests`. **Status** Name and state filters and default size 10 are required. DEC-11 approves the match, ordering and cursor direction and query-field authorization. Exact wire and access settings remain open. [F-07](../Phase1-conceptual/features.md#owner-approved-interview-features) · [Q-12](../Phase1-conceptual/quality-acceptance.md#q-12-paginated-browsing-and-filters)

## Approved semantics

- Match name exactly and case-sensitively against validated stored text. Match the current lifecycle state exactly. Combine supplied filters with AND. Agree any input normalization explicitly. An empty match returns an empty page.
- Order by immutable creation order descending with a unique stable reference tie-breaker. Omitted size yields 10. Approve page-size bounds and exact parameter syntax under DEC-11.
- Use an opaque continuation tied to caller/scope, ordering, filters and page size. Validate its scope and reauthorize each page. A token confers no permission. Browse observes the current dataset. Concurrent state or access changes can affect membership on later pages. Fixed-set traversal retains a stable order.

## Authorization before matching

Establish authorized row and retention visibility, then whether each supplied filter field may be queried for each eligible row. If a row's filter field is protected, exclude that row from matching for that filter, before any match contributes to page selection, a continuation or a count. Scope any future counts to the same authorized set. Apply output-field masking separately. Check current permission before response release. An unauthorized value must have no effect on observable match, page, count or continuation behavior. [Access and approvals](access-and-approvals.md) · [Deleted-data design](workflow-and-history.md#approved-representation-and-open-settings)

## Alternatives and evidence

Case-insensitive exact matching improves convenience and requires an agreed Unicode and storage equivalence contract. Offset paging is addressable by page number and can shift under changes. Snapshot paging stabilizes membership at higher cost and still needs live access checks. Test more than 10 fixed visible requests, ties, separate and combined filters, empty matches, and protected or deleted fields. Vary unauthorized values while holding visible facts fixed. Compare authorized contents and order, continuation presence, counts if exposed, and results when following each token. Opaque token encodings may differ. Recheck permission changes between pages. [Owner alternatives](../tmp/phase2-hld-execution/owner-comparison/data-and-browsing.md#dec-11-browsing-and-history) · [Acceptance evidence](acceptance-evidence.md)
