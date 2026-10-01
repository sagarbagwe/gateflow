# Milestone 2 verification

Execution date: 2026-10-01. All verification ran in the agent's Docker-capable
Linux sandbox. No user-side setup was requested.

## Implemented scope

Fourteen core domain tables; three Flyway SQL migrations; tenant-aware foreign
keys; primary/unique/check constraints; query-oriented indexes; narrow database
integrity triggers; permission vocabulary; digest-pinned PostgreSQL/Flyway tools;
ER diagram; schema/index/transaction/operations docs; disposable database tests.
No application, authentication encoder, REST API, frontend, queue, or cache exists.

## Runtime

- PostgreSQL 17.11 / Docker Engine 25.0.16 / Docker Compose v5.5.1.
- Flyway OSS Edition 13.8.1.
- Both runner/database images are pinned by digest in Compose.
- Migration credentials are read from ignored `.env`, never committed or printed.

## Actual results

`bash scripts/test-db.sh` passed **54 checks**: 48 SQL integrity assertions plus
fresh migration application, Flyway validation, repeat-migrate no-op, altered
checksum rejection, failed-migration rollback, and post-failure validation.
This is a storage integration suite, not 54 application tests.

| Verification | Result |
| --- | --- |
| Fresh disposable database: V1/V2/V3 applied | Passed |
| Flyway checksums/naming/schema validation | Passed |
| Second migrate | No-op; three successful versioned history entries |
| Fourteen domain tables / twelve permission codes | Passed |
| Cross-tenant membership/role/workflow/request/step/audit links | Rejected |
| Case-insensitive duplicate email and duplicate membership/version/step | Rejected |
| Empty/gapped workflow publication | Rejected |
| Published workflow/step mutation | Rejected |
| Submission against draft/other-tenant version | Rejected |
| Submitted payload/version rewrite or return to DRAFT | Rejected |
| Invalid money/JSON/state field combinations | Rejected |
| Stale optimistic SQL version guard | Zero rows affected |
| Unassigned reviewer/duplicate decision | Rejected |
| Decision/audit UPDATE, DELETE, TRUNCATE | Rejected |
| Referenced historical membership deletion | Rejected |
| Altered COPY of V1 checksum | Validation failed as expected |
| Intentionally failing copied V4 | DDL rolled back; no probe table or V4 history row remained |
| Original migration validation after failure | Passed |
| Helper clean/malformed database override guards | Rejected with exit code 2 |
| Disposable database cleanup | Passed; zero `gateflow_test_*` databases remain |
| GateFlow database migrated and validated | Passed: V1/V2/V3 successful |
| Shell syntax, local docs links, environment references, Compose configuration | Passed |

SQL fixture writes are rolled back, and the disposable database is dropped on
exit. The altered checksum test uses copied files; real migration sources are
never edited for that test. Only the verified schema and permission vocabulary
were applied to the product database; no user/tenant fixture data was inserted.
The entire final suite ran again from a second fresh disposable database. The
intentionally invalid V4 exists only in a temporary test directory, not as a real
versioned migration. Test sources are unchanged by negative-test copies.

## Limitations

No complete workflow state machine, RBAC/HTTP authorization, session handling,
notification delivery, load test, multi-connection race test, or production
runtime-role provisioning is claimed. `row_version` supports guarded updates but
is incremented by the future application, not automatically. Composite FKs block
cross-tenant references, not unscoped reads. Schema owners/superusers can bypass
trigger guards; production privilege separation remains mandatory.

The ER diagram is Mermaid source checked against the entity model, not a rendered
image claim. Indexes reflect intended query shapes, not measured speedups.

## Reproduction

`bash scripts/test-db.sh` exercises the isolated suite. See
[database operations](../database/migrations.md) and
[SQL test notes](../../backend/tests/database/README.md). The agent executed the
checks; these instructions are documentation, not a request for local user work.
