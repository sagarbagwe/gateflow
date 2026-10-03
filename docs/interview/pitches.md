# Product explanations

## 30 seconds

GateFlow is a multi-tenant approval platform for purchases, software access, and
policy exceptions. Requests bind to immutable workflow versions, and only eligible
reviewers can approve the active step. The backend uses transactional state changes,
idempotency receipts, audit evidence, and a durable outbox. Redis accelerates published
policy reads; RabbitMQ decouples activity and notifications. The interesting part is
correct decisions under retries, concurrency, permission changes, and dependency failures.

## 2 minutes

The product solves approvals disappearing into email or chat. An organization defines
an ordered policy, including conditional financial review, and publishes an immutable
version. Submission pins that version and selects eligible reviewers. Approval advances
the active step; rejection or withdrawal ends the request. Invalid transitions, stale
versions, self-approval, cross-tenant access, and duplicate commands are rejected or
safely replayed rather than treated as generic record updates.

I chose a Spring Boot modular monolith and PostgreSQL because request, step, receipt,
audit, and outbox changes benefit from one transactional boundary. Roles and permissions
are separate, and authorization is checked from current membership rather than trusted
UI state. Redis is a reconstructible published-policy cache, not a source of decision
authority. RabbitMQ delivers durable work at least once; consumers deduplicate database
projections and retry failures. Email has an explicit unknown-outcome state because SMTP
acceptance and the database cannot commit atomically.

The React interface makes those rules accessible: bounded lists, explicit filters,
versioned admin changes, audit evidence, notification preferences, and stable retry
keys. A lost HTTP response must not generate a new business intent on the next click.

The engineering evidence includes historical backend/security/database/concurrency
verification and newer frontend and mocked-browser regressions. Deployment headers,
real authenticated runtime checks, least-privilege credentials, restoration drills,
and representative performance are separate release gates. I would explain the
limitations directly rather than claim production scale or exactly-once delivery.
