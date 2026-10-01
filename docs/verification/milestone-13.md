# Milestone 13 — reproducible layered testing and coverage baseline

Full clean Maven verify with JaCoCo 0.8.14: **313 tests**, zero failures/errors/skips,
actual executable JAR and coverage HTML/XML produced. The same meaningful unit,
HTTP/integration, security, migration/rollback, concurrency and SMTP tests remain
required; no fake getters/tests or selective exclusions were added for percentages.

Measured full production-source coverage: **96.68% lines**
(2704 covered / 2797 total) and **86.18% branches**
(842 / 977). JaCoCo includes all 160 analyzed classes.
Root counters independently reconcile with package counters; the [evidence](../testing/coverage-baseline.json)
contains exact numerators/denominators, method and raw XML hash. Counters have different
grains: line and branch coverage are not interchangeable or proof of correctness.

Reproducible `bash scripts/test-e2e.sh`: **102 packaged assertions**, passed. Checked
actual JAR/auth/tenant/policy/submit/replay/approval/audit/inbox/SMTP and broker failure/
restart recovery. Disposable database/vhost/app and local fixture emails removed;
no external mailbox. OpenAPI snapshot regeneration is an explicit optional flag.

[Strategy](../testing/strategy.md) defines layers, fixtures, failure assertions,
coverage interpretation and dev-only outage scope. Testcontainers absence fails,
not silently skips. Java 21 formatting, shell syntax, Python compile/scaffold/links,
Compose and private credential scan passed. No schema/runtime business change.

No artificial 100% target, production load claim or browser-E2E claim before the UI.
Browser E2E is delivered with M14. Coverage misses are a review queue, especially
security/business error handling, not a reason to pad the test count. The user has
approved remaining milestones; frontend is next.
