# Migration owner and runtime role separation

The default local Compose owner account is convenient, **not** the production runtime identity.
The optional `docker-compose.runtime-role.yml` overlay exercises a separate `gateflow_app`
login after the owner has applied the eleven Flyway migrations.

## Operator sequence

1. Back up the database and verify the target environment. In production, obtain both
   credentials through the secret manager; never place them in Git, chat, shell argv or CI artifacts.
2. Run the reviewed migration artifact using the migration owner in a bounded one-shot job.
3. Run `scripts/database/provision-runtime-role.sql` as that owner. Supply `database_name`
   and `runtime_password` through private psql stdin; the pending CI workflow demonstrates
   this with disposable credentials read from a mode-0600 `.env` file.
4. Run `scripts/database/verify-runtime-role.sql`. It must reject audit update/delete/truncate,
   table alteration, schema creation, permission-catalog deletion and migration-history reads.
5. Configure the application with the new runtime account and `FLYWAY_ENABLED=false`.
   Hibernate remains `ddl-auto=validate`; missing migrations prevent a healthy startup.
6. Exercise signup, organization/RBAC changes, policy publishing, submission/approval,
   outbox projection, notifications and logout. Then rotate/revoke the old runtime credential
   only after a separately approved rollback plan and deployment check.

## Permission model

- Runtime account has no superuser, database/role creation, replication or inherited role privileges.
- It has schema USAGE and ordinary application-table DML, but no schema CREATE or table ownership.
- Audit evidence receives SELECT/INSERT only. Existing triggers additionally protect immutable history.
- The global permission catalog is read-only; Flyway metadata is inaccessible to the application.
- No automatic default grants: re-review and re-provision after schema migrations.
- Provision into a dedicated database with a dedicated role. Check existing ownership and role memberships
  before reusing any previously created `gateflow_app`; this script is not a universal role-remediation tool.

## Verified scope

The isolated stack started with the non-owner account. The full lifecycle and database
negative-operation checks passed. This does **not** prove production credential rotation,
provider IAM policies, external TLS or owner-proof audit retention. None were changed.
