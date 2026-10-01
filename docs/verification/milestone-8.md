# Milestone 8 — transactional events and asynchronous activity

## Delivered scope

One useful background feature: eventually consistent, authorized request activity.
Submit/approve/reject/withdraw/reassign record one immutable event in their existing
business/audit/command-receipt transaction. A short leased PostgreSQL relay publishes
persistent event references using RabbitMQ mandatory routing and correlated confirms.
The worker commits consumer deduplication and projection atomically before manual
ACK. Processing retries use three delayed queues, then a confirmed dead-letter
handoff; handoff failure keeps the original delivery recoverable.

This is at-least-once delivery with a deduplicated PostgreSQL side effect, **not
exactly-once messaging**. No email, notification inbox/preferences, frontend, CI,
cloud deployment or M9 work is claimed. Activity is not the full audit-log browser.

## Executed verification

All execution ran in the agent Linux sandbox; the user was not asked to install or
run anything locally. Restored the M7 source checkpoint, then verified GitHub main
at canonical `d81fbf3390b45635650ecacd12eb1ce7c8872f81` before editing and again
before publication. Java 21.0.12, Maven 3.8.4, Spring Boot 3.5.16, Docker 25.0.16,
Compose 5.5.1, PostgreSQL 17.11, Redis 7.4.11 and RabbitMQ 4.2.9.

- `mvn -B -f backend/pom.xml verify`: **227 tests**, zero failures/errors/skips;
  executable Spring Boot JAR packaged. **97 unit, 125 real HTTP/PostgreSQL/Redis/
  RabbitMQ, five standalone PostgreSQL migration/index tests**. All 191 M1–M7
  baseline tests still pass. Transport defaults disabled in old application tests;
  they do not acquire a RabbitMQ dependency to verify unrelated features.
- **16 new unit tests:** bounded strict references, schema/identity/header rejection,
  duration/lease configuration, capped backoff, confirm ACK/NACK/timeout handling,
  relay mark-after-confirm and failure retry, commit-before-ACK call order, lost
  ACK without a second retry publication, runtime processing failure, poison DLQ,
  confirm-before-ACK retry handoff, and failed-handoff NACK/channel close.
- **19 new real HTTP/PostgreSQL/RabbitMQ tests:** matching idempotency creates one
  event; all five event types/versions; outbox insert failure rolls back business,
  audit and command receipt; expired/reclaimed leases reject stale marker tokens;
  accepted publish/lost marker yields a safe duplicate; six concurrent consumer
  duplicates commit one projection; failed projection rolls back its dedup receipt;
  delayed retry recovery; persistent failure reaches DLQ after four attempts and
  explicit redrive succeeds; malformed/unknown references have no side effects;
  missing binding leaves outbox unpublished; paused broker does not block a valid
  business commit and later recovers; uncommitted events are invisible to relay;
  outsider/suspended/hidden access and pagination bounds; out-of-order events sort
  without mutating request state; real listener dispatch; competing distinct claims;
  failed DLQ handoff retains original; **physical** channel loss after projection
  commit redelivers safely; confirmed pending message survives broker app restart.
- **One new standalone V8→V9 upgrade test:** populated legacy rows preserved,
  historical migration checksums unchanged, nine versioned history entries,
  repeated migrate a no-op, exactly 20 application tables and no invented events.
- `bash scripts/test-db.sh`: **102 PASS checks** including fresh V1–V9, validation,
  repeat/no-op, tenant FKs, unique source versions, paired/fenced lease metadata,
  immutable event content/published metadata, append-only receipts/activity,
  checksum-mismatch rejection and transactional failed-migration rollback.
  Every fixture transaction rolls back; its entire generated database is dropped.
- V1–V8 byte-identical to the canonical M7 archive. Main development DB migrated
  and validated through V9, with zero users/requests/outbox fixture rows.
- **63 packaged API/database assertions** against the actual executable JAR and
  Compose dependencies, including HTTP status checks during readiness polling:
  automatic scheduling/listener startup, CSRF/signup/RBAC/published workflow,
  submit/replay identity and one event, minimal eventual timeline, tenant concealment,
  validation, approval and descending version order, durable consumer receipts,
  real broker pause with successful source commit and temporarily empty activity,
  automatic recovery without app restart, broker application restart/reconnection,
  four final source events/projections, and nine packaged-app migrations.
  Used a dedicated temporary broker vhost and disposable business database; both
  were removed in finally, and the temporary application process was stopped.
  No development queues, dedup receipts or business data were broadly reset.
- PostgreSQL, Redis and RabbitMQ Compose services healthy. RabbitMQ image is
  digest-pinned, AMQP port loopback-only, authenticated and named-volume persistent;
  no management web UI exposed. Queues use explicit durable quorum/overflow/TTL/
  dead-letter arguments. A single local broker node is **not an HA deployment**.
- Static scaffold/local Markdown links, shell/Python syntax, Git whitespace,
  Compose configuration, touched Java formatting and private-secret exclusion checks
  passed. No backend/frontend Dockerfiles or active CI are implied by dependency Compose.

## Repeat verification

Five failure-sensitive scenarios passed **three additional fresh PostgreSQL/
RabbitMQ runs**: broker outage recovery, projection rollback/delayed retry, failed
DLQ handoff preservation, physical ACK-loss redelivery, and concurrent duplicates.
All **15 repeat executions** passed, zero failures/errors/skips. These are repeat
correctness checks, not production-load, latency or availability benchmarks.

## Review findings and fixes

Initial compilation caught an ambiguous Queue import, the PageSlice package, an
AMQP message converter import and a generic-header assertion overload; corrected
before testing. Runtime processing exceptions now use bounded retries instead of
escaping the handoff path. Relay success counts include only fenced marker updates.

An added crash test initially used Spring's cached-channel logical close, which did
not simulate physical connection loss. It was fixed to close the target channel;
real redelivery and broker restart then passed. The legacy-upgrade fixture was
corrected to use actual schema columns and a valid fixture hash length. The SQL
truncate check explicitly includes dependent tables with CASCADE so append-only
triggers are exercised rather than only the PostgreSQL FK preflight rejection.

Packaged smoke expectations were corrected to existing product rules: software
access requires softwareName, submit replay returns 200 rather than 201, and
unknown/inactive organizations are concealed with 404, not 403. Application security
and validation were not weakened to accommodate test inputs; final smoke passed.
The API documentation was corrected to the same concealment contract.

## Important limitations / next milestone boundary

- Broker confirms do not mean worker completion. Duplicate and out-of-order
  deliveries are expected; timeline can lag authoritative state.
- Main/retry/DLQ bounds can backpressure transport. Outbox storage/receipt retention
  remains unbounded; monitored backlog/retention policy is needed before production.
- No historical backfill, public replay endpoint, notification providers/preferences,
  metrics dashboards or production capacity claim. Operator redrive is documented.
- Queue argument changes require an explicit versioned topology/drain plan.
- Production TLS, restricted broker/database runtime identities, multi-node broker
  operation, cloud recovery, dependency scanning and full observability remain
  security/cloud/observability milestones. Local credentials are not a production
  secret-management design.

[Delivery/configuration/runbook](../async/event-delivery.md),
[Activity API](../api/activity.md), [ADR 010](../decisions/010-transactional-request-events.md).
