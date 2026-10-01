# Milestone 1 verification

Execution date: 2026-10-01. Scope: repository/architecture scaffold and local
PostgreSQL infrastructure. No application features have been implemented.

## Environment and tooling

Initial inspection found no Docker, Maven, or Gradle. The agent subsequently
installed Docker and Docker Compose and completed runtime verification in this
Linux sandbox; the user was not required to run anything on their computer.

- Git 2.49.0; Python 3 available.
- Docker Engine 25.0.16 with overlay2 storage.
- Docker Compose v5.5.1; official release binary verified against its SHA-256 digest.
- PostgreSQL 17.11 from `postgres:17-bookworm`.
- Observed image digest:
  `postgres@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652`.
- Installed Java is Corretto/OpenJDK 25.0.4, not the planned Java 21 target.
  No Java build exists yet; Java compatibility has not been tested.

The PostgreSQL tag follows patch updates; the digest above records the tested
image, not a claim that the Compose configuration is digest-pinned.

## Results

| Check | Result |
| --- | --- |
| Required files, environment references, local documentation links | Passed |
| Bash syntax and Git staged/unstaged whitespace | Passed |
| Independent YAML parse and configuration assertions | Passed |
| Missing `.env` / unchanged password startup guards | Passed using mock Docker |
| Startup dispatch / unsupported command rejection | Passed using mock Docker |
| `.env` ignored; `.env.example` tracked | Passed |
| `docker compose config --quiet` | Passed with real Compose |
| `bash scripts/dev-db.sh up` | Passed; container reached healthy state |
| `pg_isready` | Passed: accepting connections |
| `SELECT 1 AS smoke_check` | Passed: returned 1 |
| Host loopback TCP connection with configured password | Passed |
| Host TCP connection with incorrect password | Passed: authentication rejected |
| Persistence across Compose down/up without deleting volumes | Passed |
| Disposable test database cleanup | Passed |
| Final scaffold check and container health | Passed |
| Backend/frontend build and application tests | Not applicable: no application code exists |

## Persistence test

Created a disposable database `gateflow_m1_smoke`, created a verification table,
and inserted a marker. Removed and recreated the PostgreSQL container using the
project scripts without deleting the named volume. The marker remained readable.
Dropped the disposable database afterward. No GateFlow application schema,
Flyway migration, or business entity was introduced.

## Corrections and retries

1. Independent YAML parsing caught incorrect health-check quoting. A single-quoted
   YAML scalar preserved inner shell quotes, and the parse/assertions then passed.
2. GitHub publication uses non-executable script modes; documented commands
   explicitly invoke `bash` so cloning does not produce permission errors.
3. The first real container start failed because the sandbox `/data` directory is
   a symlink and the Docker runtime rejected the resulting rootfs path. Restarted
   the sandbox daemon with canonical storage paths, recreated the container, and
   reran all checks successfully. This was environment configuration, not a
   GateFlow Compose defect.

## Security and limitations

A generated local password is stored only in ignored `.env` with file mode 0600.
The Docker daemon uses a local Unix socket, not an exposed TCP endpoint. No
credentials were committed or printed into the verification report. Host port
binding is loopback-only. Password testing used a real TCP client, not the local
Unix socket's trust authentication.

These tests prove the current local dependency setup, configuration guards,
authentication, and volume persistence. They do not prove approval correctness,
production security, high availability, backups, CI, AWS readiness, or application
performance. Those capabilities remain future milestones.

## Reproduction references

Standard startup, readiness, SQL, and shutdown commands are in
[development instructions](../development.md). The authentication test connected
from a client container using Linux host networking. The persistence test used a
disposable database so the product database remained free of application tables.
