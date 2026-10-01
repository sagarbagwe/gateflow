#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v docker >/dev/null 2>&1 || { echo 'Docker is required.' >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo 'Docker Compose v2 is required.' >&2; exit 1; }
case "${1:-}" in
  up)
    test -f .env || { echo 'Copy .env.example to .env and configure it first.' >&2; exit 1; }
    python3 - <<'CHECK'
from pathlib import Path
text = Path('.env').read_text()
if 'replace-with-a-unique-local-password' in text:
    raise SystemExit('Replace the local password placeholder in .env before starting.')
CHECK
    docker compose up -d --wait postgres
    ;;
  down) docker compose down ;;
  status) docker compose ps ;;
  logs) docker compose logs --tail=100 postgres ;;
  *) echo 'Usage: ./scripts/dev-db.sh {up|down|status|logs}' >&2; exit 2 ;;
esac
