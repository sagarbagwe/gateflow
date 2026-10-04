# Authenticated business workload — Milestone 19

## Tested product revision

Backend source/application revision: `0163c5be439dbbfba1d3bdb1c622bb72228395c1`.
[CI run 37140464784](https://github.com/sagarbagwe/gateflow/actions/runs/37140464784)
passed the backend/frontend/container jobs. The final workload used its checksum-verified JAR,
not a mocked API or the public Vercel website.

## Method and workload

Agent-only disposable Compose stack: two logical CPUs, approximately 4.2 GB system memory,
Linux 6.18.49, Docker 25.0.16, Compose 5.5.1, k6 1.3.0. PostgreSQL, Redis, RabbitMQ,
Mailpit, Nginx and Java shared this host. This is not a dedicated benchmark machine.

`scripts/performance-business.js` uses two scoped user sessions and a real published policy.
Nine read-only iterations (search, reviewer inbox, request details) precede one
submit-and-approve lifecycle. This is an **iteration mix**, not an HTTP request-percentage mix:
writes also fetch CSRF tokens. Every request stays on loopback, redirects are disabled,
and the private session fixture is never committed or artifact-uploaded.

Baseline and patched runs are 60 seconds; outage probes are 10 seconds. Latency is measured
by k6 HTTP timings for business requests, excluding CSRF requests from the custom business
trend. Functional checks assert successful responses and completed approval state.
Readiness, account/policy provisioning, and browser checks happen outside the timed workload.
Thresholds (<1% business errors and p95 <750 ms) are smoke gates, not contractual SLAs.

## Measured evidence

| Raw summary | VUs | HTTP requests | HTTP req/s | Business p95 ms | Business errors / samples |
| --- | ---: | ---: | ---: | ---: | ---: |
| k6-business-baseline.json | 5 | 1831 | 30.42 | 23.96 | 0 / 1551 |
| k6-business-ten-vu.json | 10 | 3711 | 61.66 | 19.70 | 0 / 3141 |
| k6-business-patched-final.json | 10 | 3678 | 61.09 | 23.87 | 0 / 3112 |
| k6-redis-outage.json | 1 | 59 | 5.88 | 18.40 | 0 / 51 |
| k6-broker-outage.json | 1 | 60 | 5.93 | 12.73 | 0 / 52 |

The final patched run completed 3678 HTTP requests with no observed business
errors. Named-check totals were independently reconciled against the k6 aggregate summary.
Raw summaries and their hashes are in [evidence](../verification/evidence/hardening/).

**Do not present differences between these runs as an optimization win.** Dataset size,
cache/JVM warmth and background work changed between runs. The final restore drill counted
721 requests across all disposable tenants; earlier runs had fewer rows. The inbox was mostly
empty because each write lifecycle immediately approved its request. A ten-VU, one-minute
loopback workload does not establish production capacity, long-term stability, tail behavior
under saturation or performance at 10x traffic.

## Query and N+1 review

The representative tenant-scoped administrator query returned 21 rows in 0.172 ms after ANALYZE
on the earlier 143-request fixture. Its plan used `ix_requests_org_created`; the existing active-step
join had no matching active rows for approved requests. The GIN text index need not be chosen
when the common term matches most rows. Do not force indexes based on this small fixture.

`RequestSearchRepository.search` maps one joined JDBC query into page summaries, rather than
loading each row's step through lazy JPA access. This rules out that specific list-query N+1
pattern by inspection, not all possible application N+1 behavior. The operator SQL probe matches
the repository's admin-search shape but does not invoke its Java builder; the existing integration
EXPLAIN test does. Older large-cardinality index checks remain historical evidence.

No measured bottleneck justified a new query/cache optimization in this revision. We fixed
security dependencies and added reproducible measurement rather than fabricating before/after
speedups. Next staging work: realistic multi-tenant distribution, dense reviewer inboxes,
separate load generator, warm-up/repeated identical fixtures, concurrent client identities,
resource/queue-age telemetry, saturation and soak tests, and targeted optimization only if needed.
