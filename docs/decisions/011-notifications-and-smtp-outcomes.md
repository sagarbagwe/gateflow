# ADR 011: Independent notifications and conservative SMTP recovery

Status: implemented in Milestone 9.

## Decision

Subscribe independently to committed request references. Atomically create consumer
receipts, at most two eligible recipient rows and opted-in email jobs. Reuse the
reference ACK/retry/DLQ handler; retries return to this subscriber only. Inbox and
email preflight never trust cached permissions or stale assignment. Personal
preferences are organization scoped, version guarded and atomically audited.

Use a provider EmailSender interface and one actual SMTP adapter. PostgreSQL leases
schedule delivery; do not call SMTP inside command/projection transactions. Record
attempt outcomes with token-fenced completion. Known pre-acceptance failures retry
with a cap; ambiguous sends/crashed leases quarantine UNKNOWN. SMTP acceptance is
not mailbox delivery and deterministic Message-ID is not deduplication support.
A real provider idempotency/status API would enable stronger automated recovery.

## Alternatives / trade-offs

Calling SMTP during approval/projection couples provider failure to business state.
Retrying every timeout can duplicate mail. Blindly reclaiming an expired external
send lease has the same problem. Holding DB locks during SMTP only adds contention;
it does not make the provider and database atomic. A second Rabbit email job queue
adds topology without fixing that uncertainty, so durable DB email jobs suffice.
Webhooks/SMS/provider factories are not implemented to pad the architecture.

Local Mailpit captures real SMTP without sending to a live mailbox. Required TLS
and disabled transport defaults are separate from explicit loopback-dev settings.
Verified addresses, production provider/domain setup, retention/fairness, terminal
reconciliation tooling and metrics are outstanding—not claims hidden by a green test.

[Full behavior/failure/configuration contract](../notifications/delivery.md).
