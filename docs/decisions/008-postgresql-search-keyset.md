# ADR 008 — PostgreSQL search and bounded request navigation

Status: implemented in Milestone 6.

## Problem

People need their own request history, admins need organization-wide filtering,
and reviewers need only work they can act on. Filtering after fetching would
produce empty/inconsistent pages and leak visibility through counts. Unbounded
lists and deep offsets would be expensive.

## Decision

Use one PostgreSQL JDBC summary query with shared SQL visibility applied before
pagination. Use EXISTS for reviewer history so multiple decisions cannot duplicate
requests. Inbox drives active-step joins with required-role checks. One unique
ACTIVE step per request bounds join cardinality; no per-row hydration queries.

A generated stored tsvector plus GIN indexes title/description with simple
plainto_tsquery semantics. PostgreSQL is already authoritative and updates vectors
transactionally. No Elasticsearch/OpenSearch, synchronization lag, extension, or
second deployment is justified now. Simple token semantics trade stemming/ranking
for predictable multilingual literal matching.

Default keyset navigation uses immutable creation timestamp plus UUID, newest or
oldest first. Bound limit to 100 and fetch limit+1 rather than an expensive COUNT.
Keep explicit offset mode capped at 10,000 for small admin navigation. No arbitrary
sort: mutable title/state ordering would need different indexes/cursor contracts.
No Redis caching yet: permission-dependent search must remain fresh.

Cursors are unsigned and context-bound for accidental reuse detection, not
cryptographically protected. All authority stays in current membership/grants and
SQL. A creation cutoff limits new-insert drift but is not a multi-page MVCC snapshot.
A signed token would add key rotation/configuration without improving authorization
for this nonprivileged navigation state; revisit if semantics become privileged.

## Alternatives and consequences

- Offset-only: simpler page numbers but work grows with skipped rows and shifting
  data. Retained only as an explicitly bounded alternative.
- UUID alone: deterministic but random UUIDs do not preserve temporal ordering.
- Timestamp alone: ties cause omissions/duplicates; tuple order resolves them.
- ILIKE `%term%`: substring behavior and likely scans, unlike token-AND semantics.
- External search: useful for advanced ranking/fuzzy search or larger isolated
  workloads, not a free scalability upgrade; permission filtering stays mandatory.
- In-memory filtering/cache: unsafe pagination and stale access, rejected.

GIN adds write/storage/vacuum costs. Statistics, term selectivity and pending-list
maintenance affect planner choices. Actual seeded EXPLAIN tests demonstrate the
rare-term GIN plan and organization/tuple B-tree plans after normal bulk-load
vacuum; they are not production latency, saturation or capacity benchmarks.

V7 stored-column creation backfills existing terminal/draft rows without changing
business state. Normal transactional DDL/index creation can block a populated table;
plan a maintenance window and disk/lock-budget rehearsal before production use.
Old migrations remain immutable; no clean/repair/trigger disabling.

V8 additively protects the UUID coordinate too; an imported draft without child
references could otherwise change its ID through direct SQL. V7 remains unchanged.
