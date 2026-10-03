# Observability and failure runbook

## Signals

GateFlow exposes unauthenticated, detail-free `/actuator/health/liveness` and `/actuator/health/readiness` probes. `/actuator/prometheus` remains authenticated. Request IDs continue through API errors and logs; never add email, session token, payload, or request title as a metric label.

Monitor HTTP rate/error/latency, Hikari active/pending connections, JVM pressure, Redis cache errors, oldest unpublished outbox age, RabbitMQ ready/unacked counts, notification job age, SMTP outcomes, and workflow conflict rate. Alert on symptoms, not single-instance restarts.

## Initial alert policy

- page: readiness unavailable for 5 minutes in two consecutive windows;
- page: oldest outbox or email job age exceeds 15 minutes;
- ticket: p95 API latency above 750 ms for 15 minutes at meaningful traffic;
- ticket: 5xx ratio above 2% for 10 minutes;
- ticket: dead-letter count increases or DB pool pending requests persist.

These are starting thresholds, not proven SLOs. Revisit after production-like load tests.

## Triage

1. Capture request ID, deployment revision, health state, and dependency saturation.
2. For DB failures, stop retries that amplify load; verify pool and primary health.
3. For broker failure, leave committed outbox rows intact and restore the broker before reviewed redrive.
4. For SMTP unknown outcomes, do not blindly resend; reconcile the quarantined attempt.
5. For Redis failure, expect PostgreSQL fallback and elevated DB reads; correctness must remain intact.
