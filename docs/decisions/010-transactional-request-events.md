# ADR 010: Transactional request events and idempotent activity projection

Status: implemented in Milestone 8.

## Context

Approval commits must not depend on broker availability. An initial activity worker
provides useful lifecycle history and verifies delivery before email integration.
Command receipts already guarantee idempotent commands, not message delivery.

## Decision

Append an immutable, versioned outbox event in the existing command transaction.
Use short SKIP LOCKED leases, mandatory publisher confirms and persistent quorum
queues. Broker body contains only an event reference; PostgreSQL supplies business
facts. One consumer transaction atomically commits a unique receipt and projection
before ACK. Confirm retry/DLQ handoffs before ACKing originals. Use activity-specific
retry routing to prevent future domain subscribers seeing retry duplicates.

## Alternatives and consequences

PostgreSQL-only workers reduce operating cost; RabbitMQ adds explicit delivery and
routing behavior we can test. Kafka/CDC/microservices are not needed. A DB/broker
transaction or best-effort post-commit publish does not close the crash window.
At-least-once delivery requires deduplication; there is no exactly-once external
side-effect claim. Out-of-order delivery is safe for append-only activity, not
implicitly safe for every future subscriber. A one-node broker is not HA. Source
retention, queue migration, least-privilege runtime identities and monitoring remain
operational responsibilities, not hidden completed milestones.

[Configuration, failures and runbook](../async/event-delivery.md).
