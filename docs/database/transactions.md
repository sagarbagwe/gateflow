# Transaction boundaries and invariants

Isolation starts with PostgreSQL READ COMMITTED and short, explicit transactions.
The schema enforces integrity; these future application operations are not yet built.

## Publish a workflow

Lock/update the draft version row; validate its typed conditions and approver
rules; validate contiguous step positions; publish and record audit in one
transaction. The database step-edit trigger locks the same parent row so published
configuration cannot be silently edited. Concurrent publication/edit behavior
needs multi-connection tests with the engine, not just sequential SQL assertions.

## Submit a request

Authenticate/authorize tenant membership. Load and validate the draft payload,
pick a published version, bind it, create step instances/assignments, activate the
first eligible step, set submission time/state, bump `row_version`, and insert
audit evidence in one transaction. At M5 add an organization-scoped idempotency
record and payload hash. At M8 add outbox rows in the same transaction. External
providers are never called inside it.

## Decide a step

Read an organization-scoped request and step; validate active state, membership,
role/permission, assignment, and self-approval policy. Perform a guarded update
using expected versions/state, insert the unique decision, advance step/request,
and record audit atomically. SQL uniqueness handles duplicate decisions; a
conflicting update must roll back the whole operation, not partially advance it.
The database prevents a different assigned reviewer ID from being substituted;
current role eligibility and deactivation are still application responsibilities.

## Withdraw

Verify ownership/permission and legal lifecycle state. Guard request version,
cancel relevant pending steps, complete the request, and record audit atomically.
Approve-versus-withdraw races require one transaction winner and an explicit
conflict response. The M2 schema does not implement this full state machine.

## Role or membership change

Use organization-scoped rows, validate who may grant/revoke privileges, update
join rows/status, and record audit atomically. Future session/caching invalidation
must not leave revoked rights authoritative in stale memory. Avoid deleting
memberships referenced by historical requests, decisions, or audit records.

## Audit and background work

Insert an audit row inside the business transaction so rollback does not leave a
false success record. Audit masking/allowed fields are M10 application behavior.
Later consumers atomically insert deduplication markers and local notification
records; email sends remain outside PostgreSQL transactions.

## Optimistic updates

```sql
UPDATE requests
SET state = :new_state, row_version = row_version + 1
WHERE organization_id = :verified_org AND id = :id
  AND row_version = :expected_version AND state = :expected_state;
```

Exactly one updated row means the guard succeeded, not that all authorization
checks are satisfied. Zero rows requires a scoped distinction between missing
resource and conflict. No blind retry of non-idempotent commands. The database
CHECKs enforce valid field combinations, but not every allowed state transition.

## Operational boundaries

Flyway applies each versioned PostgreSQL migration transactionally. Keep data
backfills bounded and review locks before production rollout. DDL/data mutation
permissions belong to a migration role; runtime should not own tables. A future
retention/anonymization process requires explicit policy and elevated tooling,
not ordinary DELETE APIs or CASCADE chains.
