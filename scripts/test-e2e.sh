#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Only dev/CI dependencies; never run against a production Compose deployment.
test -f backend/target/gateflow-backend-0.1.0-SNAPSHOT.jar || { echo 'Run Maven verify first.' >&2; exit 1; }
python3 backend/tests/e2e/packaged_api.py "$@"
