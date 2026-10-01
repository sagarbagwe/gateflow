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
