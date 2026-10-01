#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/verify-scaffold.py
for script in scripts/*.sh; do
  bash -n "$script"
done
printf '%s\n' 'PASS: shell syntax'
git diff --check
git diff --cached --check
printf '%s\n' 'PASS: Git whitespace checks'
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  # Validate using placeholders without printing the resolved config or user secrets.
  POSTGRES_DB=gateflow POSTGRES_USER=gateflow POSTGRES_PASSWORD=validation-only \
    POSTGRES_PORT=5432 docker compose --env-file .env.example config --quiet
  printf '%s\n' 'PASS: Compose configuration (not a runtime test)'
else
  printf '%s\n' 'SKIP: Docker Compose configuration and runtime checks; Docker unavailable'
fi
