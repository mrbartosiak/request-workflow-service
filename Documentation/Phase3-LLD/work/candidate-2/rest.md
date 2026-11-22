# REST and entry validation

**Scope** F-01–08, Q-04, DEC-06/08/11/13. **Status** Required behavior and proposed exact wire. All paths are unversioned. Request and response JSON use UTF-8. [HLD REST](../../../Phase2-HLD/rest-and-browsing.md)

| Method and path | Input | Success |
| --- | --- | --- |
| POST /requests | type, name, body | 201 plus Location /requests/{ref}, MutationResult |
| GET /requests/{ref} | None | 200 Detail |
| GET /requests/{ref}/action-context?action={action} | Action code | 200 ActionContext |
| PATCH /requests/{ref}/body | body, expectedRevision | 200 MutationResult |
| POST /requests/{ref}/actions/{action} | expectedRevision and reason only for delete/reject | 200 MutationResult |
| GET /requests | name, state, limit, cursor | 200 BrowsePage |
| GET /requests/{ref}/history | None | 200 HistoryView |
| POST /request-command-keys | Empty body | 201 containing key, expiresAt |

Path action codes are verify, accept, reject, delete, publish. Context also accepts edit. All business writes carry the issued `Idempotency-Key` header. The key issue endpoint has a separate scope grant and allocates no business effect. Identifiers, keys and tokens are case-sensitive opaque strings. [Observations](observations.md) · [Retries](retries.md)

`MutationResult` contains ref, action, state and result revision as a decimal string and, if permitted for Publish, publicationId as a decimal string. Field grants may omit protected fields and may suppress the whole result, including Location. On an authorized replay the status and original business values are the same under today's masks. `resultRevision` is descriptive. Detail contains ref and permitted type, name, body, state, publicationId and current display revision. Field omission represents lack of visibility. No payload copies are returned by mutation/replay. ActionContext, BrowsePage and HistoryView are defined in their modules. Protected data responses use `Cache-Control: no-store`. [Access](access.md) · [Identifiers](identifiers.md)

**Proposed validator.** Authenticate before decoding protected input. Limit raw JSON request body to 128 KiB and reject oversize independently of claimed length. Accept exactly the documented properties once each. Reject missing/null/wrong-type properties, duplicate headers/query keys, unsupported type/state/action, malformed UTF-8, lone surrogates and U+0000. Preserve code points, punctuation, whitespace and case. Reject mandatory text made entirely of Unicode White_Space (version set in contract tests). Bound name to 256 UTF-8 bytes, body to 16 KiB and reason to 2 KiB. Apply the same prospective storage check to exact-name query text. No normalization. Treat names as immutable. Cap token/header input before decoding at 4096 bytes per token. Storage capability checks pin further restrictions before serving a deployment. Failed structural checks invoke zero mutation attempts. [Browse](browse-history.md) · [PostgreSQL character types](https://www.postgresql.org/docs/18/datatype-character.html)

| HTTP / stable code | Outcome |
| --- | --- |
| 400 INVALID_INPUT / INVALID_CURSOR | Malformed input or invalid paging parameters. |
| 401 AUTHENTICATION_REQUIRED | Missing/invalid credentials. |
| 403 ACTION_DENIED | Denied action where target disclosure is allowed, or denied targetless Create. |
| 404 NOT_FOUND | Absent/concealed target, or concealment required for a keyed outcome. |
| 409 STALE_OBSERVATION / ILLEGAL_STATE / KEY_CONFLICT / KEY_EXPIRED / KEY_FENCED | Distinct known outcomes after required authorization. |
| 413 INPUT_TOO_LARGE / 415 UNSUPPORTED_MEDIA | Entry checks. |
| 429 ADMISSION_LIMIT | Perimeter admission before work, optional Retry-After. |
| 503 AUTHORITY_UNAVAILABLE / OUTCOME_PENDING / OUTCOME_UNKNOWN | Unavailable proof, bounded in-flight wait or uncertain commit. Retry unchanged with the same key. |

`ApiError` contains status, code and a server-generated requestId. Add allowlisted field names for input errors only. KEY_EXPIRED and KEY_FENCED use the same JSON shape, content type and no-store policy across concealed receipt variants. No Location, receipt-derived header, retry hint or result metadata on those responses. Only the correlation value and ordinary transport metadata may vary. Unexpected errors use 500 INTERNAL_ERROR unless a commit may have happened, then use OUTCOME_UNKNOWN. [Retry precedence](retries.md) · [Access](access.md)
