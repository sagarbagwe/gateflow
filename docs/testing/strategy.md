# Meaningful verification strategy

## Layers and authority
- Unit: transition/validation policies, cookie/token behavior, query parsing, cache
  codec/failure rules, delivery classification and safe API errors.
- Integration: real HTTP plus digest-pinned PostgreSQL/Redis/RabbitMQ/Mailpit. Assert
  actual constraints/transactions, not mock success. Testcontainers is required;
  missing Docker fails rather than silently skips.
- Security: sessions/CSRF, tenant concealment, grants/delegation/revocation, field
  allowlists and safe body/header errors.
- Concurrency: observed PostgreSQL lock barriers and repeated fresh-container races;
  independently count committed effects. Not an exhaustive interleaving proof.
- Packaged E2E: actual executable JAR with all dev dependencies. Signup, tenant/roles,
  version publication, submit/retry/approval, async activity/inbox/SMTP, preferences,
  audit and broker outage/restart recovery. Mailpit is local only; no external inbox.
- Browser E2E: added with the frontend in M14, not pretended present before that step.

## Reproduce
Java 21, Maven, Docker/Compose and private .env are required for the repository's
checks. The agent executes them here; users need not install anything to receive
verified source. `bash scripts/test-backend.sh` runs full Maven verify and coverage;
`bash scripts/test-db.sh` tests disposable schema/migration/integrity failures;
`bash scripts/test-e2e.sh` runs packaged API E2E after dependencies are healthy.
Optional --export-openapi regenerates the snapshot from the actual application.

E2E creates a disposable database and broker vhost, captures only its fixture emails,
then stops its app and removes its own data. It deliberately pauses/restarts the
selected DEV Compose broker for failure cases: never point this at production or
someone else's shared deployment. Dedicated Compose CI projects isolate that outage.
Commands read credentials in memory, never print resolved Compose configuration.

## Coverage without gaming
JaCoCo 0.8.14 writes HTML/XML to backend/target/site/jacoco during verify. Coverage is
an investigative signal: review missed business/security branches and test outcomes,
not a promise every line is correct. No fabricated 100% claim, exclusions to inflate
coverage, or new boilerplate tests just for numbers. Current real SMTP refusal,
uncertain acceptance, marker rollback and lease fencing cases matter more than
getter coverage. A future gate must be justified by stable measured module baselines.

Tests should assert both response semantics and persisted effects. Keep fixtures
bounded, randomly identified, isolated and disposable. Include negative/rollback
paths, permissions after waiting, dependency outages and stale retries. Log failures
without credentials/rejected values. Flaky tests are defects, not skipped successes.

The actual full-suite counts and measured coverage appear in milestone verification
reports. Reports/containers are build artifacts; no target output or secrets belong
in Git. Frontend tests and CI layer this same suite later without weakening it.
