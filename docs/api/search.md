# Request search and reviewer inbox (M6)

Implemented, authenticated, read-only routes:

```text
GET /api/v1/organizations/{orgId}/requests
GET /api/v1/organizations/{orgId}/requests/inbox
```

## Access contract

The authenticated user and verified active organization membership—not a supplied
actor ID or header—determine visibility. Every page reloads current grants.

- `VISIBLE` (default): `REQUEST_VIEW_ALL`, or own requests with `REQUEST_VIEW_OWN`,
  or current `REQUEST_APPROVE` plus an eligible ACTIVE assignment or an existing
  decision by this member. Otherwise 403.
- `OWN`: requester is current membership, with VIEW_OWN or VIEW_ALL. Approval-only
  reviewers receive 403 rather than silently gaining own-request read permission.
- `INBOX`: requires REQUEST_APPROVE and includes only IN_REVIEW requests whose
  ACTIVE step is assigned to this member, who still holds the required approver
  role and is not the requester. VIEW_ALL alone does not authorize this endpoint.
- `/inbox` forces INBOX; a conflicting scope is 400. `?scope=INBOX` is equivalent.
- Historical reviewer visibility requires current APPROVE but not continued
  membership in the old required role. A waiting assignee cannot read on assignment
  alone. Detail and list share RequestAccessPolicy; invisible detail is 404.
- Outsiders/suspended members see 404 ORGANIZATION_NOT_FOUND. Anonymous callers
  receive 401. A foreign/nonexistent workflow filter produces an empty result,
  never a cross-tenant lookup or disclosure.

Access predicates run in SQL **before** limit/offset. A REPEATABLE_READ transaction
keeps authorization and results consistent within one request. New requests start
new snapshots; revocations already committed before the next page take effect.
An in-flight read can finish on its existing snapshot.

## Query parameters

| Parameter | Values / behavior |
| --- | --- |
| scope | VISIBLE, OWN, INBOX; default VISIBLE |
| pagination | CURSOR (default) or OFFSET |
| limit | 1–100, default 20; fetches one sentinel row |
| cursor | Opaque continuation from prior cursor page; max 768 characters |
| offset | OFFSET mode only, 0–10,000, default 0 |
| sort | CREATED_DESC (default) or CREATED_ASC; UUID tie-breaker same direction |
| q | Max 200 Java string characters; title/description full-text search; blank clears |
| status | DRAFT, IN_REVIEW, APPROVED, REJECTED, WITHDRAWN; repeat to OR, up to 5 occurrences |
| type | PURCHASE, SOFTWARE_ACCESS, POLICY_EXCEPTION |
| workflowId | Canonical UUID for workflow definition, not version |
| createdFrom | ISO-8601 timestamp with offset; inclusive |
| createdBefore | ISO-8601 timestamp with offset; exclusive, later than from |

Filters combine with AND. Scalar parameters cannot repeat. Unknown names, empty
scalar values (except q), invalid enums/UUIDs/bounds, NUL search text, or incompatible
pagination parameters return 400 INVALID_SEARCH_QUERY with sanitized ProblemDetail
and X-Request-ID. No user-controlled SQL identifiers or order expressions.
CURSOR mode rejects offset even if zero; OFFSET mode rejects a cursor.

`plainto_tsquery('simple', q)` treats words as literal tokens with AND semantics.
Tokens are case-insensitive, with no stemming, prefix/fuzzy matching, relevance
ranking, or accent folding. Punctuation-only queries return no matches. Searching
`engineering missing` does not mean either word; `eng` does not match `engineering`.
Details/comments/identities are not indexed. Unicode tokenization follows PostgreSQL,
not a bespoke multilingual segmentation engine.

## Examples

```text
GET .../requests?scope=OWN&status=IN_REVIEW&limit=20
GET .../requests/inbox?type=PURCHASE&q=engineering%20laptop
GET .../requests?status=APPROVED&status=REJECTED&sort=CREATED_ASC
GET .../requests?createdFrom=2020-01-01T00:00:00Z&createdBefore=2020-02-01T00:00:00Z
GET .../requests?pagination=OFFSET&offset=40&limit=20
GET .../requests?limit=20&cursor=<returned-token>
```

Repeat the original filters/sort/scope on continuation; the token does not fill
parameters for you. Page size may change. Status order and equivalent timezone
representations normalize to the same context.

## Response

```json
{
  "items": [],
  "limit": 20,
  "hasMore": false,
  "nextCursor": null,
  "pagination": "CURSOR",
  "offset": null
}
```

Each item contains id, workflowDefinitionId, nullable workflowVersionId,
requesterMembershipId, title, requestType, purchaseAmount, currency, state, version,
createdAt, submittedAt, completedAt, and nullable activeStep (id, position, name,
assignedMembershipId, activatedAt). Amounts are JSON decimals; timestamps are UTC.
The list omits description/details, full step history, decisions and identities.
There is **no total count**: `hasMore` means the bounded query found a sentinel.
Cursor anchors the last returned item, not the sentinel. Offset pages have an
integer offset, no nextCursor, and must explicitly request the next offset.

## Cursor consistency and security

The versioned Base64URL token carries DB statement-time cutoff, immutable
(created_at,id) anchor, and SHA-256 context digest over organization, membership,
normalized scope/sort/filters. No passwords or grants. Exact field/type validation,
duplicate-JSON/trailing-input rejection, timestamp precision/range and size bounds
apply. Malformed/wrong-context continuations return 400 INVALID_CURSOR; revoked
scope access may return 403 first. Limit is deliberately outside the digest.

**This is an unsigned navigation token, not a credential or MAC.** A determined
caller can construct another navigation anchor/cutoff. This does not bypass fresh
authorization, tenant WHERE clauses, sort allowlists or result bounds. No
cryptographic tamper resistance, expiry, or frozen export guarantee is claimed.
Signing becomes appropriate if future cursors carry privileged server state.

First-page cutoff excludes normally timestamped inserts after that page. Each
page is a new snapshot: late commits with earlier timestamps, role/assignment
changes, or status changes can change eligibility. Even immutable coordinates do
not make this a point-in-time export. Offset navigation can shift after inserts.
For exact exports, later design a background job with an explicit snapshot.

Legacy/imported DRAFT rows are searchable; there is no create-draft API. M5 submits
directly to IN_REVIEW. These endpoints do not add new write commands.
