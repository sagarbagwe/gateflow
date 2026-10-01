# Development setup

## Current prerequisites

Git, Docker/Compose v2 supporting `up --wait`, Python 3, Java 21 and Maven 3.8+.
The backend build was introduced in M3. Authentication and database verification
were run by the agent; these commands document how to reproduce them.

## Start

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD to a unique local value; keep .env private.
bash scripts/check.sh
bash scripts/dev-db.sh up
bash scripts/dev-db.sh status
bash scripts/migrate-db.sh migrate
bash scripts/migrate-db.sh validate
```

Compose reads `.env`; the start script rejects the placeholder. Passwords must
follow Compose env-file quoting/interpolation rules. Do not print resolved Compose
configuration into shared logs: it can contain the password.

`POSTGRES_PORT` defaults to 5432. If occupied, choose another port in `.env`.
The database binds to 127.0.0.1 only. It is not reachable through the shared browser
and no browser is needed to work on this project.

## Verify a real database on a Docker-capable machine

```sh
docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1;"'
```

Expected: readiness success and a row containing 1. These checks passed in the
agent's Linux sandbox. Password authentication, wrong-password rejection, and
volume persistence also passed; see [verification evidence](verification/milestone-1.md).
Migrations now exist; run `bash scripts/test-db.sh` for the real database
integrity/replay/checksum suite. It creates and cleans up only a disposable database.

## Backend build and local launch

```sh
bash scripts/test-backend.sh
python3 scripts/run-backend.py --jar
```

Maven verify runs unit and real HTTP/Testcontainers tests and packages the JAR.
Tests fail if Docker is unavailable. The local-only launcher resolves credentials
from Compose in memory, never prints them, binds 127.0.0.1 and permits local HTTP
cookies. It is not the production entrypoint. Without --jar it runs Maven's
spring-boot:run goal. See [backend setup](../backend/README.md) for environment names.

The agent sandbox uses a non-default Docker Unix socket and explicit Java 21 path;
those paths are not hardcoded into project scripts. Standard Docker installations
do not need overrides. Custom setups can provide DOCKER_HOST and, when necessary,
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE / TESTCONTAINERS_HOST_OVERRIDE.

## Stop / inspect

```sh
bash scripts/dev-db.sh logs
bash scripts/dev-db.sh down
```

`down` preserves the named volume. PostgreSQL initialization variables apply only
to a fresh data directory; changing a password in `.env` does not change the
password stored in an existing database.

**Destructive local reset:** `docker compose down --volumes` deletes this local
PostgreSQL volume. Back up anything needed first; never use this on production.

## Environment boundaries

- `.env.example`: tracked variable names and non-production placeholders.
- `.env`: local values, ignored by Git.
- Production: managed secrets and restricted networking; not this Compose file.
- Authentication APIs exist as a separately launched Spring Boot process. No
  Redis, RabbitMQ, workflow APIs, or UI exist yet. Flyway is a one-shot tools profile;
  PostgreSQL/Flyway images are digest-pinned.

## Verification rules

Report passed, failed, and skipped checks separately. Static YAML parsing and
shell syntax do not prove a running service, image availability, or database
connectivity. Do not claim Java 21 compatibility based on a different installed JDK.

## M4 configuration and fixture isolation

`RBAC_ORG_LIMIT` defaults to 10 (valid 1–100); `RBAC_ROLE_LIMIT` defaults to 100
(valid 4–1000). They have validated application defaults and do not change the
Compose environment template. Administration writes require current expectedVersion.

RBAC integration tests retain immutable audit rows until the disposable
Testcontainers database/container is removed. Do not run their fixtures against
shared production data or bypass append-only audit triggers for cleanup.
No RBAC demo account passwords or fixtures are committed.

## M5 execution and test isolation

Use auth/CSRF and the organization-scoped [workflow commands](api/workflows.md).
There is no seeded business tenant or public demo. All request commands require a
fresh UUID-shaped Idempotency-Key per business intent; retry the same original
command with that key after a timeout. Versions are from response bodies.

Workflow tests use disposable PostgreSQL containers. Receipt/audit/decision rows
are append-only; cleanup removes the entire disposable database/container, never
bypassing guards on a shared product database. No broker, Redis or frontend is
required to run the M5 synchronous core. The body limit is fixed at 256 KiB for
current synchronous JSON POST/PUT/PATCH routes. Streaming/async uploads require
a deliberately different bounded transport before introducing those APIs.

## M6 search verification

Search requires no service beyond PostgreSQL. The default API is cursor-paged;
repeat filters/scope on continuation. See [API](api/search.md) and
[verification](verification/milestone-6.md) for bounds and consistency limits.
`RequestSearchPostgresTest` writes generated EXPLAIN evidence under
`backend/target/query-plans/`; it tests a two-tenant synthetic fixture, not production
capacity. Normal VACUUM/ANALYZE after bulk insertion matters for GIN plan selection.

## M7 Redis dependency and opt-in

Update ignored `.env` with the new REDIS_PASSWORD/REDIS_PORT entries from the
example; generate a distinct URL-safe Redis password rather than reuse PostgreSQL
credentials. `docker compose up -d --wait` now starts PostgreSQL and Redis.
`bash scripts/dev-db.sh up` remains PostgreSQL-only. The backend/UI are still not
Compose services (full containerization is M16).

`python3 scripts/run-backend.py --jar` resolves both private services in memory
and enables published-policy caching by default. It rejects the Redis placeholder;
set PUBLISHED_POLICY_CACHE_ENABLED=false to explicitly disable. Direct application
startup defaults the feature false and needs only PostgreSQL for core behavior.
Redis unavailable with the feature enabled is a DB fallback, not a startup requirement.
Never print resolved Compose config, environment values or Redis credentials.

Full Maven verify now includes fresh authenticated Redis/Testcontainers fixtures.
The eviction/OOM/paused-server tests change only their disposable test container,
restore configuration/unpause in finally, and remove entire containers at cleanup.
See [cache](cache/workflow-policy.md) and [verification](verification/milestone-7.md).
