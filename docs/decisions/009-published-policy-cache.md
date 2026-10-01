# ADR 009 — cache immutable published policy reads, not authority

Status: implemented in Milestone 7.

## Context

Published workflow configuration is immutable and reused by viewers. An authorized
policy GET otherwise hydrates ordered step rows every time. Mutable inboxes and
permissions have much harder invalidation/security requirements. Redis must earn
its role rather than cache every endpoint.

## Decision

Add Spring Data Redis/Lettuce and a narrowly scoped cache-aside reader. PostgreSQL
always authorizes and loads active definition/current header; only the ordered
published VersionView body is cached. Commands remain fully PostgreSQL-backed.
Drafts never cache. One-key tenant/definition/version namespace, typed bounded JSON,
atomic SET+TTL, 10-minute expiry with jitter, dedicated allkeys-lru memory bound.
No persistence, distributed locks, negative cache or `latest` key.

Immutable versions avoid edit invalidation races; new publication means new UUID.
Lazy reads cannot warm rolled-back publications. Archive/permission changes are
fresh DB gates. Malformed/oversized/wrong-type values are nonblocking-invalidated
and rebuilt; metadata/body validation is explicit, not native Java deserialization.

Redis failures use bounded timeouts, reject disconnected command queues, simple
monotonic cooldown and DB fallback. Never serve without current source authorization.
The feature is opt-in outside the loopback dev launcher. A cache outage is degraded
DB capacity, not a guarantee of unchanged latency or unlimited availability.

## Alternatives/trade-offs

- Cache RBAC or request search: harder security/pagination freshness, rejected now.
- Move sessions/rate limits: changes security failure semantics; existing shared PG
  limiter remains. A cache eviction cannot reset login counters or restore sessions.
- In-process cache: simpler, but per-instance memory/expiry/warmup; Redis demonstrates
  a shared bounded cache across replicas without changing application authority.
- Spring @Cacheable on the service: could accidentally skip authorization. Explicit
  reader keeps the current DB gate visible and testable.
- Eager after-publish warmup: adds transaction/event coordination for little benefit.
- Distributed lock: redundant fills of immutable values are safe; lock expiry/
  ownership failures add complexity. Revisit only after measuring stampedes.
- Cache all header SQL: saves another query but weakens archive/status/source gates.

Redis stores a display response, not truth. Matching identity/revision does not
cryptographically authenticate every cached field. Tests poison a structurally valid
body and verify submit still uses original PostgreSQL policy. Private network,
credentials/ACL/TLS and incident handling remain important.

[Detailed contract](../cache/workflow-policy.md) specifies keys, TTL, eviction,
invalidation, outage behavior and limits. [Verification](../verification/milestone-7.md)
records executed results; no production performance gain is claimed.
