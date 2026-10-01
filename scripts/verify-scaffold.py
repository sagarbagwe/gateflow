#!/usr/bin/env python3
"""Dependency-free static checks. These are not application or container tests."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

def check(condition, message):
    if not condition:
        print(f"FAIL: {message}", file=sys.stderr)
        raise SystemExit(1)
    print(f"PASS: {message}")

required = [
    "docs/testing/strategy.md", "docs/testing/coverage-baseline.json",
    "docs/verification/milestone-13.md", "scripts/test-e2e.sh", "backend/tests/e2e/packaged_api.py",
    "docs/api/conventions.md", "docs/api/openapi.md", "docs/api/openapi.json",
    "docs/verification/milestone-12.md", "docs/decisions/014-api-contract-and-internal-openapi.md",
    "docs/concurrency/consistency.md", "docs/decisions/013-database-authoritative-concurrency.md",
    "docs/verification/milestone-11.md",
    "backend/src/test/java/com/gateflow/workflow/ConcurrencyIntegrationTest.java",
    "docs/api/audit.md", "docs/audit/evidence.md",
    "docs/decisions/012-audit-evidence-and-privileged-browsing.md", "docs/verification/milestone-10.md",
    "backend/src/main/resources/db/migration/V11__harden_audit_evidence.sql",
    "docs/notifications/delivery.md", "docs/api/notifications.md",
    "docs/decisions/011-notifications-and-smtp-outcomes.md", "docs/verification/milestone-9.md",
    "backend/src/main/resources/db/migration/V10__add_notifications.sql",
    "docs/async/event-delivery.md", "docs/api/activity.md",
    "docs/decisions/010-transactional-request-events.md", "docs/verification/milestone-8.md",
    "backend/src/main/resources/db/migration/V9__add_transactional_outbox.sql",
    "docs/cache/workflow-policy.md", "docs/decisions/009-published-policy-cache.md",
    "docs/verification/milestone-7.md",
    "docs/api/search.md", "docs/decisions/008-postgresql-search-keyset.md",
    "docs/verification/milestone-6.md",
    "backend/src/main/resources/db/migration/V7__add_request_search.sql",
    "backend/src/main/resources/db/migration/V8__protect_request_cursor_id.sql",
    "README.md", "LICENSE", ".gitignore", ".env.example", "docker-compose.yml",
    "docs/architecture/system-design.md", "docs/development.md",
    "docs/milestones.md", "docs/decisions/001-modular-monolith.md",
    "docs/decisions/002-persistence.md", "docs/decisions/003-async-delivery.md",
    "docs/verification/milestone-1.md", "scripts/check.sh", "scripts/dev-db.sh",
    "backend/src/.gitkeep", "backend/tests/.gitkeep", "frontend/src/.gitkeep",
    "frontend/tests/.gitkeep", "docs/api/.gitkeep", "docs/database/.gitkeep",
    ".github/workflows/.gitkeep",
    "docs/database/schema.md", "docs/database/er-diagram.md",
    "docs/database/indexes.md", "docs/database/transactions.md",
    "docs/database/migrations.md", "scripts/migrate-db.sh", "scripts/test-db.sh",
    "backend/tests/database/integrity.sql",
    "backend/src/main/resources/db/migration/V1__create_core_schema.sql",
    "backend/src/main/resources/db/migration/V2__enforce_workflow_and_audit_integrity.sql",
    "backend/src/main/resources/db/migration/V3__seed_permission_catalog.sql",
    "backend/pom.xml", "backend/README.md", "scripts/test-backend.sh", "scripts/run-backend.py",
    "docs/api/workflows.md", "docs/decisions/007-sequential-workflow-commands.md",
    "docs/verification/milestone-5.md",
    "backend/src/main/resources/db/migration/V6__add_workflow_commands.sql",
    "docs/api/authentication.md", "docs/api/rbac.md", "docs/architecture/lld.md",
    "docs/decisions/006-organization-rbac.md", "docs/verification/milestone-4.md",
    "backend/src/main/resources/db/migration/V5__add_rbac_management_metadata.sql",
    "docs/decisions/005-cookie-sessions.md", "docs/verification/milestone-3.md",
]
check(all((ROOT / f).is_file() for f in required), "required scaffold files exist")
compose = (ROOT / "docker-compose.yml").read_text()
env = dict(line.split("=", 1) for line in (ROOT / ".env.example").read_text().splitlines()
           if line.strip() and not line.lstrip().startswith("#"))
references = set(re.findall(r"(?<!\$)\$\{([A-Z_]+)", compose))
check(references == set(env), "Compose environment references match template")
check(env["POSTGRES_PASSWORD"] == "replace-with-a-unique-local-password",
      "environment template contains a placeholder, not a credential")
check('127.0.0.1:${POSTGRES_PORT:-5432}:5432' in compose,
      "database host binding is loopback-only")
check('127.0.0.1:${REDIS_PORT:-6379}:6379' in compose, "Redis host binding is loopback-only")
check(env["REDIS_PASSWORD"] == "replace-with-a-unique-redis-password", "Redis example password is a placeholder")
check('maxmemory 128mb' in compose and 'maxmemory-policy allkeys-lru' in compose,
      "Redis cache has bounded memory and reconstructible-data eviction policy")
check('127.0.0.1:${RABBITMQ_PORT:-5672}:5672' in compose, "RabbitMQ host binding is loopback-only")
check(env["RABBITMQ_PASSWORD"] == "replace-with-a-unique-rabbitmq-password", "RabbitMQ example password is a placeholder")
check('rabbitmq_data:/var/lib/rabbitmq' in compose and 'rabbitmq-diagnostics' in compose, "RabbitMQ durable storage and health check configured")
check('127.0.0.1:${MAILPIT_SMTP_PORT:-1025}:1025' in compose and '127.0.0.1:${MAILPIT_HTTP_PORT:-8025}:8025' in compose, "mail capture host bindings are loopback-only")
check('user: "1000:1000"' in compose and '--disable-version-check' in compose, "mail capture is unprivileged and avoids update callbacks")
check('postgres_data:/var/lib/postgresql/data' in compose,
      "PostgreSQL 17 data directory has a named volume")
check('pg_isready' in compose and '$$POSTGRES_USER' in compose
      and '$$POSTGRES_DB' in compose, "health check defers env expansion to container")
for document in sorted(ROOT.rglob("*.md")):
    for destination in re.findall(r"(?<!!)\[[^\]]+\]\(([^)]+)\)", document.read_text()):
        if "://" in destination or destination.startswith("#"):
            continue
        target = destination.split("#", 1)[0]
        check((document.parent / target).exists(),
              f"local documentation link resolves: {document.relative_to(ROOT)} -> {target}")
check((ROOT / "backend/pom.xml").is_file(), "backend build descriptor exists")
check((ROOT / "backend/src/main/resources/db/migration/V4__create_authentication_storage.sql").is_file(),
      "authentication storage migration exists")
print("Static scaffold checks complete; no runtime behavior verified.")
