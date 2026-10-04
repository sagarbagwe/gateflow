-- Operator-only provisioning, AFTER Flyway runs as the migration owner.
-- psql -X -v ON_ERROR_STOP=1 -v database_name=gateflow
-- Supply runtime_password privately through stdin; never in shell argv or Git.
\if :{?runtime_password}
\else
  \echo 'runtime_password is required'
  \quit 1
\endif
\if :{?database_name}
\else
  \echo 'database_name is required'
  \quit 1
\endif
SELECT 'CREATE ROLE gateflow_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gateflow_app') \gexec
ALTER ROLE gateflow_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database_name" TO gateflow_app;
GRANT USAGE ON SCHEMA public TO gateflow_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO gateflow_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO gateflow_app;
-- Normal application flows can append evidence but cannot rewrite or truncate it.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON audit_logs FROM gateflow_app;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON permissions FROM gateflow_app;
REVOKE ALL ON flyway_schema_history FROM gateflow_app;
-- Do not grant default privileges: explicitly review/re-provision after migrations.
