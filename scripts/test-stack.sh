#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cookies=$(mktemp)
trap 'rm -f "$cookies"' EXIT
trap 'docker compose ps; docker compose logs --no-color --tail=80 backend frontend' ERR
docker compose up -d --build --wait --wait-timeout 240
curl --fail --silent --show-error "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health/readiness" | grep -q '"status":"UP"'
curl --fail --silent --show-error "http://127.0.0.1:${FRONTEND_PORT:-3000}/" | grep -q '<title>GateFlow</title>'
curl --fail --silent --show-error -c "$cookies" "http://127.0.0.1:${FRONTEND_PORT:-3000}/api/v1/auth/csrf" | grep -q 'token'
BASE_URL="http://127.0.0.1:${FRONTEND_PORT:-3000}" python3 - <<'PY'
import os, urllib.request, urllib.error
base=os.environ['BASE_URL']
with urllib.request.urlopen(base) as r:
    assert r.headers.get('X-Content-Type-Options')=='nosniff'
    assert r.headers.get('X-Frame-Options')=='DENY'
    assert "frame-ancestors 'none'" in r.headers.get('Content-Security-Policy','')
try: urllib.request.urlopen(base+'/api/v1/auth/me')
except urllib.error.HTTPError as e: assert e.code==401
else: raise AssertionError('Anonymous API unexpectedly allowed')
print('PASS: frontend headers and anonymous API boundary')
PY
echo 'PASS: full GateFlow stack is healthy'
