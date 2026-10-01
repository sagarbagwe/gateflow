# Milestone 11 — deterministic business-concurrency verification

## Delivered
Seven independent HTTP/PostgreSQL race cases observe both server transactions waiting
on a real database barrier before release. Assert valid final aggregate/configuration
and atomic durable effects, not just HTTP outcomes. Existing guards are retained:
row lock plus explicit version, transaction-scoped advisory idempotency serialization,
unique receipt and atomic state/audit/outbox, policy definition/version locks, and
preference compare-and-set. No extra distributed lock, application library, migration
or microservice was introduced to decorate the milestone.

[Consistency contract](../concurrency/consistency.md) explains why/how/alternatives,
lock ordering, failure cases and scaling limitations. [ADR 013](../decisions/013-database-authoritative-concurrency.md)
records the choice. Main implementation safeguards originally shipped with M4/M5/M8/M9;
M11 makes their combined contract independently and deterministically verifiable.

## Executed checks
Built/run entirely in the agent sandbox. M10 canonical GitHub commit before M11:
`cfa7668764d14898e032ceacf4e4e45422b9abb3`.

- Full `mvn -B -f backend/pom.xml verify`: **298 tests**, zero failures/errors/skips;
  actual executable JAR packaged. XML totals independently aggregated: **116 unit,
  170 real HTTP/PostgreSQL/Redis/RabbitMQ/Mailpit tests, seven standalone PostgreSQL
  upgrade/index cases and five SMTP adapter checks**. All 291 earlier distinct tests,
  including M10 populated V10→V11 upgrade, now pass together in the full suite.
- Corrected focused `-Dtest=ConcurrencyIntegrationTest test`: **7/7 passed** before
  full regression. An initial test-observer pattern assumed SELECT * for policy
  definitions, but production selects named columns; the barrier correctly failed
  rather than pretend a race was observed. Matched the real prepared SQL; reran all
  seven cases green. No production locking defect was found in these scenarios.
- **3** further runs of all seven cases, each with a fresh PostgreSQL container:
  **21 additional race executions**, zero failures/errors/skips. Full
  regression and focused execution are separate from these repeat counts.
- Actual final packaged JAR plus Compose PostgreSQL/Redis/RabbitMQ/local-only Mailpit:
  **100 API/database/SMTP assertions**, passed. Protected audit browsing,
  metadata/detail integrity, replay evidence, automatic relay and both subscribers,
  recipient inbox/preferences/email, broker outage/restart recovery. No external
  mailbox contacted. Temporary application/DB/vhost/fixture emails cleaned up.
- M10's **128 database integrity checks** remain applicable: M11 has no schema or
  production-code changes. Eleven Flyway migrations/24 application tables unchanged.
  V1–V11 byte-identical to the verified M10 source checkpoint.
- Java 21 formatter, static scaffold/relative-link checks, shell syntax, Compose
  configuration, Git whitespace and private-environment credential scan passed.
  Main development database remains empty of users/requests/audit fixtures.

## Seven scenarios and durable assertions
1. Different keys, same reviewer/version: one decision, mutation audit and outbox;
   one 200, one 409, aggregate version 1.
2. Approval vs withdrawal: one coherent terminal state, matching decision/step state;
   no incompatible second receipt/audit/event.
3. Approval vs reassignment: either old reviewer commits approval or ownership changes;
   stale old-reviewer command cannot also succeed.
4. Same submission key/body: 201 + replay 200, same request UUID; one aggregate,
   receipt, submission audit and outbox event.
5. Same submission key/different body: one 201, other 409 IDEMPOTENCY_CONFLICT;
   persisted winner payload and only one aggregate/receipt/audit/outbox.
6. Publish vs draft edit: one coherent status/version/step name/role and one audit;
   no mixed published policy.
7. Preference updates at same expectedVersion: one 200, other 409, final version 2
   and exactly one additional audit; no unrelated workflow event.

Request-mutation winner X-Request-ID equals the only committed mutation audit's
correlation ID; failed command error/request-ID is sanitized and correlated. The
barrier releases in finally before worker shutdown, with bounded observation/HTTP/
future deadlines. Either valid winner is accepted; not timing-dependent winner order.

## Scope limits and next gate
Controlled race tests do not prove every interleaving or deadlock freedom, simulate
multi-region failure, or measure load/throughput. PostgreSQL authority works across
replicas by design; these tests use one application instance with independent HTTP
clients, not an actual multi-pod deployment. Operational lock/statement timeout,
pool isolation and monitoring tuning remain documented later work.

Milestones 10 and 11 are complete. **Milestone 12 (API quality/OpenAPI) was not
started** and requires explicit approval. Frontend/cloud/CI are not claimed shipped.
