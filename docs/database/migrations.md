# Migration operations

SQL source: `backend/src/main/resources/db/migration/`.
Flyway runs in a tools-profile container; no application build or local Java/Maven
installation is required. The migration runner image is pinned by digest after
verification. The service is not a continuously running application component.

## Commands

```sh
bash scripts/dev-db.sh up
bash scripts/migrate-db.sh migrate
bash scripts/migrate-db.sh validate
bash scripts/migrate-db.sh info
bash scripts/test-db.sh
```

Compose obtains credentials from ignored `.env`; never place passwords in command
arguments, committed config, or shared `docker compose config` output. Migration
names are validated. Clean, baseline-on-migrate, and out-of-order execution are
disabled. The helper permits only migrate/validate/info; repair requires a separate
reviewed operator procedure.

## Versions

- V1: fourteen core tables, tenant-aware FKs, checks, uniqueness, and query indexes.
- V2: workflow publication/immutability, submitted-request binding, append-only
  decision and audit guards.
- V3: twelve permission codes; no accounts, organization roles, or assignments.

Flyway creates its own `flyway_schema_history` table, separate from domain tables.
Do not edit an applied migration. Add a new version. Validation must reject checksum
mismatch; do not hide it using automatic repair. Run migrations before deploying
application code requiring the new schema. Production rollout uses reviewed
backups and expand/contract changes; a destructive down migration is not the default.

## Fresh database checks

The database test helper (added with this milestone's verification) creates a
randomly named disposable `gateflow_test_*` database, runs migrations, validation,
repeatability/integrity/checksum checks, tests a copied failing migration for
transactional DDL/history rollback, and drops that test database. It does not clean or
reset the product database. Fresh-database replay is required evidence for migration
changes; merely running against an already-migrated DB is insufficient.

## Important limitations

Local PostgreSQL user is the development superuser. Production must use separate
migration/runtime identities and managed secrets; runtime DDL and audit mutation
must be denied. This milestone does not deploy production or configure a runtime
application account. The schema is sequential single-reviewer approval; future
parallel approval requires deliberate migrations and application compatibility.
