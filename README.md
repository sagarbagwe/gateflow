# GateFlow

Configurable approval workflows with reliable execution and traceable decisions.

> **Status: Milestone 2 — database schema and migrations.** No application,
> authentication, REST endpoints, or frontend has been implemented. PostgreSQL
> runs locally; a one-shot Flyway tools profile applies and validates the schema.

## Problem

Purchase, software-access, and policy-exception approvals often disappear into
email and chat. Organizations need enforceable review rules, clear ownership,
and a reliable record of decisions.

## Solution

GateFlow will bind each submitted request to a published workflow version,
activate eligible review steps, enforce authorized state transitions, and record
the outcome. It approves requests; it does not execute payments or provision access.

## Features

**Implemented:** repository scaffold, architecture/decision documentation,
PostgreSQL Compose configuration, tenant-safe fourteen-table core schema, three
Flyway migrations, database integrity/replay/checksum tests, ER diagram, and Git conventions.

**Planned:** tenant isolation, authentication, RBAC, versioned sequential approvals,
reviewer inbox, search, notification preferences, durable background delivery,
audit records, and concurrency-safe transitions. Parallel approvals and SLA
escalation follow a working sequential workflow. See [milestones](docs/milestones.md).

## Architecture

Start with a modular monolith. Domain modules own their rules; controllers adapt
HTTP requests; persistence and provider adapters remain outside domain logic.
A separate worker deployment from the same codebase is a later scaling option.

```mermaid
flowchart LR
    Browser[React UI - planned] --> API[Spring Boot modular monolith - planned]
    API --> DB[(PostgreSQL)]
    API -. later cache .-> Redis[(Redis)]
    DB -. later outbox publisher .-> Queue[RabbitMQ - later]
    Queue -.-> Worker[Worker - later]
    Worker -.-> Mail[Email provider - later]
```

See [system design](docs/architecture/system-design.md).

## Tech Stack

| Technology | Purpose | Current status |
| --- | --- | --- |
| Java 21, Spring Boot, Maven | Backend runtime and build | Planned; no application yet |
| Spring Security | Authentication and authorization | Planned |
| JPA/Hibernate, PostgreSQL 17 | Relational persistence | Core schema with verified migrations |
| Flyway OSS 13.8.1 | Explicit schema migrations | Implemented via digest-pinned tools container |
| Redis | Measured cache use and shared rate limits | Milestone 7 |
| RabbitMQ | Durable background jobs | Milestone 8 |
| React, TypeScript, Vite | Authenticated application UI | Milestone 14 |
| JUnit, Mockito, Testcontainers | Unit and integration verification | Added with relevant features |
| Docker Compose, GitHub Actions | Local dependencies and CI | Dependency Compose now; CI later |

Exact application dependency versions will be selected and pinned when the build
is introduced. No release compatibility or vulnerability claim is made yet.

## System Design

[Components, flows, capacity assumptions, reliability, scaling](docs/architecture/system-design.md).
[Decisions](docs/decisions/README.md) explain alternatives and trade-offs.

## Database Design

[Schema and entity relationships](docs/database/schema.md),
[ER diagram](docs/database/er-diagram.md), [indexes](docs/database/indexes.md),
[transaction boundaries](docs/database/transactions.md), and
[Flyway operations](docs/database/migrations.md). Production will not use automatic
ORM schema creation. No application workflow behavior is implemented yet.

## API Documentation

No API exists yet. Planned commands include submit, approve, reject, and withdraw,
not merely CRUD. Error handling, validation, request IDs, and authorization will
arrive with the first endpoints; full OpenAPI review is Milestone 12.

## Local Development

Prerequisites for this milestone: Git, Docker Engine/Desktop with Compose v2,
and Python 3 for the scaffold/test helpers. No Java build is needed yet.

```sh
cp .env.example .env
# Edit .env: replace the local PostgreSQL password placeholder.
bash scripts/check.sh
bash scripts/dev-db.sh up
bash scripts/dev-db.sh status
bash scripts/migrate-db.sh migrate
bash scripts/migrate-db.sh validate
bash scripts/dev-db.sh logs
```

The start command rejects the unchanged password placeholder. Alternatively,
after configuring `.env`, run `docker compose up -d --wait` directly. At this
milestone it starts **only PostgreSQL**, not GateFlow.

See [development instructions](docs/development.md) for stopping, resets, and
troubleshooting. Never reuse local credentials in production.

## Testing

`bash scripts/check.sh` verifies the scaffold and shell syntax and, when Docker is
available, validates Compose without printing its resolved secrets. It does not
exercise business logic or database connectivity.

Run `bash scripts/test-db.sh` for real database integration verification: fresh
migrations, validation, repeat-migrate no-op, SQL integrity assertions, checksum
rejection, failed-migration rollback/revalidation, and automatic disposable-database
cleanup. Application/security and
multi-connection concurrency tests will accompany their features; Milestone 13
expands them.

Real PostgreSQL runtime verification has also passed in the agent's Linux sandbox:
healthy startup, SQL smoke query, host TCP password authentication, wrong-password
rejection, and persistence across container recreation. No user-side setup was
needed. Actual results and limitations:
[Milestone 1 verification](docs/verification/milestone-1.md) and
[Milestone 2 verification](docs/verification/milestone-2.md).

## Docker

Local PostgreSQL uses persistent storage, a readiness health check, and a
loopback-only host port. PostgreSQL and Flyway are pinned to tested image digests;
updates require explicit review and migration tests. Flyway is a one-shot tools
profile, not a long-running application. Application Dockerfiles and a full local
stack are intentionally deferred. Production image scanning is still future work.

## CI/CD

No workflow is active yet. Planned pipeline: build, lint, unit tests, integration
tests, container build, and security checks. Production deployment needs an
explicit rollout/rollback plan and approval; it is not enabled automatically.

## Performance

No benchmarks have been run. Capacity figures in system design are planning
assumptions, not measured throughput. Milestone 19 records before/after results.

## Security

Local `.env` files are ignored by Git. Tenant isolation, secure authentication,
resource-specific authorization, bounded input, and sensitive-data redaction are
required design invariants. This scaffold is **not deployment-ready**.

## Trade-offs

A modular monolith favors understandable transactions and low operational cost
over premature service boundaries. PostgreSQL search comes before a separate
search cluster. RabbitMQ serves background delivery; Kafka is not required for
our initial workload. See [architecture decisions](docs/decisions/README.md).

## Future Improvements

Parallel approval quorums, reminder/escalation policies, delegation, provider
adapters, and independent worker scaling—only after the core is verified.

## Screenshots

Not available: the UI is not implemented.

## Demo

No hosted demo exists. Current local setup runs the database dependency only.

## Engineering Challenges

Versioned workflows; tenant-safe queries; approve/withdraw races; duplicate
submission; database-to-queue consistency; idempotent workers; and avoiding
stale authorization caches. These are planned work, not completed capabilities.

## Contributing and License

Follow [contributing guidance](CONTRIBUTING.md). Licensed under [MIT](LICENSE).
