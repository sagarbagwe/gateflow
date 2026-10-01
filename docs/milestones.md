# Incremental delivery plan

Milestones 1–7 are implemented and verified. This change covers Milestone 7 only. Each major milestone requires user
approval after implementation, checks, fixes, and documentation. Tests/security
invariants start with their features rather than waiting for later review phases.

| # | Milestone | Exit evidence |
| --- | --- | --- |
| 1 | Repository and architecture | Git commit, docs, scaffold checks; Compose smoke test on capable host |
| 2 | Database design | ER diagram, Flyway migrations, tenant-safe constraints/indexes, migration tests |
| 3 | Authentication | Signup/login/logout, lifecycle ADR, password/rate-limit/security tests |
| 4 | RBAC | Separate roles/permissions, organization/resource authorization tests |
| 5 | Core workflow | Sequential submission/decisions, version binding, transition/transaction tests |
| 6 | Search/filtering | Bounded tenant-aware queries, pagination, explain-plan evidence |
| 7 | Redis | Justified cache keys/TTL/invalidation/outage tests |
| 8 | Async processing | Transactional outbox, broker, retries, deduplication, dead-letter tests |
| 9 | Notifications | In-app inbox, preferences, email adapter and delivery tests |
| 10 | Audit | Full evidence fields, restricted mutation paths, integrity tests |
| 11 | Concurrency | At least two deterministic race tests; refine early safeguards |
| 12 | API quality | Consistent errors/statuses, request IDs, OpenAPI, compatibility review |
| 13 | Testing expansion | Unit/integration/security/concurrency/E2E coverage of critical paths |
| 14 | Frontend | Accessible responsive workflow UI and loading/error/empty states |
| 15 | Observability | Metrics, health, structured logs, traces, documented failure signals |
| 16 | Full Docker stack | Backend/UI/dependencies start together and pass smoke checks |
| 17 | CI/CD | Build/lint/tests/images/security checks; no unapproved production deployment |
| 18 | AWS architecture | Actual service choices, recovery/security/cost/rollout documentation |
| 19 | Performance | Measured baselines and justified before/after optimizations |
| 20 | Security review | Threat/endpoint/dependency review with remaining risks recorded |

Basic audit evidence and concurrency safeguards start in RBAC (M4), then core
transactions (M5); later milestones make them comprehensive. Initial Docker setup is
not full containerization. Authentication/authorization changes invalidate any
previous security evidence and need new tests.

## Living documents

`docs/architecture/lld.md`, detailed API/schema docs, security review, performance
reports, and `docs/interview/` are added when their contents can reflect actual
implementation. Avoid empty documents pretending those milestones are complete.
