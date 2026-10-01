# Database design

Milestone 2: PostgreSQL relational schema and Flyway migrations. No application
API, authentication implementation, RBAC enforcement, or workflow engine exists yet.

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
it does not prove secure hashing. Authentication will supply a real encoder at M3.

## Explicitly deferred tables

Refresh sessions/tokens (M3), submission idempotency (M5), outbox/inbox deduplication
(M8), notifications/preferences/delivery attempts (M9), workflow dependency graphs
and quorum reviewers (when advanced flows are approved). No generic projects/tasks
are included because GateFlow is a request/approval product, not a task tracker.
