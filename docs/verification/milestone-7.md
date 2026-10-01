# Milestone 7 — bounded published workflow Redis cache

## Delivered

Cache-aside for authorized published-version GETs only, immutable tenant-scoped
keys, bounded typed JSON, atomic expiry with jitter, nonblocking invalidation,
PostgreSQL fallback and monotonic failure cooldown. Defaults opt-in outside the
loopback dev launcher. Compose adds digest-pinned authenticated, unprivileged,
read-only, cache-only Redis 7.4.11 with 128-MiB allkeys-lru and no disk persistence.
No new migration, broker, frontend, CI or deployment milestone.

## Executed verification

All work ran in the agent Linux sandbox; the user was not asked to run it locally.
Restored the M6 source checkpoint and checked GitHub main against canonical commit
`db33224a980299b8256d01582ccffd01feb8474e` before editing. Java 21.0.12, Maven 3.8.4,
Spring Boot 3.5.16, Docker 25.0.16, Compose 5.5.1, PostgreSQL 17.11, Redis 7.4.11.

- `mvn -B -f backend/pom.xml verify`: **191 tests**, zero failures/errors/skips;
  executable JAR packaged. 81 unit, 106 real HTTP/PostgreSQL/Redis, four standalone
  PostgreSQL upgrade/index tests. All M1–M6 tests remain passing with cache disabled
  by default and no additional Redis requirement for their application contexts.
- M7 adds **29 unit tests**: namespace/scoping, disabled/no-I/O, optional misses,
  cooldown/recovery, atomic value/TTL/jitter, UTF-8 write bounds, read/write/UNLINK
  failure handling, property validation, typed codec/header checks, malformed/
  duplicate/unknown/trailing/fractional data, step invariants, oversize/depth checks,
  reader hit/miss/draft/invalid/primary-DB-failure paths and client option preservation.
- **17 new real HTTP/Redis/PostgreSQL tests**: cold fill/warm omission of step
  hydration while fresh header remains, expiry/non-sliding TTL, draft edit freshness,
  publication UUID isolation, genuine audit failure rollback, archived definition,
  revoked/suspended/foreign access, malformed/foreign/oversized/wrong-type values,
  forged display policy vs database-backed submit, warm-cache source failure,
  paused-server fallback/recovery, wrong credentials, six simultaneous cold fills,
  observed memory eviction and noeviction/OOM rejection without failing business reads.
- `bash scripts/test-db.sh`: **83 PASS checks** with fresh migrations, validation,
  repeated no-op, SQL integrity, checksum rejection, transactional DDL rollback and
  disposable DB cleanup. V1–V8 byte-identical to M6 archive; no clean/repair or
  historical migration edits. Main DB validates through V8 and contains no users,
  organizations, requests, audit rows or receipts from verification.
- Packaged JAR + actual Compose dependencies: **32 smoke assertions**, including
  authenticated Redis and NOAUTH without credentials, signup/RBAC/workflow,
  draft bypass, no publication-time cache fill, TTL, stable cache response,
  fresh grant revocation, malformed value rebuild, forged-display submit safety,
  paused Redis fallback twice, recovery without restart, expiry, archive gate and
  eviction/persistence settings. Only generated fixture keys were UNLINKed and
  the entire disposable business DB was dropped in finally; no broad FLUSHALL.
- Compose PostgreSQL/Redis healthy; Redis maxmemory=134217728, allkeys-lru, save
  empty and appendonly=no verified. Redis server runs as Redis user on a read-only
  root filesystem with all capabilities dropped. Password enters private tmpfs
  config, not the server command line. No app/UI Compose container is claimed.
- Scaffold/local Markdown links, shell syntax, Python syntax, Git whitespace and
  resolved Compose configuration passed. Java touched files formatted AOSP.

## Repeat verification

Five sensitive scenarios passed **three additional fresh PostgreSQL/Redis runs**:
paused-server fallback/recovery, concurrent cold fills, permission revocation,
publication audit rollback and memory write rejection. All 15 repeated executions
passed, zero failures/errors/skips. These are repeat consistency checks, not load
benchmarks. Each run removed its own disposable containers.

## Review findings and fixes

The first HTTP test failed to compile because its UUID idempotency helper hid the
statically imported cache-key builder; renamed the helper, leaving app code intact.
A Mockito stub on the transactional audit proxy triggered its MANDATORY guard;
replaced it with a real PostgreSQL audit-insert rejection trigger in the disposable
test DB. Publication rolls back and Redis stays empty; the guard was not weakened.
Tests restore/drop the injected failure trigger in finally.

Review found a Lettuce ClientOptions override could drop Boot's socket timeout.
The final customizer explicitly preserves configured connect/command durations,
rejects disconnected queues, caps per-connection request queue at 256 and keeps
reconnection enabled. Unit assertions inspect actual option values; outage tests
exercise the final implementation. Timeout settings are not an HTTP latency SLA.

Wrong-type/oversized entries are removed with one-key Lua TYPE/STRLEN and UNLINK
before transfer. JSON size/depth/type/step checks and encoder validation skip
unsupported cache shapes. UNLINK avoids blocking Redis while freeing a large wrong-
type aggregate. Cache failures log only a safe exception class, once per cooldown.

## What this does and does not prove

Repository spy assertions show a warm read omits ordered-step hydration while
current authorization, active-definition and version-header SQL still execute.
This is a verified query-path change, **not a production speedup/load benchmark**.
Small policies may not benefit after network/validation costs; measure M19 before
expanding the cache. No cache-hit-rate/latency/capacity or availability SLA claim.

Redis contains display configuration, not authority. Envelope checks do not
cryptographically authenticate forged but well-shaped fields. A dedicated test
poisons a matching published body, then confirms submit still uses the original
PostgreSQL step/eligible reviewer. Protect Redis with private networking, credentials,
production ACL/TLS and incident handling. Local password auth is not a full production
least-privilege/TLS or dependency-vulnerability review. Redis 7.4 licensing needs
commercial-deployment review independently of this repository's MIT license.

Published versions are immutable; new versions get new keys. No mutable latest-key
invalidation race, negative caching or write-transaction warmup. Drafts, grants,
sessions, auth counters, request state/search and commands stay PostgreSQL-backed.
Archive/revocation gates stay fresh; an in-flight read can still finish on its
existing REPEATABLE_READ snapshot, as before. PostgreSQL down means 503, not cached
unauthorized service. Losing Redis raises DB load; fallback overload and distributed
stampedes are not solved. Simultaneous cold fills may duplicate work safely.

The cooldown is local/simple backoff, not a full distributed single-probe breaker.
DNS/TLS/reconnection and DB work can exceed configured command durations. Metrics
and tracing remain M15; Redis topology/managed service choices remain M18. allkeys-lru
is approximate and appropriate only for reconstructible cache data, not durable jobs,
locks or authorization state. No Redis auth-rate-limit/session migration this step.

## Important files and next gate

- `cache/WorkflowCacheProperties`, `WorkflowCacheConfiguration`, `RedisPolicyCache`,
  `WorkflowCacheCodec` and `workflow/PublishedWorkflowReader`.
- Targeted WorkflowService version-read and WorkflowRepository header/steps split.
- `application.yml`, `.env.example`, Compose and development launcher wiring.
- [Cache contract](../cache/workflow-policy.md), [ADR 009](../decisions/009-published-policy-cache.md),
  [LLD](../architecture/lld.md), [system design](../architecture/system-design.md).

Milestone 7 is complete. Milestone 8 —
asynchronous processing — requires explicit user approval; no outbox/broker/worker
implementation has started.
