# Request activity API

`GET /api/v1/organizations/{organizationId}/requests/{requestId}/activity`

Authenticated, read-only, no CSRF header required. Fresh active membership and
shared request-detail visibility checks apply on every read; there is no Redis
cache or permission shortcut. Foreign organization/inactive membership: 404 ORGANIZATION_NOT_FOUND.
Hidden or missing request within an authorized organization: 404. This minimal
lifecycle timeline is not the full AUDIT_VIEW-gated organization audit browser.

Query: `limit` defaults 20, range 1–100; `offset` defaults 0, range 0–10,000.
Invalid bounds/types return sanitized validation errors. HTTP 200 response:

```json
{
  "activity": {
    "items": [
      {
        "eventId": "93aa7fda-57a0-42ec-82b0-452f65c700e6",
        "requestVersion": 0,
        "actorMembershipId": "265cb4be-0f18-4196-ae9b-785bd22f5422",
        "stepId": null,
        "type": "REQUEST_SUBMITTED",
        "state": "IN_REVIEW",
        "occurredAt": "2026-10-01T12:00:00Z",
        "projectedAt": "2026-10-01T12:00:01Z"
      }
    ],
    "limit": 20,
    "offset": 0,
    "hasMore": false
  },
  "eventuallyConsistent": true
}
```

Items are ordered by unique request version descending, not arrival time. A page
can be empty after a successful submit while the worker catches up. Use request
GET for the authoritative current state. No email, raw audit JSON, request details,
exception text or broker payload is exposed. Existing error/request-ID conventions
apply. Offset is acceptable for bounded per-request history; concurrent new events
can shift pages. This is not a stable historical snapshot or unbounded export.
No history is fabricated for commands predating V9.

See [event delivery contract](../async/event-delivery.md).
