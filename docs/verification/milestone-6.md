# Milestone 6 — search, filtering and bounded navigation

## Delivered

Authenticated tenant request search and eligible pending reviewer inbox; shared
request-detail/list visibility; own/all/reviewer-history scopes; PostgreSQL title/
description full-text token search; status/type/workflow/date filtering; oldest/
newest ordering; default tuple keyset navigation and explicit bounded offset.
No Redis, new broker, frontend, CI deployment or extra search service.

## Executed evidence

Java 21, Spring Boot 3.5.16, Maven 3.8.4, PostgreSQL 17.11. Tests ran in the agent
Linux sandbox against actual PostgreSQL containers with pinned images. The user
was not asked to install tools or run commands locally.

- `mvn -B -f backend/pom.xml verify`: **145 tests**, zero failures/errors/skips;
  executable JAR packaged. 52 unit, 89 real HTTP/PostgreSQL, four standalone
  PostgreSQL upgrade/plan tests. All M1–M5 tests remain passing.
- M6 adds 13 unit, 23 HTTP/DB, and three PostgreSQL tests. Coverage includes filters,
  literal/Unicode token search, bounds/injection attempts, summary omissions,
  multiple same-reviewer decisions, ACTIVE vs WAITING assignments, role loss,
  withdrawal/reassignment, scope/tenant/actor visibility, suspension, fresh revoked
  permissions, tied timestamps in both directions, sentinel boundaries,
  normalized filters/page-size changes and later-insert cutoff behavior.
- Five sensitive HTTP scenarios repeated **three additional runs**, fresh DB per
  run: tied keyset pages, post-cutoff insertion, normalized continuation,
  permission revocation between pages and paginated reviewer inbox. All 15 passed.
- `bash scripts/test-db.sh`: **83 PASS checks** (77 SQL integrity assertions and
  six migration lifecycle checks). Fresh migration, validation, repeated no-op,
  checksum rejection, failed DDL rollback and original validation all passed;
  the generated disposable database was dropped.
- Executable-JAR HTTP smoke: **55 assertions** plus cleanup. Real signup/RBAC/
  workflow lifecycles followed by search/filter/cursor/offset/inbox/error checks.
  Startup applied all eight migrations; the entire fixture database was dropped.
- Main development database migrated/validated through V8 and retained zero users,
  organizations, requests, audit rows and command receipts. No business fixtures
  or credentials committed.
- Static scaffold/local documentation links, shell syntax, whitespace and
  resolved Compose validation passed. Java changes formatted in AOSP style.

## Migration correctness

V1–V6 byte-identical to canonical M5 source. V7 adds generated tsvector, GIN,
organization-order and reviewer-history indexes, plus creation-time guard. V8
additively extends the guard to UUID changes: final review found that unreferenced
imported drafts could otherwise change a pagination coordinate through SQL. V7
had already been applied during verification, so its checksum was not edited.
No trigger disabling, schema clean/repair or old migration rewrites.

The 6→8 upgrade fixture contains an approved request, approved execution step and
immutable decision plus a legacy draft. Backfill preserves terminal state/history;
draft text updates recompute search; manual vector assignment fails 428C9; ID/
creation coordinate changes and terminal payload updates fail 55000. The existing
5→6 test explicitly targets V6 to preserve its historical assertions.

## Query-plan verification, not capacity claims

RequestSearchPostgresTest uses the **actual repository SQL builder** with bound
parameters, no enable/disable planner flags, and EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON).
Fixture: two organizations, 15,000 synthetic drafts each, plus approved/draft upgrade
rows = **30,002 records**. Synthetic records deliberately have no active-step
children; correctness of real reviewer inboxes is separately covered by HTTP tests.
No production-distribution or busy-inbox benchmark is claimed.

After normal bulk-load VACUUM (ANALYZE): rare `helios` token uses
`ix_requests_search_document`; latest tenant feed and deep tuple continuation use
`ix_requests_org_created`. Each checked query returns at most 21 candidates for
limit 20. The deep anchor is selected at offset 10,000, then production cursor SQL
uses the tuple condition—not OFFSET. Selective search correctness checks 31 tenant
matches, including the existing approved record. Result hydration is one query,
not per-row details/steps; authorization adds separate queries.

Raw reproducible JSON is written to `backend/target/query-plans/search-plans.json`
when this test runs; generated target output is intentionally not committed.
No timing percentage or “faster at scale” claim is derived from these short runs.
Statistics, distribution, cache warmth and maintenance can change plans.

Initial bulk inserts left a GIN pending list and PostgreSQL chose an existing tenant
index instead. Normal vacuum maintenance—not forced index hints—yielded the GIN
plan. This is an operational lesson and a limit on the evidence, not proof that GIN
wins for common terms or every tenant. Real concurrency/load tuning remains M19.

## Important files

- `RequestAccessPolicy`: one definition of visibility/active eligibility.
- `RequestSearchQuery`, `SearchCursor`: bounded, normalized request/token parsing.
- `RequestSearchService`, `RequestSearchRepository`: current grants, SQL filtering,
  summary joins, immutable tuple order and limit+1 page assembly.
- `RequestSearchController`, `RequestSearchDtos`: two HTTP routes and bounded DTOs.
- `V7__add_request_search.sql`, `V8__protect_request_cursor_id.sql`: additive schema.
- [API](../api/search.md), [ADR 008](../decisions/008-postgresql-search-keyset.md),
  [indexes](../database/indexes.md), [LLD](../architecture/lld.md),
  [system design](../architecture/system-design.md).

## Review findings and limitations

Initial test runs caught an incorrect legacy-fixture column name, malformed
reassignment test field and a too-short fake upgrade password hash. Fixtures were
corrected; application/schema validation was not weakened. Planner maintenance
and complete UUID/timestamp immutability were reviewed and verified explicitly.

Cursors are **unsigned navigation**, with a context digest for continuity—not a
MAC or auth token. Forging an anchor cannot bypass fresh grants or SQL scope.
Token field/size/type/duplicate/trailing-input checks reject malformed input.
Limit can change, filters/actor/scope cannot silently change. No cursor expiry or
frozen multi-page MVCC/export guarantee. Creation cutoff limits ordinary later
submissions; backdated late commits and changed eligibility may still affect pages.
In-flight REPEATABLE_READ queries may complete on pre-revocation snapshots.

Full-text is literal token AND, not substring/fuzzy/stemming/rank. Large common-term
searches may examine/sort many rows despite bounded responses. No COUNT query or
unbounded payload export. Offset mode is capped but still discards preceding rows.
Ordinary V7 DDL/backfill/index creation can block populated tables: maintenance
window, disk/WAL/timeout rehearsal and backups required before production use.
Database-owner bypass remains possible; runtime least privilege, ingress hardening,
complete dependency/security review, audit browsing and deployment are later work.

## Next milestone gate

Milestone 6 is complete. Milestone 7 — Redis — requires explicit user confirmation.
Pick a concrete cache/rate-limit use case with keys, TTL, invalidation and failure
behavior; do not cache permission-dependent search merely to add technology.
