# Ten-minute deep dive

## 0–1 minute: requirements

Purchases/software access/policy exceptions; tenant boundaries; sequential conditional
approval; explicit decisions; auditability; retry safety. No payments or access provisioning.
Discuss non-functional goals as targets until measured.

## 1–3 minutes: architecture and request flow

Browser -> same-origin API -> authentication -> current membership/permission/resource
checks -> business service -> PostgreSQL transaction. Controllers adapt HTTP; services
own invariants; persistence/provider adapters isolate infrastructure. Explain why one
modular monolith is simpler than distributed transactions for this stage.

## 3–5 minutes: database and concurrency

Separate identity, tenant membership, roles/grants, policy versions/configuration steps,
request execution steps/decisions, receipts, audit, outbox, and delivery state. Immutable
policy versions keep old requests reproducible. Use current request versions for commands,
not policy/step versions. Per-request row locking serializes state changes; transaction-scoped
idempotency-key locking serializes identical intents. Foreign keys, uniqueness and tenant
predicates complement application authorization. Two concrete races: approve vs withdraw,
and duplicate approvals with same/different keys. Explain one business winner and rollback.

## 5–6 minutes: APIs and security

Opaque hashed cookie sessions, BCrypt, CSRF for writes, bounded validated JSON, sanitized
ProblemDetail/request IDs and hidden-resource 404 semantics. Version conflicts are 409;
first submissions are 201 and authorized replays 200. UI role visibility is advisory;
server checks remain authoritative after revocation. Keep Swagger internal and authenticated.

## 6–7 minutes: cache

Cache-aside for published policy bodies, explicit TTL/invalidation and DB fallback.
Read current authorization outside the cache. Redis failure increases DB work; it does
not authorize a request or change execution policy. Do not claim a measured cache speedup.

## 7–8 minutes: queues and notifications

Transactional outbox prevents a DB commit/broker-publish gap. Publisher confirms, durable
retry/DLQ handling and idempotent consumers provide at-least-once delivery with deduplicated
projections. Independent activity and notification subscribers avoid coupling consumers.
SMTP uncertainty is quarantined rather than blindly resent. Recipient access and preferences
are rechecked before delivery; generic emails avoid leaking business details.

## 8–9 minutes: observability and scale

Use logs/request IDs, health, metrics and worker/outbox signals to distinguish ingress,
DB pool/query, cache, queue, and provider failures. Scale stateless API/worker instances
when measurements justify it. Watch DB contention and connections before adding replicas
or services. Scale readers/relays/consumers independently without changing domain ownership.
An HA broker and tested recovery are distinct from running one container locally.

## 9–10 minutes: verification and limitations

Explain unit, HTTP/database, security, controlled concurrency and browser tests separately.
Mock-browser checks prove UI behavior, not real backend integration. The new disposable
Compose CI check uses real HTTP/services. Existing load evidence is a transport smoke,
not the mixed product workload. Discuss privilege separation, external email verification,
restore/failover, deployment revision and runtime CI results as explicit release gates.
