# ADR 007: Sequential approval aggregates and durable command receipts

Status: implemented in M5; bounded sequential business scope.

## Why

An approval platform must enforce policy/version binding and legal transitions,
not merely expose CRUD records. Concurrent retry, approve/withdraw, policy edits,
and role revocation must not produce duplicate or partially committed decisions.
Unavailable assignees need a recoverable, audited administrative path.

## How

Configuration: definition row -> draft version and ordered typed step conditions
-> explicit publication -> immutable policy snapshot. Definition row locks assign
new version ordinals; version locks and expected versions protect draft edits and
publication. No graph engine or expression runtime is needed for an ordered chain.

Execution: direct submission binds a published version, selects all applicable
reviewers, creates step instances, and activates only the first applicable step.
A unique partial index enforces at most one ACTIVE step. Request rows form the
aggregate lock boundary. Application policy checks + expected request version +
DB state/provenance guards reject illegal or stale transitions.

Authorization: core commands obtain FOR SHARE on the organization, then reload
grants. Shared locks allow unrelated request aggregates to run concurrently while
coordinating with M4's exclusive FOR UPDATE RBAC mutations. The organization lock
is acquired before command-key and aggregate/configuration locks. Read endpoints
use REPEATABLE READ snapshots for consistent multi-query projections; reads
already in flight may overlap revocation. New mutations never rely on stale grants
from before waiting on an RBAC change. Future global account disabling must use
compatible locking/recovery policy; this milestone does not implement that command.

Requests require current reviewer role + effective approval permission + pinned
assignment + non-requester identity, not just a permission enum. Reassignment is
separate REQUEST_REASSIGN plus REQUEST_VIEW_ALL, granted to protected ADMIN only
by default. V6 explicitly adds this code to existing protected admins; custom or
legacy ADMIN-named roles are not promoted. The catalog now contains 13 codes.

Idempotency: organization/actor/key scoped immutable ledger. A PostgreSQL
transaction advisory lock on a 64-bit hash of that scope serializes same-key
attempts; hash collisions only serialize unrelated commands, not merge receipts.
Canonical SHA-256 fingerprints cover kind/path/body. Store receipt after successful
local writes in the same transaction. On replay reauthorize first, verify intent,
then lock/read the same aggregate's current representation. No pending receipt,
separate coordination database, or external response snapshot is necessary.

## Alternatives and trade-offs

- Optimistic locking alone cannot stop different-key commands from inserting a
  decision before aggregate validation. A short row lock makes the sequential
  invariant explicit; versions separately reject stale client intent.
- A single exclusive organization lock would needlessly serialize all approvals.
  Shared locks retain RBAC correctness but briefly delay tenant administration
  while commands execute. Requests for the same aggregate still serialize.
- Redis leases introduce expiry/ownership/fencing complexity without benefit while
  PostgreSQL remains authoritative. Advisory transaction locks release on rollback,
  disconnect or commit and coordinate replicas using the same primary database.
- Frozen replay JSON gives stable bytes but stores duplicate sensitive data and
  hides current status. M5 deliberately returns current state and documents it.
- Automatic reassignment can surprise approvers and shift separation of duties.
  Deterministic initial assignment and explicit audited reassignment are simpler.
- Email/broker calls inside the transaction create distributed commit problems.
  None occur here. M8 will introduce a transactional outbox, not best-effort publish.

## Failure and scale

No eligible initial reviewer/no applicable step -> submission rollback. Unavailable
next reviewer -> decision rollback until restoration/reassignment. Audit or receipt
storage failure -> rollback all local effects; the unbound key may retry. Lost
response after commit -> receipt replay; changed body or operation -> conflict.
Terminal requests/decisions cannot be silently reopened. Idempotency receipts have
no TTL; safe retention/partition policy must preserve retry guarantees before cleanup.

Steps are capped at 50; detail maps and body bytes are bounded. Repeated configured
roles reuse a per-command selection map, not a cross-request authorization cache.
Role selection is deterministic, not fair/load-balanced. No throughput/SLA claim.
Hot request IDs serialize; independent IDs can progress on separate replicas.
Database pooling/lock waits, receiver retry backoff, query plans and load testing
remain deployment/performance considerations. Production ingress timeouts,
least-privilege DB roles, comprehensive auditing and dependency review remain open.

## Explicit non-goals

Parallel/quorum approvals, distinct-human constraints, save-draft request API,
policy expressions/scripts, provider execution/payments/provisioning, queues,
notifications, search, UI and public hosting. APPROVED records a business decision;
it does not claim a downstream operation completed.
