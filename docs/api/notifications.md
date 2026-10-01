# Notification API

All routes below are relative to `/api/v1/organizations/{orgId}/notifications`.
Authentication and active tenant membership are required. Unknown/inactive/foreign
organizations return 404 ORGANIZATION_NOT_FOUND; recipient ownership and current
request visibility are checked before returning or acknowledging a notification.
No admin override to browse another user's inbox.

| Method / route | Behavior |
| --- | --- |
| GET / | Own visible in-app notifications, newest created_at/UUID first |
| GET /unread-count | `{ "unreadCount": 3 }` for own visible unread rows |
| PATCH /{id}/read | First read acknowledgment; repeat returns the same readAt |
| GET /preferences | Own per-organization channel defaults/persisted preferences |
| PUT /preferences | Full channel replacement with expectedVersion and CSRF |

List query: unreadOnly=false, limit=20 (1–100), offset=0 (0–10,000). Standard
PageSlice: items/limit/offset/hasMore; no unbounded result or exact list-total scan.
Items contain id, requestId, eventId, kind, eventType, eventState, createdAt, readAt,
actionable. eventState is historical, not current request state. actionable is a
fresh eligibility check; the server still reauthorizes a later approve command.
An existing historical card can become non-actionable. No title/email/raw provider
failure/body/audit data is returned. Inbox can lag command commit because generation
is asynchronous. Offset pagination can shift when new notifications arrive.

PATCH /{id}/read has no body and requires the current CSRF header/cookie. Missing,
foreign, email-only or currently hidden IDs return 404 NOTIFICATION_NOT_FOUND.
Read state cannot be reset through this API.

Preference input example:

```json
{ "inAppEnabled": true, "emailEnabled": false, "expectedVersion": 0 }
```

GET defaults: `{ "inAppEnabled": true, "emailEnabled": false, "version": 0 }`.
First successful PUT returns version 1. All three input fields are required;
negative/missing versions and missing/null channels fail validation. Concurrent
updates at the same expectedVersion have one winner; stale writes return 409
VERSION_CONFLICT. Successful changes atomically audit booleans/version with the
request ID; database/audit failure returns sanitized 503 SERVICE_UNAVAILABLE and
rolls back preferences. Channel opt-out behavior is documented in the delivery
contract. This is self-service; no recipient/member ID is accepted in the body.

Authentication 401, CSRF 403, validation 400 and sanitized ProblemDetail/request-ID
conventions remain unchanged. Email transport is opt-in globally and per user;
API opt-in alone does not configure an external provider or verify address ownership.

[Delivery contract and recovery](../notifications/delivery.md).
