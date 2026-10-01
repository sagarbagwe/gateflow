# Milestone 9 — notifications, preferences and durable SMTP delivery

## Delivered

Recipient-owned in-app notification feed, visible unread count and idempotent first
read acknowledgment. Self-service per-organization channel preferences default to
in-app on/email off; optimistic updates atomically audit safe booleans/version.
An independent RabbitMQ subscriber creates eligible notifications and opted-in email
jobs in one receipt/projection transaction. Retries never fan back out to activity.

Actual SMTP adapter behind EmailSender, short fenced PostgreSQL job claims,
provider I/O outside DB transactions, bounded safe retries, immutable attempt history
and durable terminal states. Ambiguous SMTP sends/expired external leases quarantine
UNKNOWN rather than blindly resend. SMTP acceptance is not mailbox delivery;
deterministic Message-ID is not provider idempotency. Local Mailpit captured test
emails; **no external mailbox was contacted**. M10 full audit browsing was not started.

## Environment and baseline

Built and verified entirely in the agent Linux sandbox; no user-local installation
or execution was required. GitHub main matched M8 canonical
`7ab77888ee90f3bf7360570cd2fad7ffc985db45` before changes. Java 21.0.12,
Maven 3.8.4, Spring Boot 3.5.16, Docker 25.0.16, Compose 5.5.1, PostgreSQL 17.11,
Redis 7.4.11, RabbitMQ 4.2.9, Mailpit 1.31.3. GreenMail 2.1.3 is test-scope only.
The Mailpit image was resolved, runtime version checked and pinned by digest.

## Executed checks

- `mvn -B -f backend/pom.xml verify`: **270 tests**, zero failures/errors/skips,
  executable JAR packaged. **108 unit, 151 real HTTP/PostgreSQL/Redis/RabbitMQ/
  Mailpit, six standalone PostgreSQL migration/index tests and five real SMTP
  adapter/classification checks**. All 227 M1–M8 tests remain passing, including
  M8 physical ACK loss/restart/rollback behavior after extracting the shared handler.
- **26 new HTTP/PostgreSQL/RabbitMQ/Mailpit cases:** default owner/current-reviewer
  targets with no email; opted-in actual SMTP and idempotent read/unread behavior;
  both channels off; email-only hidden inbox; versioned strict preferences; recipient
  ownership/tenant concealment/pagination bounds; fresh permission revocation and
  suspension suppress inbox/email; send-time opt-out; old-event stale-action suppression;
  next-step eligibility; reassignment and old-action email suppression; six concurrent
  duplicate events; concurrent email claims; projection and email-job insert rollback;
  isolated delayed generation retry; safe provider retry then acceptance; retry cap;
  permanent and ambiguous outcomes; expired lease quarantine/stale token protection;
  real SMTP acceptance followed by failed DB marker; real notification listener;
  preference audit rollback, concurrent preference winner/audit and CSRF/anonymous gates.
- **11 new unit cases:** property/age/lease bounds, capped jitter, disabled no-I/O,
  send-before-acceptance marker, skip without send, source-failure safe retry,
  retry limit, unexpected provider unknown, DB marker failure after acceptance,
  opaque stable Message-ID/minimal body and whitelisted failure codes.
- **Five SMTP adapter checks:** real GreenMail acceptance with stable Message-ID and
  plain-text privacy, refused connection classification, actual SMTP authentication
  rejection, header-injection rejection before I/O, and uncertain timeout classification.
  All servers use loopback fixtures; GreenMail is not an application dependency.
- **One new populated V9→V10 upgrade case:** old membership and previous checksums
  preserved, no invented preferences/inbox/email rows, 24 application tables,
  validation and repeated migrate no-op. Historical migrations V1–V9 byte-identical
  to the verified M8 checkpoint.
- `bash scripts/test-db.sh`: **125 PASS checks**, including 23 new notification
  defaults/tenant/provenance/uniqueness/read/state/lease/terminal/append-only invariants.
  Fresh V1–V10, repeat/no-op, altered checksum rejection and failed migration atomic
  rollback/revalidation all pass. Fixtures roll back and the entire disposable
  integrity database is dropped. Main development schema migrated/validated through
  V10; verification users/business/notification/jobs were not inserted into it.
- **87 packaged API/database/SMTP assertions** using the actual executable JAR and
  Compose dependencies, including HTTP status checks during polling. Verified
  automatic relay plus both real subscribers and scheduled email worker; signup,
  CSRF/RBAC/policy, submit/replay one source event, activity and private inbox,
  actionable reviewer card, admin/outsider isolation, unread/read idempotency,
  actual captured SMTP body without business/tenant/resource identifiers,
  durable ACCEPTED outcome, opt-out/version conflict/atomic audit, approval ordering,
  broker pause with source commit/eventual catch-up, broker application restart,
  ten packaged migrations and no future email after opt-out.
  The generated broker vhost and business DB were removed in finally; app stopped.
  Only the captured fixture message IDs were deleted, not the whole mail sink.
- **15 additional fresh-container recovery executions:** three runs each of
  concurrent duplicate generation, SMTP acceptance/DB marker failure quarantine,
  preference audit rollback, notification projection/delayed retry and retryable
  provider recovery. Zero failures/errors/skips. These are correctness repeats,
  not load tests or production latency/availability measurements.
- The actual updated development launcher also started the packaged JAR with
  Compose SMTP configuration and returned CSRF bootstrap 200; its temporary process
  was stopped without creating identities or email fixtures.
- Four Compose dependencies healthy. Mailpit runs UID/GID 1000, read-only root,
  dropped capabilities/no-new-privileges, bounded ephemeral tmpfs capture, loopback
  SMTP/API ports, digest-pinned image and no relay configured. Main database contains
  no verification identities/requests/audits/notifications/jobs; temporary fixture
  databases/vhosts and captured email were cleaned up.
- Static scaffold/local Markdown links, shell/Python syntax, touched Java formatting,
  Git whitespace, Compose configuration and source/private-secret exclusion checks
  pass. No frontend, application Dockerfile, CI, live provider or cloud deployment
  claim is inferred from dependency Compose.

## Fixes made during verification

The first compile command used the wrong working directory; corrected the command,
then compilation passed. Extended preference audit testing initially expected an
incorrect error code: existing sanitized 503 SERVICE_UNAVAILABLE is preserved.
The integrity generator's quoting was corrected before applying new assertions;
the cross-tenant email FK fixture was reordered so it tests the FK rather than an
already-existing unique notification job. Packaged receipt checking was scoped to
activity because M9 intentionally adds a second independent consumer receipt.
No security rule was relaxed or production code weakened to satisfy these fixtures.

Review strengthened actionable checks to require current REQUEST_APPROVE and the
required role independently; provider error codes cannot log arbitrary strings.
The shared reference handler preserves confirm-before-ACK and lost-ACK semantics.

## Limits / next milestone boundary

External email stays globally disabled outside explicit configuration and user opt-in.
Signup address ownership is not yet verified: external production delivery must not
be enabled until verification/abuse/provider/domain controls are completed. Fixed
generic plain-text content avoids disclosing request/tenant details to an address.

UNKNOWN/DEAD require operator investigation; no unsafe automatic resend, public
terminal replay API or fake exactly-once guarantee. A crashed pre-send worker can
also become UNKNOWN because durable state cannot prove whether SMTP was reached.
Provision all durable subscriber bindings before expecting new notification coverage;
confirms do not prove every intended binding existed. No pre-M9 event backfill.

Retention, per-tenant fairness/quotas, provider reconciliation tooling, full metrics/
health/alerts, multi-node broker HA, production least privilege and complete dependency
security review remain later work. M10 full audit browsing/retention was not started;
only the necessary preference security event uses the existing audit writer.

[Notification API](../api/notifications.md),
[delivery/configuration/runbook](../notifications/delivery.md),
[ADR 011](../decisions/011-notifications-and-smtp-outcomes.md).
