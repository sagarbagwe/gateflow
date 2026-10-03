# Interview questions and honest answers

## Why these technologies?

Java/Spring Boot: typed domain services, DI, security and mature testing.
PostgreSQL: constraints, transactions, tenant-aware relational queries and built-in full-text search.
Redis: reconstructible read-cache with explicit outage behavior, not mandatory business authority.
RabbitMQ: background work/retry/routing requirements; no event-stream replay requirement demanding Kafka.
React/TypeScript/Vite: direct SPA needs; no SEO/server-rendering requirement demanding Next.js.
Docker/CI: reproducible services/builds and gates, not a promise of production availability.

## Failure scenarios

- Lost submit response: retain key/body, retry the same intent; do not blindly create another.
- Same request updated concurrently: row lock/version checks reject stale or invalid transitions.
- Reviewer permission revoked: reauthorize, reject stale eligibility; never trust cached UI grants.
- Redis down: current authorization remains required; policy reads fall back to PostgreSQL.
- Broker down: outbox stays durable; monitor backlog age and recover with confirms/retries.
- Duplicate event: consumer receipt/constraints suppress duplicate projection effects.
- SMTP accepted, finish commit failed: quarantine uncertainty; do not promise exactly-once mail.
- Logout failed: retain account UI with an error/retry; do not imply server revocation succeeded.
- Old workspace response arrives: suppress it using an invalidated async scope.
- Recovery/restore: targets are not guarantees until rehearsed and measured.

## What changes at 10x traffic?

Measure first: latency distribution, DB plans/IO, pool waits, lock contention, outbox
age, consumer throughput and cache behavior. Bound lists, use appropriate indexes/cursor
navigation, add API/worker replicas with connection budgets, and size DB/broker availability.
Partition/archive durable history only with a retention/recovery design. Do not immediately
split every module into a microservice or replace PostgreSQL with a search cluster.

## Hardest problem / what would you change?

A strong topic is correctness when retries, concurrent commands, authorization drift and
asynchronous side effects overlap. Walk through the actual transaction and failure evidence.
Improvements: verified onboarding, easier policy-version selection, representative workload
measurements, independently controlled audit retention, and tested operations. Do not claim
these were implemented just because the architecture describes them.

## Contribution and authorship

The implementation was AI-assisted under the user's product direction. Do not present
agent-written code as exclusively handwritten personal work. State the scope you actually
reviewed, understood, tested or changed. Before an interview, trace an approval from UI to
DB/outbox/consumer, explain a failing concurrency/security test, and make a small verified
change yourself. Replace aspirational contribution statements with genuine experience.
