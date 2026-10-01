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
- V4: global opaque auth sessions and shared fixed-window auth counters (M3).
- V5: creator attribution, protected role metadata, administrative row versions (M4).
- V6: workflow command ledger, state guards, policy row versions, REQUEST_REASSIGN (M5).

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
must be denied. Authentication now exists, but production runtime DB role provisioning and
deployment are still not implemented. The schema is sequential single-reviewer approval; future
parallel approval requires deliberate migrations and application compatibility.

V5 adds nullable creator attribution and system-role/version metadata. Existing
roles are not auto-promoted. Fresh replay and original checksum validation are
required; V1–V4 remain unchanged.

V6 introduces version tokens, sequential state/active-step guards, an append-only
command ledger and REQUEST_REASSIGN backfill for protected ADMIN only. A real
PostgreSQL upgrade test applies V1–V5, creates existing protected/custom roles,
then applies V6, validates old checksums and verifies repeat-migrate no-op.

V6 uses ordinary transactional DDL/index creation, not an online large-table
index rollout. Preflight existing ACTIVE-step duplicates and schedule/measure
locks before applying it to a populated production DB. Do not silently delete
legacy steps or bypass guards to make the migration pass.
