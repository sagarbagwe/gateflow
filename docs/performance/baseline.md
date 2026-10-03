# Performance baseline plan

No unsupported throughput claim is made. The existing query-plan checks remain structural evidence only.

## Workloads

Measure login throttling separately from authenticated traffic. Primary scenario: 70% paginated request reads, 15% reviewer inbox, 10% submissions, 5% decisions. Secondary scenarios isolate full-text search, published-policy cache cold/warm behavior, and outbox recovery after a broker outage.

Run against staging-sized PostgreSQL, Redis, and RabbitMQ with seeded tenants and realistic cardinality. Record p50/p95/p99 latency, error/conflict rate, CPU, heap/GC, Hikari wait, query time, cache hit rate, outbox age, queue depth, and DB IOPS. Warm up for 5 minutes, measure for 15, repeat three times, and retain raw output with revision and environment metadata.

## Acceptance gates

At 50 virtual users, target p95 read latency below 500 ms, p95 commands below 750 ms, error rate below 1% excluding expected 409 conflicts, no connection-pool exhaustion, and outbox age returning below 30 seconds within five minutes after recovery. Change one bottleneck at a time and record before/after evidence.
