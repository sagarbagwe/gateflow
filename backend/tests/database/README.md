# Database integrity tests

Run `bash scripts/test-db.sh` from the repository root with Docker/Compose and a
configured `.env`. The helper starts PostgreSQL if needed, creates an isolated
random test database, applies/validates all current versioned Flyway migrations, verifies a second
migrate is a no-op, runs SQL assertions, tests checksum rejection using copied
migration files, intentionally fails a copied next-version migration to assert transactional DDL/history
rollback, validates the original sources afterward, and drops the disposable
database even on failure.

Fixtures live inside one rolled-back transaction. They use deliberately fake hash
strings, not accounts usable through authentication. The test helper never invokes
Flyway clean and never drops the configured product database. Failed cleanup is
reported as failure rather than silently ignored.

Coverage: tenant references, identity/grant uniqueness, ordered publication,
published immutability, request version/payload binding, field CHECKs, assigned
reviewer identity, duplicate decisions, audit/decision append-only guards, and
optimistic compare-and-set SQL shape. M3 adds session hash/expiry/uniqueness/FK
and limiter-count checks. These are storage tests, not proof of API
security, role eligibility, a complete state machine, or real concurrent races.
Those application and multi-connection tests arrive with their relevant features.
