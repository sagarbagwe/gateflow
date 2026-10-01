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
check(not (ROOT / "backend/pom.xml").exists(), "application build not prematurely introduced")
print("Static scaffold checks complete; no runtime behavior verified.")
