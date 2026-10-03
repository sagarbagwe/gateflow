\set ON_ERROR_STOP on
SET ROLE gateflow_app;
DO $$ BEGIN
  IF current_user <> 'gateflow_app' THEN RAISE EXCEPTION 'Wrong runtime role'; END IF;
  IF (SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication FROM pg_roles WHERE rolname=current_user) THEN
    RAISE EXCEPTION 'Runtime role has administrative capabilities';
  END IF;
END $$;
DO $$ BEGIN UPDATE audit_logs SET action=action WHERE false; RAISE EXCEPTION 'audit UPDATE allowed';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
DO $$ BEGIN DELETE FROM audit_logs WHERE false; RAISE EXCEPTION 'audit DELETE allowed';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
DO $$ BEGIN TRUNCATE audit_logs; RAISE EXCEPTION 'audit TRUNCATE allowed';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
DO $$ BEGIN ALTER TABLE audit_logs ADD COLUMN forbidden integer; RAISE EXCEPTION 'DDL allowed';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
DO $$ BEGIN CREATE TABLE forbidden_runtime_ddl(id integer); RAISE EXCEPTION 'schema CREATE allowed';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
DO $$ BEGIN DELETE FROM permissions WHERE false; RAISE EXCEPTION 'permission catalog edit allowed';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
DO $$ BEGIN SELECT count(*) FROM flyway_schema_history; RAISE EXCEPTION 'migration metadata accessible';
EXCEPTION WHEN insufficient_privilege THEN NULL; END $$;
SELECT 'PASS: non-owner runtime role; seven forbidden operations rejected' AS verification;
RESET ROLE;
