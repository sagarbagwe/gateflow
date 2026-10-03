#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d --build --wait
trap 'docker compose logs --no-color backend frontend | tail -n 200' ERR
curl --fail --silent --show-error http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health/readiness | grep -q '"status":"UP"'
curl --fail --silent --show-error http://127.0.0.1:${FRONTEND_PORT:-3000}/ | grep -q '<title>GateFlow</title>'
curl --fail --silent --show-error -c /tmp/gateflow-cookies http://127.0.0.1:${FRONTEND_PORT:-3000}/api/v1/auth/csrf | grep -q 'token'
echo 'PASS: full GateFlow stack is healthy'
