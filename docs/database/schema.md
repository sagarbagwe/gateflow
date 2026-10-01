# Database design

Current schema: fourteen core tables, two authentication tables and one command
receipt ledger plus three asynchronous and four notification tables (24 application tables, excluding Flyway history). Authentication,
RBAC and sequential workflow APIs exist. V1–V5 are unchanged; V6 adds core-command
metadata, guards and one product permission.

## Modeling choices

Shared database/schema, tenant discriminator `organization_id`, UUID primary keys,
`timestamptz` timestamps (instants), and text states with CHECK constraints. UUIDs
avoid sequential identifier enumeration but are not an authorization boundary.
Random UUIDs have index-locality costs; measure before changing identifier strategy.
Money uses `numeric(14,2)` plus an uppercase three-letter currency. This initial
purchase model supports two-decimal currencies only; it does not perform FX,
validate the ISO currency catalog, or execute payments.

## Entities and relationships

| Entity | Purpose and important relationship |
| --- | --- |
| users | Global identity; case-insensitive unique email; password hash only |
| organizations | Tenant; unique normalized slug |
| memberships | One user's membership in one organization; suspend instead of deleting history |
| roles | Organization-owned role vocabulary, unique code per organization |
| permissions | Global permission catalog, not users' effective grants |
| membership_roles | Many-to-many membership/role assignment, both in the same organization |
| role_permissions | Many-to-many organization role/permission grant |
| workflow_definitions | Stable named workflow identity; archive rather than deleting history |
| workflow_versions | Numbered draft/published configuration; immutable after publishing |
| workflow_steps | Ordered single-reviewer step definitions belonging to one version |
| requests | Request content, requester, definition, pinned version, state and optimistic version |
| request_steps | Execution instances of steps from exactly the request's pinned version |
| approval_decisions | One immutable decision per sequential step by its assigned reviewer |
| audit_logs | Append-only action evidence, actor, resource, old/new redacted snapshots, correlation ID |
| auth_sessions | Global user sessions; unique SHA-256 token hash, absolute expiry, revocation |
| auth_rate_limit_buckets | Shared fixed-window auth counters keyed by pseudonymous scope hash |

See [ER diagram](er-diagram.md), [index strategy](indexes.md),
[transaction boundaries](transactions.md), and [migration operations](migrations.md).

## Keys, tenant integrity, and nulls

UUID `id` is the global primary key for entity tables; permission code is its
catalog PK. Join tables use composite PKs. Tenant-owned parents expose explicit
composite UNIQUE keys so child foreign keys include `organization_id`.

A request cannot reference another organization's requester, definition, or
version. A role assignment cannot combine one tenant's membership and another
tenant's role. A step instance's composite FKs require both its request and its
step definition to use the same workflow version. Decisions reference the exact
step/request/assigned-reviewer tuple, preventing an unrelated reviewer identity
from being substituted at the storage layer.

Draft requests may have a null workflow version. PostgreSQL's MATCH SIMPLE skips
a composite FK containing null; CHECK constraints therefore require non-null
version and submission time outside DRAFT. Step instances require non-null version.
Active steps require an assignee and activation time. Terminal state fields must
have completion timestamps. Timestamp constraints are integrity checks, not a
complete business state machine.

FKs deliberately do not cascade-delete business history. Account suspension and
workflow archival precede a separate retention/anonymization policy. The actor
references survive normal deactivation. Historical display names may change;
audit snapshots must preserve necessary actor context without storing secrets.
Audit `resource_type`/`resource_id` is a polymorphic reference, not a foreign key;
the application must validate resource/tenant ownership and allowed snapshot fields.
The database cannot infer those rules from an arbitrary action/resource label.

## Versioning and sequential scope

A draft version can be edited. Publishing requires at least one step and contiguous
positions 1..N. Published versions and their steps reject updates/deletes. Step
edits lock the parent version row to serialize with publication. Steps cannot move
between versions. Requests bind a published version at submission; policy,
requester, submission time, and payload cannot be rewritten afterward.

The first engine is sequential, single-reviewer per step. Approval decisions have
a unique step ID. Parallel reviewers, quorums, delegation, and dependency graphs
need explicit future migrations rather than overloading this constraint.

`row_version` supports future optimistic compare-and-set updates. PostgreSQL does
not increment it automatically: the application must update it atomically. The
schema alone does not implement transition validation, eligibility, or idempotency.

## Structured payloads

`request_data` and step `conditions` must be JSON objects; application validation
will enforce a small typed vocabulary, size limits, required fields, and allowed
operators in Milestone 5. No arbitrary code execution or general expression
language is supported. JSON shape checks are not semantic policy validation.

## Security boundaries and audit integrity

Cross-tenant **references** are prevented by composite FKs. Cross-tenant **reads**
are not: every future repository/API query must carry verified organization scope
and enforce resource authorization. RLS is not enabled in this milestone; adding
it requires transaction-local tenant context and connection-pool isolation tests.

Audit records and approval decisions reject UPDATE, DELETE, and TRUNCATE through
triggers. This protects normal SQL flows, not a malicious schema owner/superuser
who can disable triggers or drop tables. Production must separate migration owner
from runtime role, grant runtime only necessary DML, deny DDL/TRUNCATE, and limit
audit/decision tables to SELECT/INSERT. The local Compose user remains a development
superuser; do not use it as the production application identity.

No bootstrap accounts, plaintext passwords, organization grants, or fake audit
history are seeded. The password-hash length constraint is only a storage guard;
it does not prove secure hashing. M3 AuthService supplies BCrypt cost 12 before
persistence; there is no API accepting a client-provided password hash.

## Explicitly deferred tables

JWT/refresh-token storage is deliberately not used: M3 implements opaque sessions.
Submission command receipts (M5) and outbox/consumer deduplication (M8) are implemented.
M9 now adds notifications/preferences/email deliveries/attempts. Still deferred: workflow dependency graphs
and quorum reviewers (when advanced flows are approved). No generic projects/tasks
are included because GateFlow is a request/approval product, not a task tracker.

## V5 RBAC metadata

- `organizations.created_by_user_id`: nullable users FK for creator quota/accountability;
  indexed. Nullable preserves existing rows without guessing creators.
- `roles.is_system`: false by default; true only for bootstrap defaults through the
  API. Check limits true values to ADMIN/MANAGER/MEMBER/VIEWER codes; existing roles
  retain false even if named ADMIN. It is not a database-owner security boundary.
- `roles.row_version`, `memberships.row_version`: nonnegative bigint default zero;
  conditional updates reject stale administrative edits.
- No V1–V4 source/checksum edits, destructive rebuild, or new tables in V5.
- Existing composite FKs keep role assignments tenant-safe; existing org/user and
  org/code unique constraints reject duplicate membership and role creation.

## V6 workflow command invariants

- `workflow_versions.row_version`: nonnegative bigint, default 0; draft/publication
  concurrency token, separate from version_number.
- `command_receipts`: composite PK (organization_id, actor_membership_id,
  idempotency_key); operation enum constraint; SHA-256 hex fingerprint; same-tenant
  actor/request FKs; created_at. Append-only including TRUNCATE guards.
- `ux_request_steps_one_active`: unique request_id where state=ACTIVE. At most one
  active step; application guarantees one while IN_REVIEW when submitting/advancing.
- Request state transitions and execution-step provenance/terminal guards supplement
  M2 immutability. External owner writes are not a complete application RBAC engine.
- New REQUEST_REASSIGN catalog entry; explicit backfill only for protected system
  ADMIN roles. Custom/legacy name matches never gain it automatically.
- No request DRAFT API: M5 binds direct submissions into IN_REVIEW. DRAFT remains a
  valid stored state for compatibility; direct submission is one atomic operation.
- There is no automatic receipt expiry/retention job or provider-delivery ledger.

## M6 search representation

`requests.search_document` is a stored generated PostgreSQL tsvector of coalesced
`title || ' ' || description`, using explicit simple regconfig. It is not a client
field or a new entity. Creation coordinates are immutable even for legacy drafts.
Read summaries expose created_at with UUID tie-breaker; source payload and decision
history still live in their original tables. See [search API](../api/search.md).

## M8 durable asynchronous storage (V9)

- outbox_events: immutable event ID/schema/tenant/request/version/actor/step/type/
  state/correlation/time; only pending relay lease/attempt/retry metadata can change.
  One event per tenant/request/version; published metadata is frozen. Composite
  tenant FKs scope request, actor and optional step provenance. Business command,
  audit, receipt and event commit or roll back together.
- processed_events: `(consumer_name,event_id)` primary key with source tenant FK;
  append-only per-consumer receipt, not an authentication or command receipt.
- request_activity: event ID PK, tenant/request/version unique index, actor/optional
  step/type/state/event-time/projection-time. The projector copies authoritative
  source fields via INSERT SELECT. Tenant FKs cover source event, request and actor;
  they do not independently enforce equality of every copied event field, so runtime
  insert privileges must remain restricted. No ordinary update/delete/truncate.

Nine migrations total; V1–V8 remain untouched. There is no historical event backfill.
[Delivery contract](../async/event-delivery.md) records retention and recovery limits.

## M9 notification storage (V10)

notification_preferences: tenant/membership composite PK/FK, default in-app true /
email false, nonnegative optimistic version and update timestamp. notifications:
UUID PK, source event/request/recipient/optional step tenant FKs, unique source
recipient, immutable kind/channel/provenance and first-only read_at. Email-only
rows retain provenance but do not appear in the in-app inbox.

notification_email_deliveries: unique notification job with recipient-matching
composite FK, finite states, attempt counter, due time, paired processing lease,
terminal timestamp, immutable provenance and frozen terminal state.
notification_email_attempts: delivery/attempt PK plus tenant FK, immutable lease
identity/outcome/reason/time. No recipient address/body/SMTP secret is persisted.
Projector inserts trusted source fields; FKs do not independently prove every
copied source value or delivery policy. Runtime insert privileges remain restricted
production work. No preference/inbox backfill or notification purge job is added.
