# Audit evidence and integrity

## Contract
Each security/business mutation records who (USER plus tenant membership, or SYSTEM),
what (action), when (database timestamp), resource (type and UUID), old/new selected
evidence and request correlation ID. Creation has null oldValue. Rollbacks produce
no audit; replaying a successful idempotent command does not append another event.
TenantAuditWriter requires an existing transaction. No SMTP/network call is coupled
to that transaction. Workflow edits now capture the actual persisted step IDs,
positions, approver roles and structured conditions before and after editing, not
merely a step count. Request histories deliberately exclude descriptions/comments.

AuditData restricts producer fields recursively, limits depth to 6, arrays to 100,
strings to 2048 UTF-8 bytes and serialized new evidence to 60000 bytes per snapshot.
Unexpected keys/bounds fail the write and roll back the business transaction.
This is a trusted-producer field allowlist, not semantic PII detection: workflow
names are allowed business labels and must not be populated with secrets.
Passwords, session/token hashes, email addresses and free-form request details are
not approved evidence fields. Legacy reads omit unknown keys and flag redaction.

V11 bounds each new JSONB old/new snapshot to 65536 bytes in PostgreSQL. NOT VALID
checks deliberately preserve old append-only evidence without rescanning/rejecting
legacy oversize rows; they DO apply to every new insert. They remain unvalidated for
historical data. Detail reads bound historical JSON transport; metadata lists avoid
JSON hydration. Actor/action tenant-time indexes support common investigations,
with existing tenant/resource/time indexes retained. Index maintenance costs writes;
no unmeasured latency or full arbitrary-filter optimization claim is made.

## Trust boundary and retention
Existing database triggers reject ordinary UPDATE, DELETE and TRUNCATE. There are
no application audit mutation endpoints. This is append-only for normal application
flows, NOT cryptographically tamper-proof against database owners/superusers, backups
or a compromised migration account. Production deployment still needs separate
least-privilege runtime/migration credentials, restricted backups and admin access.

No automatic purge or retention duration is invented. Before production, select a
policy with the business/legal owner, design controlled archive and privileged
maintenance tooling, verify recovery and access logging. Legal hold, hash chains,
external SIEM integration and regulatory certification are not implemented.
