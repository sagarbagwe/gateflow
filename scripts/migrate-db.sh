#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v docker >/dev/null 2>&1 || { echo 'Docker is required.' >&2; exit 1; }
test -f .env || { echo 'Configure .env first.' >&2; exit 1; }
case "${1:-migrate}" in
  migrate|validate|info) action="${1:-migrate}" ;;
  *) echo 'Usage: bash scripts/migrate-db.sh {migrate|validate|info}' >&2; exit 2 ;;
esac
args=()
if [[ -n "${GATEFLOW_MIGRATION_DB:-}" ]]; then
  [[ "$GATEFLOW_MIGRATION_DB" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || {
    echo 'Invalid database name override.' >&2; exit 2;
  }
  args=(-e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$GATEFLOW_MIGRATION_DB")
fi
# Secrets come from Compose environment, never command-line password arguments.
docker compose --profile tools run --rm -T "${args[@]}" flyway "$action"
