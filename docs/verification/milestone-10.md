# Milestone 10 — protected audit investigation and bounded evidence

## Delivered
Tenant-scoped AUDIT_VIEW list/detail APIs with exact actor/action/resource/request-ID
filters, half-open time ranges, bounded pagination and metadata-only lists. Fresh
membership/permission checks, foreign-entry concealment and legacy snapshot redaction.
Producer field/depth/array/text/byte bounds apply to atomic audit writes. Workflow
policy edits capture actual persisted before/after steps and conditions.

V11 adds new-write database JSONB size checks and actor/action investigation indexes,
without changing historical evidence or adding tables. Existing UPDATE/DELETE/TRUNCATE
triggers remain; no application audit write endpoints. This is ordinary append-only
evidence, not cryptographic protection against database owners. No purge job/export,
legal-hold system, SIEM or regulatory certification is claimed.

## Executed verification
Built entirely here, not on the user's computer. Baseline GitHub main:
`e7110147b780e24b260efb786995df307ebe6479` (M9).

- Full `mvn -B -f backend/pom.xml verify`: **290 tests**, zero failures/errors/skips;
  executable JAR packaged. All 270 previous tests passed, plus 8 audit unit tests
  and 12 real HTTP/PostgreSQL audit cases.
- Subsequent `-Dtest=AuditMigrationTest test`: **one additional populated V10→V11
  upgrade test**, passed. Preserved memberships, legacy oversize audit snapshot,
  historical checksums and 24-table count. Validation/repeated migrate passed;
  new oversize writes rejected, NOT VALID legacy policy explicitly checked.
  **291 distinct Maven tests verified** (290 full-suite + one new focused upgrade).
- Focused audit suite also passed all 20 cases before full regression. Covers grants,
  tenant isolation, suspension/revocation, filters/bounds, old/new decisions, actual
  step policy diffs, nested legacy secret redaction, unsupported mutations,
  duplicate-command single audit, anonymous access, producer/reader bounds.
- `bash scripts/test-db.sh`: **128 PASS checks**. Fresh V1–V11, validation, repeat
  no-op, audit new/old size constraints/indexes, append-only histories, checksum
  rejection, failed DDL/history atomic rollback and final validation. Disposable
  database removed and fixtures rolled back.
- Actual packaged executable JAR against Compose PostgreSQL/Redis/RabbitMQ/Mailpit:
  **102 assertions**, all passed. Privileged filtered metadata feed,
  exact decision detail, correlation ID, denial/concealment/query bounds/no deletion,
  replay without duplicate audit; existing automatic outbox/activity/notification/
  SMTP and broker outage/restart recovery still pass. No external mailbox contacted.
  Disposable DB/vhost and captured fixture emails removed, application stopped.
- Main development schema migrated and validated through V11. No product fixture
  identities or business data inserted. V1–V10 byte-identical to M9 source checkpoint.
- Supported Java 21 formatter, scaffold/relative links, shell syntax, Compose config,
  whitespace and private-environment credential scan checked before commit.

## Important files
`audit/AuditController.java`, `AuditQuery.java`, `AuditService.java`, `AuditData.java`;
`rbac/TenantAuditWriter.java`; `workflow/WorkflowService.java`;
`V11__harden_audit_evidence.sql`; audit unit/integration/upgrade tests;
[API](../api/audit.md), [evidence/trust boundary](../audit/evidence.md),
[decision](../decisions/012-audit-evidence-and-privileged-browsing.md).

## Scope and trade-offs
Tenant-wide investigation is a privileged role capability, not ordinary request
visibility. No grant caching. Offset pages can drift, max offset 10000; no consistent
bulk export. Historical oversize rows remain untouched/unvalidated; detail omits them
with a flag. Indexes help common actor/action filters but no unmeasured speed claim.
Retention and least-privilege production DB deployment still require separate review.
Milestone 11 starts only after this verification; Milestone 12 is not included.
