# Request events and asynchronous activity

## Why this job exists

The activity timeline is the first genuine background read projection: approved
requests remain authoritative immediately, while lifecycle history arrives shortly
later. It exercises durable delivery without introducing email or notification
preferences ahead of M9. This is not the full audit-log browser.

## Commit and delivery contract

1. A submit, approve, reject, withdraw or reviewer reassignment transaction writes
   business state, security audit, one `outbox_events` row and command receipt.
   Outbox append requires an existing transaction. Failure rolls everything back;
   a matching idempotency replay does not rerun the command or produce another event.
2. A relay claims **one event at a time**, in a short PostgreSQL transaction using
   `FOR UPDATE SKIP LOCKED`. UUID leases expire using database time. No database
   lock is held during broker I/O. Lease-token fencing protects marker updates
   against a stale relay; it cannot prevent duplicate network publication.
3. Publish persistent JSON with mandatory routing and correlated publisher confirms.
   An ACK without a returned message is required before marking `published_at`.
   A published marker means broker acceptance, **not worker completion**.
4. The worker strictly decodes the reference, loads the immutable PostgreSQL event,
   and atomically inserts `(activity_projection_v1,event_id)` into `processed_events`
   plus the timeline row. A concurrent duplicate blocks on the unique index until
   the first transaction commits/rolls back; it never bypasses a failed projection.
5. Manual ACK happens **after** the Spring transaction proxy returns. Lost ACKs
   redeliver safely. Failed processing is handed off to a confirmed retry/DLQ
   publication before ACKing the original. Failed handoff NACKs/requeues the
   original and closes the channel; the listener reconnects.

Delivery is **at least once**, with a deduplicated PostgreSQL side effect—not
exactly-once messaging. Uncertain confirms and lease expiry can cause duplicates.
Database unavailability delays both relay and projection; broker unavailability
must not fail an otherwise valid business command. Application scheduling and
connection recovery retry independently. No distributed transaction is used.

## Schema and trust

The body is only `{"schema":1,"eventId":"<canonical UUID>"}` (≤256 bytes).
`messageId` must match; `x-gf-attempt` is an integer 0–3. Strict field/version/type,
UUID, duplicate-field, depth and trailing-data checks reject invalid messages.
No JSON polymorphism or Java object deserialization is used. Tenant, actor, state,
step and request version come from the authoritative database, not broker headers.
Headers on retry/DLQ publication are rebuilt from a small whitelist; failure
reasons are enum-like codes, never exception text or original business payloads.
The broker is a trusted internal infrastructure boundary with restricted access;
a forged valid reference can cause early projection, not invent new business state.

Events: REQUEST_SUBMITTED, STEP_APPROVED, REQUEST_REJECTED, REQUEST_WITHDRAWN,
REVIEWER_REASSIGNED. Draft edits and policy publication do not emit these events.
V9 adds no fake/backfilled timeline for pre-M8 requests. Events are retained so
consumers can resolve references and operators can redrive old deliveries.

## Topology

| Exchange / queue | Purpose |
| --- | --- |
| gateflow.workflow.events.v1 / request.changed.v1 | Original domain-event routing |
| gateflow.activity.v1 | Main activity quorum queue |
| gateflow.activity.retry.v1 / retry.1–3 | Activity-only retry routing |
| gateflow.activity.v1.retry.1–3 | TTL-delayed quorum queues |
| gateflow.activity.work.v1 / activity | Retry return to this worker only |
| gateflow.activity.dead.v1 / dead | Terminal dead-letter exchange and quorum queue |

Retry TTLs default to 5, 30 and 120 seconds: initial attempt + three retries = four
processing attempts. Unknown event/reference or malformed data goes straight to
DLQ. Unexpected runtime processing failures use the same bounded retry policy.
TTL expiry is a minimum delay, not a precise scheduler. Domain events are not
republished to the shared exchange on retry, avoiding future notification
subscribers receiving activity retries.

All queues are durable quorum queues, bounded to 10,000 ready messages / 16 MiB,
using `reject-publish`, not silent drop-head. Main and retry queues use quorum
**at-least-once dead lettering**, which retains the message until target acceptance.
The main queue has delivery-limit 50 for repeated infrastructural requeues; its
terminal DLX is the same DLQ. DLQ saturation can backpressure upstream queues.
Prefetch is 1, with 1–2 consumers per process. One-node local Compose is durable
but **not highly available**. A tested multi-node quorum deployment and restricted
runtime identities belong to cloud/security work; no production HA claim is made.

## Defaults and configuration

`ASYNC_ENABLED=false` outside the development launcher. Events are always recorded
in PostgreSQL; disabled transport accumulates pending work. The dev launcher
resolves private broker credentials and enables transport by default.
`ASYNC_RELAY_ENABLED` and `ASYNC_CONSUMER_ENABLED` independently control roles when
transport is enabled; both default true. The API can run with both false, and a
separate deployment of the same artifact can enable worker roles. There is still
one modular codebase, not fabricated microservices.

Validated internal properties: batch size 20 (max 100), poll 1s, confirm timeout 2s,
lease 30s, producer retry base 1s/max 1m with capped exponential backoff and positive
jitter. Confirm timeout ≤10s; lease exceeds it by ≥2s. Producer retries do not have a
terminal attempt limit: preserve committed work during prolonged outages. Queues
bound broker memory, but **outbox growth remains unbounded** until retention and
backlog limits are designed. Rabbit connection/channel timeouts and heartbeat are
configured. OS/network failures can still exceed a desired end-to-end latency SLO.

Environment: RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST and RABBITMQ_TLS_ENABLED.
Use encrypted transport, a private network and managed secrets in production.
Local .env variables initialize only fresh RabbitMQ storage; changing them does
not rotate persisted credentials. Queue arguments are immutable: changing TTLs,
limits or types requires an explicit versioned topology/drain plan, not blindly
redeclaring an existing queue.

## Recovery runbook

- Check database connectivity, pending-event count/oldest timestamp, expired leases,
  attempt counts and sanitized last_failure. Fix connection/routing before replay.
- Restore the broker and bindings: due events retry automatically; crashed relay
  claims become eligible after lease expiry. Do not clear published_at; protected
  history is deliberate. Do not assume queue-empty means all transactions committed.
- For DLQ: diagnose the reason, fix the bug/data/infrastructure, inspect one delivery,
  and republish a valid original event reference to **activity.work.v1 / activity**
  with the same messageId and attempt 0, persistent delivery and publisher confirms.
  ACK/remove the DLQ delivery only after confirmed acceptance. Existing successful
  projections safely deduplicate. Invalid/unknown references need investigation,
  not endless replay. There is no public redrive endpoint in M8.
- Never drop production queues or reset consumer receipts to recover. Application
  history guards reject routine UPDATE/DELETE/TRUNCATE. A future retention job needs
  explicit elevated maintenance policy that coordinates event and dedup retention.
- Monitor oldest pending age, eligible/leased backlog, retry/DLQ depth, database
  projection failures and time since last progress. Current logs use sanitized
  deferred reason codes; full metrics/health/alerts are **M15**, not implemented here.

## Ordering, scale and alternatives

Competing relays/consumers may deliver out of order. This append-only projection
orders by request_version, never writes current request state, and does not depend
on FIFO. A future ordered notification/escalation consumer needs its own sequencing
policy. Do not claim the architecture already solves that requirement.

SKIP LOCKED permits independent relay progress without a global distributed lock.
The pending partial B-tree bounds candidate lookup for ordinary workloads; locked
rows/backlog can cause extra scanning. Batch result memory is O(1), one leased
reference at a time. Dedup index operations are typically O(log E); E retained
receipts. Payload validation/copy is bounded O(B), B≤256. Backoff computation is
O(1) time/space using a capped shift. These are genuine scheduling/index decisions,
not custom in-memory heaps or invented interview algorithms.

A PostgreSQL-only job queue is simpler operationally and would suit this initial
scale. RabbitMQ earns its place through durable acknowledgments, bounded routing,
consumer isolation, backpressure and future notification handoff. Kafka would add
retained-stream/partition complexity we do not need yet. Sending after commit
without an outbox loses the database-to-broker crash window; holding a DB transaction
open during broker I/O cannot make two systems atomic and increases lock duration.
