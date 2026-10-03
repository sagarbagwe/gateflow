#!/usr/bin/env bash
# Disposable-only restore drill; never connects to hosted databases.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ ${GATEFLOW_DISPOSABLE_STACK:-} == 1 ]] || { echo 'Disposable stack consent required' >&2; exit 1; }
case "${COMPOSE_PROJECT_NAME:-}" in gateflow-ci|gateflow-test-*|gateflow-hardening) ;; *) echo 'Refusing unknown Compose project' >&2; exit 1;; esac
work=$(mktemp -d); chmod 700 "$work"
created=0
cleanup() {
  if [[ "$created" == 1 ]]; then docker compose exec -T postgres sh -ec 'dropdb -U "$POSTGRES_USER" gateflow_restore_verify' >/dev/null; fi
  rm -rf "$work"
}
trap cleanup EXIT
sql="SELECT json_build_object('users',(SELECT count(*) FROM users),'organizations',(SELECT count(*) FROM organizations),'requests',(SELECT count(*) FROM requests),'decisions',(SELECT count(*) FROM approval_decisions),'audit',(SELECT count(*) FROM audit_logs),'migrations',(SELECT count(*) FROM flyway_schema_history WHERE success));"
# Creating an existing DB fails; never drop/overwrite a pre-existing database.
docker compose exec -T postgres sh -ec 'createdb -U "$POSTGRES_USER" gateflow_restore_verify'
created=1
before=$(printf '%s' "$sql" | docker compose exec -T postgres sh -ec 'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"')
start=$SECONDS
docker compose exec -T postgres sh -ec 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > "$work/backup.dump"
docker compose exec -T postgres sh -ec 'pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d gateflow_restore_verify' < "$work/backup.dump"
after=$(printf '%s' "$sql" | docker compose exec -T postgres sh -ec 'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d gateflow_restore_verify')
[[ "$before" == "$after" ]] || { echo 'Restore counts differ; stop writes and investigate' >&2; exit 1; }
# Verify restored audit immutability with a real row, not a zero-row update.
docker compose exec -T postgres sh -ec 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d gateflow_restore_verify' <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM audit_logs) THEN RAISE EXCEPTION 'Need an audit fixture'; END IF;
  BEGIN
    UPDATE audit_logs SET action=action WHERE id=(SELECT id FROM audit_logs LIMIT 1);
    RAISE EXCEPTION 'Restored audit UPDATE unexpectedly permitted';
  EXCEPTION WHEN SQLSTATE '55000' THEN NULL; END;
END $$;
SQL
printf 'PASS: restore schema/counts and audit trigger; elapsed_seconds=%s; counts=%s\n' "$((SECONDS-start))" "$after"
