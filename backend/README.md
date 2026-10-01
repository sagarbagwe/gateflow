# GateFlow backend

Java 21, Spring Boot 3.5.16, Maven, PostgreSQL, Hibernate/JPA and targeted JDBC SQL.
Implemented: browser authentication, organization RBAC, sequential request workflows,
tenant-safe search/reviewer inbox, bounded published-policy Redis caching, and
explicit database migrations.

## Build and test

From repository root with Java 21, Maven 3.8+, and reachable Docker:

```sh
bash scripts/test-backend.sh
bash scripts/test-db.sh
```

The Maven verify phase runs unit and real HTTP/PostgreSQL integration tests and
packages an executable JAR. Testcontainers creates a fresh database/container and
fails (does not silently skip) if Docker is unavailable. No committed database or
application passwords are used; integration users are disposable fixtures.

## Local execution

```sh
docker compose up -d --wait
python3 scripts/run-backend.py --jar
```

The launcher resolves the private Compose environment in memory, binds 127.0.0.1,
and permits insecure cookies only for this local HTTP process. It never prints the
resolved credentials. Without `--jar`, it invokes Maven spring-boot:run.
Production must use direct managed environment configuration, not this dev launcher.
No backend Dockerfile/CI/hosted deployment exists yet.

## Production environment contract

Required: DATABASE_URL (JDBC), DATABASE_USER, DATABASE_PASSWORD. Defaults:
SERVER_ADDRESS=127.0.0.1, SERVER_PORT=8080, AUTH_COOKIE_SECURE=true,
AUTH_SESSION_TTL=PT12H, AUTH_RATE_WINDOW=PT1M, AUTH_LOGIN_LIMIT=10,
AUTH_SIGNUP_LIMIT=5. Only explicitly configured ingress should expose the service.
Flyway is enabled locally by default. Production should gate migrations separately
and set FLYWAY_ENABLED=false after the migration job, keeping Hibernate validation.

[Authentication API](../docs/api/authentication.md),
[session decision](../docs/decisions/005-cookie-sessions.md),
[LLD](../docs/architecture/lld.md), and
[verification](../docs/verification/milestone-3.md) describe behavior and limits.

M4 adds organization-scoped JDBC RBAC services and live grant checks. See
[RBAC API](../docs/api/rbac.md), [policy ADR](../docs/decisions/006-organization-rbac.md),
and [verification](../docs/verification/milestone-4.md).

M5 implements typed workflow configuration, sequential request execution,
per-request concurrency and durable command receipts. See [Core API](../docs/api/workflows.md),
[ADR 007](../docs/decisions/007-sequential-workflow-commands.md) and
[verification](../docs/verification/milestone-5.md).

M6 implements bounded search, filtering, tuple cursor navigation and reviewer inbox.
See [Search API](../docs/api/search.md) and [verification](../docs/verification/milestone-6.md).

M7 caches authorized published-policy reads only. Core commands and permission
checks stay PostgreSQL-backed. See [cache contract](../docs/cache/workflow-policy.md)
and [verification](../docs/verification/milestone-7.md).

M8 adds a Spring AMQP relay and activity consumer, both from the same modular backend.
Start dependencies with `docker compose up -d --wait` before the dev launcher.
Outside the launcher enable `ASYNC_ENABLED=true` and provide RABBITMQ_HOST/PORT/
USERNAME/PASSWORD/VHOST; TLS via RABBITMQ_TLS_ENABLED. Defaults are opt-in; pending
events still accumulate when disabled. ASYNC_RELAY_ENABLED / ASYNC_CONSUMER_ENABLED
control background roles independently. See [delivery configuration/runbook](../docs/async/event-delivery.md),
[Activity API](../docs/api/activity.md) and [M8 evidence](../docs/verification/milestone-8.md).

M9 adds personal notification preferences/inbox, independent Rabbit projection and
leased email worker. EmailSender has an actual SMTP adapter; dev launcher uses
local-only Mailpit, never external forwarding. Defaults outside dev disable notification
and email transport; see [API](../docs/api/notifications.md),
[provider/worker contract](../docs/notifications/delivery.md), and
[M9 evidence](../docs/verification/milestone-9.md). No external mailbox was contacted.
