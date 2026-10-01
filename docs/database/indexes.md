# Index and constraint strategy

These indexes follow anticipated core query shapes; no latency improvement is
claimed before application workloads and EXPLAIN measurements exist. PostgreSQL
creates PK/UNIQUE indexes but does not automatically index referencing FK columns.

## Uniqueness

- Users: `lower(email)` prevents case-only duplicate identities.
- Organizations: normalized slug.
- Memberships: organization/user; roles: organization/code.
- Workflow definitions: organization/case-insensitive name.
- Workflow versions: definition/version number.
- Step definitions: version/position.
- Step instances: request/definition step.
- Approval decisions: one decision per sequential step.
- Membership-role and role-permission pairs: composite PKs prevent repeated grants.

Additional tenant/ID UNIQUE indexes exist specifically as targets of tenant-safe
FKs. They add write/storage cost beyond UUID PKs; correctness justifies that cost.
Some composite FK paths reuse the leading columns of these indexes.

## Explicit indexes and intended queries

| Index | Shape it supports |
| --- | --- |
| ix_memberships_user | Find organizations for authenticated user |
| ix_membership_roles_role | List role members and FK checks on role changes |
| ix_workflow_steps_role | Find steps using an approver role |
| ix_requests_org_state_created | Tenant/status inbox ordered by created_at/id |
| ix_requests_requester_created | Tenant/requester history with stable ordering |
| ix_requests_workflow_version | Version-specific history and parent FK checks |
| ix_request_steps_definition | Definition usage and FK checks |
| ix_request_steps_active_inbox | Assigned active approvals; partial index limits size |
| ix_approval_decisions_request | Ordered decision history for one tenant/request |
| ix_audit_logs_org_time | Tenant audit timeline |
| ix_audit_logs_resource | Tenant/resource audit history |

Equality columns precede ordering columns. A partial index requires a query
predicate implying `state = 'ACTIVE'`; arbitrary prepared query shapes may not use
it. Inspect actual execution plans instead of assuming index selection.

## Authentication additions (M3)

`auth_sessions.token_hash` has a UNIQUE index for exact credential lookup.
`ix_auth_sessions_active_user` supports active-user session inspection;
`ix_auth_sessions_expiry` supports expiration cleanup. Rate bucket key hash is
its PK; `ix_auth_rate_limit_window` supports stale-bucket cleanup. Account login
uses `lower(email)` explicitly so its query matches the existing unique expression
index. No measured performance improvement is claimed.

## Future list query and pagination

```sql
SELECT id, title, state, created_at
FROM requests
WHERE organization_id = :verified_org AND state = :state
  AND (created_at, id) < (:cursor_time, :cursor_id)
ORDER BY created_at DESC, id DESC
LIMIT :bounded_page_size;
```

The UUID tie-breaker makes ordering deterministic for equal timestamps. Indexes
are not authorization checks. Offset is acceptable for small admin lists; cursor
pagination is preferable for deep histories and changing inboxes. A status-free
organization timeline may need a different index once its query is implemented.

## Deferred optimization

Full-text/GIN search indexes, archival partitioning, read replicas, and extensive
reporting indexes are deferred to relevant measured workloads. No EXPLAIN or
N+1-performance claim is made from an empty database.

M4 adds `ix_organizations_creator` for creator quota counting. Existing tenant
role/member indexes support bounded directory queries; no benchmarked query tuning
is claimed. Role lists page before joining grants; membership roles are batch-loaded.
