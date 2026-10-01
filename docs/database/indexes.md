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

## Implemented M6 list query and pagination

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
pagination is preferable for deep histories and changing inboxes. M6 adds `ix_requests_org_created` for the status-free timeline.

## Deferred optimization

Archival partitioning, read replicas, and extensive reporting indexes remain
deferred. M6 introduces actual search indexes and seeded plan checks below.

M4 adds `ix_organizations_creator` for creator quota counting. Existing tenant
role/member indexes support bounded directory queries; no benchmarked query tuning
is claimed. Role lists page before joining grants; membership roles are batch-loaded.

M5 adds unique partial `ux_request_steps_one_active` on request_id (ACTIVE only)
and `ix_command_receipts_request` for tenant/request ledger lookups. The receipt PK
supports organization/actor/key lookup. Reviewer queries use existing tenant role
assignment indexes and active account/membership predicates; deterministic order
can require sorting. No measured EXPLAIN/load optimization claim is made yet.

## M6 search indexes and evidence

- `ix_requests_search_document`: GIN on generated tsvector; literal token search.
- `ix_requests_org_created`: organization then immutable `(created_at DESC,id DESC)`;
  supports both forward/backward B-tree scans and tuple cursor bounds.
- `ix_decisions_org_reviewer_request`: reviewer-history EXISTS lookup; no duplicated
  list rows for multiple decisions.
- Existing requester/status indexes serve OWN and selective status queries; existing
  partial active-inbox and membership-role indexes support reviewer eligibility.
  PostgreSQL may choose a different plan as distributions change.

RequestSearchRepository builds one summary query and active-step join; there are
no per-result step/detail queries. Authorization resolution has its own bounded
queries, so “one query” refers to result hydration, not the entire HTTP request.
No query is forced to use an index and result bounds do not bound all scanned rows.
FTS can require a bitmap heap scan and sort; highly common terms can be expensive.
Do not add every possible filter combination as another index.

Seeded evidence: [Milestone 6 verification](../verification/milestone-6.md).
The GIN pending list initially made a tenant-index plan cheaper after the bulk
fixture insertion. Normal VACUUM (ANALYZE) maintenance produced the expected GIN
rare-term plan; tests preserve that setup. Monitor vacuum/GIN maintenance rather
than disabling planner options or promising the same plan for every term.

## M8 asynchronous indexes

`ix_outbox_pending(available_at,occurred_at,id) WHERE published_at IS NULL` limits
relay candidates to unfinished work. Claim SQL orders by occurred_at/id and may
sort eligible candidates; lease predicates/locked rows can still require scanning.
Do not claim index-only constant-time draining or measured throughput.
`UNIQUE(organization_id,request_id,request_version)` rejects duplicate source events
and supports descending per-request activity pages on request_activity.
`processed_events(consumer_name,event_id)` serializes competing duplicate effects.
Source PK and `(organization_id,id)` support authoritative reference and tenant FK
lookups. `ux_request_steps_event_scope` supports same-request step provenance.
Watch index/storage growth; retention is not implemented in M8.

## M9 notification indexes

Own in-app feed: partial `(organization_id,recipient_membership_id,created_at DESC,
id DESC) WHERE in_app`, plus unread partial `(organization_id,recipient_membership_id,
request_id) WHERE in_app AND read_at IS NULL`. Resource visibility still needs joins
and permission predicates; no constant-time count is claimed. Unique tenant/event/
recipient and unique email notification_id enforce generation deduplication.
`ix_email_due(available_at,id)` restricts pending/retry jobs; `ix_email_expired_lease`
restricts processing lease recovery. Worker claims may scan locked candidates.
Append-only attempt PK supports delivery history. Retention/index-growth operations
are still required before production.
