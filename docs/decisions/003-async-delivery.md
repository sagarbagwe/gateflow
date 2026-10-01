# ADR 003: Defer infrastructure; later use RabbitMQ with an outbox

Status: accepted direction; not implemented until Milestone 8.

## Context

Notifications should not block approval commits. A database commit and broker
publish cannot be assumed atomic. Delivery may occur more than once.

## Decision

Do not add a broker to Milestone 1. At Milestone 8 introduce a transactional outbox,
RabbitMQ publisher confirms, idempotent consumers, bounded retries, and dead-letter
handling. Redis is separately deferred to Milestone 7.

## Alternatives

Synchronous email ties approval availability to the provider. Best-effort publish
after commit can lose events. Kafka is useful for retained event streams and
multiple independent replay consumers, but that is not our initial need. A
PostgreSQL-only work queue is a reasonable simpler alternative; RabbitMQ is chosen
for explicit delivery/acknowledgment and worker-routing experience, with its extra
operational cost acknowledged. AWS-managed queue options are evaluated at M18.

## Consequences

Broker operations, message schemas, deduplication, and replay tooling become our
responsibility. Exactly-once external email is not promised. Prefer provider
idempotency support and document ambiguous send outcomes.

## Revisit when

Hosting costs favor a managed queue, or multiple independent event consumers need
retained replay. Do not run multiple brokers just to demonstrate technology names.
