# Concurrency and consistency contract

## Why these are real business races
Two approvals can otherwise create duplicate decisions and notifications. Approval
racing withdrawal can claim incompatible terminal outcomes. Reviewer reassignment
racing an old reviewer's decision can approve under stale ownership. A client retry
can create two requests. Concurrent policy edit/publication can mix review rules.
Simultaneous preference updates can overwrite another user's intended configuration.
These are correctness problems, not merely a lost HTTP response.

## Implemented solutions

| Operation | Authority and guard | Result |
| --- | --- | --- |
| Decision/withdraw/reassign | PostgreSQL request FOR UPDATE, recheck state/expectedVersion after waiting | One mutation for a version; other gets 409 and no business side effects |
| Idempotent command | Tenant/member/key transaction advisory lock plus unique receipt/payload digest | Same operation/body replays; changed body/operation gets 409 IDEMPOTENCY_CONFLICT |
| Draft edit/publish | Definition then version row locks, expectedVersion, immutable published policy | One coherent policy; stale editor/publisher gets 409 |
| Notification preferences | Conditional UPDATE WHERE row_version=expectedVersion | One winner; loser 409; atomic audit only for winner |
| Permission revocation | Organization FOR SHARE for business writes, FOR UPDATE for RBAC; reload grants after lock | Waiting command cannot use a revoked cached grant |
| Projection duplicates | Unique consumer/event receipt and unique event/recipient/kind, one transaction | No duplicate committed inbox/email rows |
| Background claims | SKIP LOCKED, expiring leases, token-fenced finish | Disjoint work; old token cannot overwrite new claim |

Most guards were implemented when their features first required them (M4/M5/M8/M9).
Milestone 11 adds controlled, observable server-side races and documents the combined
contract. No duplicate Redis locking mechanism, new broker or artificial microservice.

## Lock order, boundaries and failures
Organization share/reload -> command advisory lock -> request aggregate; policy
writes use organization -> definition -> version. RBAC has the organization exclusive
lock. Preference compare-and-set holds only its relevant row. Transactions commit
state/decision, audit, receipt and outbox together; loser/validation/audit failure rolls
back all of them. Do not perform provider/network I/O inside these transactions.

ExpectedVersion protects client intent; a row lock alone would serialize two stale
approvals but would not make the second intent valid. A database unique constraint
remains the final integrity guard, not a Java in-memory lock. PostgreSQL transaction
locks work across application replicas and release on rollback/disconnection. Advisory
keys are hash-derived; collisions can serialize unrelated commands, not authorize
cross-tenant access or bypass row checks. Process-local synchronized cannot protect
multiple pods; a Redis lease would add expiry/split-brain failure modes without replacing
the database authority.

A hot request necessarily serializes its own decisions. Unrelated aggregates proceed
under compatible organization share locks. Long transactions/large connection pools
can amplify waits; measure lock-wait and pool saturation, keep commands short. Separate
API/worker pools, query/lock timeouts and overload response policy are later operational
work, not claimed as implemented. There is no automatic retry of conflicting business
intent: reload the resource, explain the change and obtain a new user decision. For a
transport failure retry the same idempotency key/body; receipt replay returns the
current request view and does not rerun the original effects (not a byte-identical
historical response). Receipt retention is not yet a purge policy.

## Deterministic verification
`ConcurrencyIntegrationTest` starts two independent authenticated HTTP clients against
real PostgreSQL. A test-only transaction first locks the target row/advisory key.
The harness polls pg_stat_activity until **both server transactions are waiting on
that specific query class** (same isolated database), then releases the barrier.
Fail if both waiters are not observed within 10 seconds; no arbitrary sleep to guess
an overlap. Winner ordering is intentionally not fixed. HTTP/future timeouts are
bounded and finally releases the barrier before shutting down workers.

Seven cases: different-key duplicate approval; approval versus withdrawal; old-reviewer
approval versus reassignment; identical simultaneous submit key; conflicting payload
with same submit key; draft edit versus publish; preference compare-and-set. Tests
inspect final version/state/assignment/policy and count committed decision, receipt,
audit and outbox rows. Winning request correlation matches its sole mutation audit;
loser error is sanitized. Fixtures are disposable and never create production accounts.

These are controlled race cases, not exhaustive interleaving proof, a deadlock-freedom
claim or a throughput benchmark. Repeated fresh-container runs reduce regression risk
but do not establish a production capacity number. Existing event-worker/email race
and RBAC-revocation tests remain part of full regression.

## Schema rollout caution
V11 investigation indexes use normal CREATE INDEX inside the migration transaction.
On a large audit table this can wait for/block writes; the verified small-fixture
upgrade is not a zero-downtime production proof. Review maintenance timing, backups,
and a separately controlled concurrent-index rollout if production volume requires
it. Do not rewrite an already applied migration or silently run Flyway repair.
