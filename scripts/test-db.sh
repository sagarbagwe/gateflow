#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v docker >/dev/null 2>&1 || { echo 'Docker is required.' >&2; exit 1; }
test -f .env || { echo 'Configure .env first.' >&2; exit 1; }
bash scripts/dev-db.sh up
# Only this generated disposable DB is ever created/dropped. Never Flyway-clean a product DB.
test_db="gateflow_test_$(python3 -c 'import secrets; print(secrets.token_hex(6))')"
test_dir="$(mktemp -d)"
created=false
pg() {
  docker compose exec -T postgres sh -c \
    'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' sh "$@"
}
cleanup() {
  status=$?
  trap - EXIT
  if [[ "$created" = true ]]; then
    if ! printf 'DROP DATABASE :"test_db" WITH (FORCE);\n' | pg -v "test_db=$test_db" >/dev/null; then
      echo "FAIL: disposable test database cleanup: $test_db" >&2
      status=1
    fi
  fi
  rm -rf "$test_dir"
  exit "$status"
}
trap cleanup EXIT
printf 'CREATE DATABASE :"test_db";\n' | pg -v "test_db=$test_db" >/dev/null
created=true
export GATEFLOW_MIGRATION_DB="$test_db"
bash scripts/migrate-db.sh migrate > "$test_dir/migrate.log" 2>&1 || { tail -30 "$test_dir/migrate.log"; exit 1; }
echo 'PASS: all migrations applied to a fresh disposable database'
bash scripts/migrate-db.sh validate > "$test_dir/validate.log" 2>&1 || { tail -30 "$test_dir/validate.log"; exit 1; }
echo 'PASS: Flyway validation'
bash scripts/migrate-db.sh migrate > "$test_dir/repeat.log" 2>&1 || { tail -30 "$test_dir/repeat.log"; exit 1; }
history="$(pg -d "$test_db" -Atc 'SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success;')"
[[ "$history" = 3 ]] || { echo "Unexpected versioned migration count: $history" >&2; exit 1; }
grep -qi 'up to date' "$test_dir/repeat.log" || { tail -20 "$test_dir/repeat.log"; exit 1; }
echo 'PASS: second migrate is a no-op with three successful versioned history rows'
pg -d "$test_db" < backend/tests/database/integrity.sql > "$test_dir/integrity.log" 2>&1 || {
  tail -35 "$test_dir/integrity.log"; exit 1;
}
grep 'NOTICE:  PASS:' "$test_dir/integrity.log" | sed 's/^NOTICE:  //'
# Change only a COPY of the migration to prove checksum mismatch is detected.
cp -R backend/src/main/resources/db/migration "$test_dir/migration"
printf '\n-- intentional checksum test; never applied\n' >> "$test_dir/migration/V1__create_core_schema.sql"
if docker compose --profile tools run --rm -T \
    -e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$test_db" \
    -v "$test_dir/migration:/flyway/sql:ro" flyway validate > "$test_dir/checksum.log" 2>&1; then
  echo 'FAIL: altered migration checksum unexpectedly accepted' >&2; exit 1
fi
grep -qi 'checksum mismatch' "$test_dir/checksum.log" || { tail -25 "$test_dir/checksum.log"; exit 1; }
echo 'PASS: altered migration checksum rejected'
# Prove PostgreSQL transactional DDL leaves neither a table nor failed history row.
cp -R backend/src/main/resources/db/migration "$test_dir/rollback-migration"
cat > "$test_dir/rollback-migration/V4__intentional_failure.sql" <<'SQL'
CREATE TABLE gateflow_rollback_probe (id integer);
SELECT deliberately_missing_column FROM gateflow_rollback_probe;
SQL
if docker compose --profile tools run --rm -T \
    -e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$test_db" \
    -v "$test_dir/rollback-migration:/flyway/sql:ro" flyway migrate > "$test_dir/rollback.log" 2>&1; then
  echo 'FAIL: intentionally invalid migration unexpectedly succeeded' >&2; exit 1
fi
grep -qi 'deliberately_missing_column' "$test_dir/rollback.log" || { tail -25 "$test_dir/rollback.log"; exit 1; }
rollback_state="$(pg -d "$test_db" -Atc \
  "SELECT to_regclass('public.gateflow_rollback_probe') IS NULL; SELECT count(*) FROM flyway_schema_history WHERE version='4';")"
[[ "$rollback_state" = $'t\n0' ]] || { echo 'FAIL: failed migration left partial database state' >&2; exit 1; }
echo 'PASS: failed migration rolls back both DDL and schema-history changes'
bash scripts/migrate-db.sh validate > "$test_dir/post-failure-validate.log" 2>&1 || {
  tail -25 "$test_dir/post-failure-validate.log"; exit 1;
}
echo 'PASS: original migrations validate after transactional failure'
echo 'Database suite passed; disposable database will be dropped by cleanup.'
