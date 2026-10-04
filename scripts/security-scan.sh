#!/usr/bin/env bash
# Requires a locally installed, verified Trivy CLI; no implicit privileged mounts.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v trivy >/dev/null || { echo 'Install and verify Trivy first' >&2; exit 1; }
[[ ${GATEFLOW_DISPOSABLE_STACK:-} == 1 ]] || { echo 'Disposable stack consent required' >&2; exit 1; }
case "${COMPOSE_PROJECT_NAME:-}" in gateflow-ci|gateflow-test-*|gateflow-hardening) ;; *) echo 'Unknown Compose project' >&2; exit 1;; esac
mkdir -p verification/security
# Includes unfixed findings: an unavailable fix is a release blocker, not an auto-ignore.
while IFS= read -r image; do
  key=$(printf '%s' "$image" | sha256sum | cut -c1-12)
  trivy image --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format json \
    --output "verification/security/image-$key.json" "$image"
done < <(docker compose images -q backend frontend | sort -u)
# Infrastructure images are inventoried separately: production uses managed RDS,
# ElastiCache and a reviewed broker deployment, not local Compose credentials.

# Never scan/upload .env, disposable fixtures, build caches, or database dumps.
trivy fs --offline-scan --scanners secret --exit-code 1 --skip-dirs node_modules --skip-dirs .git \
  --skip-dirs verification --skip-dirs .qa --skip-dirs target --skip-dirs dist \
  --skip-files .env --skip-files .load-fixture.json --format json \
  --output verification/security/secrets.json .
