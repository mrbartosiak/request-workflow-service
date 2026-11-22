# Unversioned REST and validation

**Scope** F-01–08, Q-02/04/12/13, DEC-04/06/08/11/13. Named actions are preferred by the owner. Exact routes, fields, limits and mappings below are proposed. Use UTF-8 JSON and `Cache-Control: no-store`. [Source](../../../Phase2-HLD/rest-and-browsing.md) · [JSON](https://www.rfc-editor.org/rfc/rfc8259.html)

| Method and path | Accepted input | Successful body |
| --- | --- | --- |
| `POST /operation-keys` | Empty | 201 `{key,expiresAt}`. Allocates a signed client intent key, without a request effect. |
| `POST /requests` | `{type,name,body}` | 201 `MutationResult`, `Location: /requests/{ref}`. |
| `GET /requests/{ref}` | Empty | 200 `RequestView`. |
| `GET /requests/{ref}/action-context?action=…` | One action from `edit,verify,accept,reject,delete,publish` | 200 `{ref,action,state,context,expectedRevision}`. Separate action-specific context grant. |
| `PATCH /requests/{ref}/body` | `{body,expectedRevision}` | 200 `MutationResult`. |
| `POST /requests/{ref}/actions/{action}` | A named action `verify,accept,reject,delete,publish` with `{expectedRevision}`. For Reject and Delete require `{expectedRevision,reason}`. | 200 `MutationResult`. Publish includes the ID. |
| `GET /requests` | `name,state,size,cursor` query parameters | 200 `{items,nextCursor?}`. |
| `GET /requests/{ref}/history` | Empty | 200 `{ref,changes}`. Distinct history grant. |

`MutationResult` is `{ref,action,resultRevision,state,publicationId?}`. `resultRevision` is an informational string from the original commit and is never an action token. A successful authorized replay uses the original status and Location when applicable. `RequestView` is `{ref,type?,name?,body?,state?,publicationId?}`. Suppress restricted fields, with `publicationId` available only after Publish. `context` uses the action-specific shape in [access](access.md). The observation is obtainable from the context route alone. `Idempotency-Key` is mandatory for each business command. [Key contract](retries.md)

`RestInputValidator` authenticates through the security boundary, then checks bounded request bytes, media type and exact JSON shape before a mutation transaction. Proposed `type` values are `HR_TICKET, BUG_REPORT, INTERNAL_REQUEST` with a required explicit selection. Scope comes from trusted context. Reject unknown or duplicate JSON members, coercions, invalid UTF-8, unpaired surrogates, embedded U+0000, mandatory nulls, empty or Unicode White_Space-only strings and unexpected query parameters. Preserve accepted characters, whitespace and casing. Proposed max decoded UTF-8 bytes are name 256, body 16,384, reason 2,048, key 1,024, token 2,048, ref 128. Bound the request body at 128 KiB after decompression and limit a single key header. Validate cursor limits separately. Verify selected-engine text compatibility before release. [Browse limits](queries.md) · [Unicode properties](https://www.unicode.org/reports/tr44/#White_Space) · [PostgreSQL text](https://www.postgresql.org/docs/18/datatype-character.html)

Use Spring `ProblemDetail` with `application/problem+json`, status, stable `urn:requests:problem:<code>`, a fixed title, safe correlation ID and optional invalid field names. Use 400 `invalid_input` for syntax/shape, 413 `request_too_large`, 415 `unsupported_media`, 401 `authentication_required`, 404 `not_found` for absent/concealed, 403 `action_denied` only on safely disclosable targets, 429 `throttled`, and 503 `authority_unavailable` or `outcome_unknown`. Authorized 409 types are `illegal_state`, `stale_observation`, `key_conflict`, `key_expired` and `retry_fenced`. Return a generic 500 with correlation for unexpected faults. Keep method errors safe and include the applicable protocol headers. Validate before domain calls, then establish scoped access before targeted errors or replay cues. On 503 `outcome_unknown`, continue the same intent and key. [Problem details](https://www.rfc-editor.org/rfc/rfc9457.html) · [Retry cases](retries.md)
