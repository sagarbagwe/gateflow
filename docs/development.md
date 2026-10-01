# Development setup

## Current prerequisites

Git, Docker Engine/Desktop with Docker Compose v2 supporting `up --wait`, and
Python 3. Java 21 and Maven will become prerequisites when the backend build is
introduced. No application build exists in Milestone 1.

## Start

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD to a unique local value; keep .env private.
bash scripts/check.sh
bash scripts/dev-db.sh up
bash scripts/dev-db.sh status
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

Expected: readiness success and a row containing 1. These checks have NOT been
run in the current workspace because Docker is unavailable. Once migrations
exist, a real database integration suite replaces this basic smoke check.

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
- No Redis, RabbitMQ, API, or UI services exist yet.

## Verification rules

Report passed, failed, and skipped checks separately. Static YAML parsing and
shell syntax do not prove a running service, image availability, or database
connectivity. Do not claim Java 21 compatibility based on a different installed JDK.
