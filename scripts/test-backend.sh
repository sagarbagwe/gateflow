#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null 2>&1 || { echo 'Maven and Java 21 are required.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker must be available; integration tests are not silently skipped.' >&2; exit 1; }
mvn -B -f backend/pom.xml verify
