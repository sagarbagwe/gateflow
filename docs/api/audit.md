# Audit API

Authenticated, tenant-scoped reads require a fresh active membership and AUDIT_VIEW.
This permission intentionally grants organization-wide audit evidence, not arbitrary
request-detail access. Unknown/inactive/foreign organization: 404. Active member
without permission: 403. Foreign or absent entry: 404 AUDIT_NOT_FOUND.

- GET /api/v1/organizations/{org}/audit-logs
- GET /api/v1/organizations/{org}/audit-logs/{id}

List parameters: action, resourceType (exact uppercase tokens), resourceId,
actorMembershipId (UUIDs), requestId (safe correlation identifier), from and before
(ISO-8601 instants). Date range is [from, before); from must precede before.
limit defaults 20, range 1–100; offset defaults 0, range 0–10000.
Invalid bounds/filter combinations: 400 INVALID_AUDIT_QUERY; malformed UUID/time: 400.
Parameters are bound, not interpolated into SQL. No user-controlled SQL ordering.

List is a PageSlice: items, limit, offset, hasMore, nextOffset; fetched as limit+1.
Entries contain id, actorKind, actorMembershipId, action, resourceType, resourceId,
requestId, occurredAt. JSON snapshots are not loaded for lists. Sort: occurredAt
DESC, id DESC. Offset pages can shift as events arrive; not a consistent export.

Detail returns entry, oldValue, newValue, snapshotRedacted. Old/new are nullable
allowlisted JSON evidence, not full resource payloads. Oversize historical snapshots
are omitted; unsupported historical keys are removed and flagged, without modifying
the persisted record. Correlation IDs match successful command X-Request-ID headers.

No public create/update/delete audit routes. Supported writes happen only inside
business transactions. Unsupported methods receive 405 after authentication/CSRF.
No full-text snapshot search, bulk export or retention deletion API is implemented.
