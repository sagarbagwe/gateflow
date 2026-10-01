# Milestone 5 verification

Execution date: 2026-10-01. Scope: sequential core approval workflows only.
All implementation, compilation and testing ran in the agent environment; the
user did not need to install anything or execute commands locally.

## Implemented

- Tenant-scoped workflow definitions; bounded draft steps; optimistic policy edits;
  explicit publication and immutable version binding.
- Typed ALWAYS / currency-bound inclusive PURCHASE_AMOUNT_AT_LEAST conditions.
- Direct transactional submission with current non-requester reviewer eligibility;
  ordered ACTIVE/WAITING/SKIPPED execution instances.
- Approval advancement, rejection/cancellation, owner withdrawal, terminal guards.
- Explicit authorized/audited pending reviewer reassignment and version handling.
- Shared organization authorization locks + per-request aggregate locks + durable
  actor/tenant/key-scoped idempotency receipts with canonical SHA-256 intent hashes.
- Same-transaction decisions/audit/receipts; rollback and fail-closed storage errors.
- Resource-specific visibility and current-role checks, including decision history.
- Strict bounded DTOs, NUL rejection and actual 256 KiB synchronous JSON body bound.
- V6 additive migration and explicit REQUEST_REASSIGN grant only for protected ADMIN.

No search/inbox filters, cache, queue/outbox, notifications, frontend or cloud
hosting was added. Approval does not execute payments or provision software access.

## Environment

Corretto Java 21; Maven 3.8.4; Spring Boot 3.5.16; PostgreSQL 17.11;
Docker Engine 25.0.16; verified Compose v5.5.1; Flyway 13.8.1. PostgreSQL and
Flyway image digests remain pinned. The workspace had restarted; committed M1–M4
sources were restored from saved GitHub patches and tooling reinstalled here.

## Full build and regression suite

`mvn -B -f backend/pom.xml verify`: **106 tests passed**; zero failures/errors/skips.
Executable Spring Boot JAR packaging passed. Prior authentication/RBAC coverage
remains active rather than being skipped or mocked out for this milestone.

| Suite | Tests | Evidence |
| --- | --- | --- |
| AuthServiceTest | 4 | Credential policy unit tests |
| AuthenticationIntegrationTest | 19 | Real HTTP + PostgreSQL auth/security lifecycle |
| PasswordValidationTest | 3 | Input policy |
| StorageFailureFilterTest | 3 | Fail-closed filters |
| TokenAndCookieTest | 5 | Hash/cookie/security handling |
| AuthorizationServiceTest | 2 | Authorization reload and outsider lock denial |
| RolePolicyTest | 8 | RBAC policy/immutable bounded views |
| RbacIntegrationTest | 20 | Real HTTP + PostgreSQL tenant/admin safeguards |
| WorkflowPolicyTest | 14 | Conditions/payload policy, currency fail-closed, decimal canonicalization |
| WorkflowIntegrationTest | 27 | Real HTTP + PostgreSQL business/security/rollback/races |
| WorkflowMigrationTest | 1 | Actual populated V5 -> V6 upgrade and checksum validation |

Classification: 39 unit tests, 66 real HTTP/PostgreSQL tests, one standalone
PostgreSQL upgrade test. These are counts, not a claim of 100% code coverage.

New core coverage exercises sequential approval, rejection, withdrawal, immutable
publication, new-policy versus in-flight binding, stale draft edits, live approver
role validation, financial conditions/threshold boundaries/currency mismatch,
software/policy payload rules, missing reviewers/no applicable step, self-approval,
assignment/role independence, tenant/resource isolation, explicit reviewer recovery,
unavailable-next rollback, idempotency replay/conflict/authorization/scoping,
invalid headers/foreign steps/stale versions, audit storage rollback/retry, safe
audit snapshots, known-length/chunked body bounds, NUL text and restricted reviewer
visibility/history. Pure policy tests reject unsatisfiable mixed-currency policy.

## Concurrency repeat evidence

Six scenarios passed in the full suite and **three additional fresh-container
repeat runs** (18 additional executions, zero failures/errors/skips):

1. Same-key identical submissions -> one aggregate/receipt, 201 + replay 200.
2. Same-key identical decisions -> one decision/version advance, both 200.
3. Different-key competing decisions -> one winner and one 409.
4. Approval vs withdrawal -> one consistent terminal outcome and one conflict.
5. Publication vs draft replacement -> no mixed published step configuration.
6. Command waiting on RBAC lock -> rechecks committed revocation before mutation.

Parallel clients use independent session/cookie stores. The revocation test
observes a real PostgreSQL lock wait before committing the fixture revocation,
not an arbitrary sleep assumption. These checks cover named interleavings;
they are not load benchmarks or an exhaustive proof of all possible schedules.

## Database verification

`bash scripts/test-db.sh`: **77 checks passed** (72 SQL assertions plus six migration
lifecycle checks). Fresh V1–V6 application, checksum validation, repeat no-op,
core/auth/RBAC/ledger constraints, copied-checksum rejection, copied next-migration
transaction rollback and post-failure validation passed. Test DB cleanup succeeded.

New checks cover negative policy versions, at most one ACTIVE step, illegal/terminal
state changes, immutable step provenance/terminal behavior, scoped receipt uniqueness
and FKs, hash/operation constraints and append-only UPDATE/DELETE/TRUNCATE guards.
V1–V5 source bytes were checked unchanged against committed patch content.

The standalone upgrade test migrates V1–V5, creates existing protected and custom
ADMIN-named roles, then applies V6 and validates/no-ops it. Protected admin gains
the new permission; a legacy/custom name does not. No automatic migration repair.

Main GateFlow DB V1–V6 are applied/validated separately. No test users, organizations,
requests, decisions, receipts or audit fixtures are installed there. All disposable
business fixture DBs/containers were removed, rather than bypassing immutable-row
triggers for cleanup.

## Executable JAR smoke

**39 assertions passed**, plus successful entire-fixture-database cleanup. Started
the actual packaged server, applied six migrations and drove real cookie/CSRF HTTP:

- five generated fixture signups; organization and business role/member setup;
- typed manager/finance policy draft/publication and immutable-edit rejection;
- purchase submission and single active step;
- submit retry and decision retry without duplicate effects;
- admin permission alone cannot bypass pinned assignment;
- manager -> finance -> APPROVED terminal outcome;
- low-value finance skip and approval;
- rejection cancels future review; requester withdrawal;
- suspension -> audited reassignment -> recovered approval;
- five distinct request aggregates, five immutable decisions, twelve receipts,
  and zero remaining ACTIVE execution steps.

Fixture passwords/session tokens were generated/held in memory, not printed or
committed. The executable server was stopped before dropping its fixture DB.
This proves sandbox startup/API behavior, not a hosted demo or persistent service.

## Static checks and fixes

Scaffold/local Markdown links, shell syntax, Git whitespace and real Compose
configuration passed. Java was formatted with Google Java Format (AOSP style).

The first workflow run found validation error classification changed when header
constraints trigger Spring method-level body validation. Added a separate sanitized
body-vs-parameter handler rather than weakening constraints. Review also added
canonical numeric hashes, NUL checks, bounded actual-body reads (including chunked),
a compatible submitted-to-draft SQLSTATE guard, empty-legacy-condition semantics,
and safe nullable old-assignee audit data for recovery.

## Limits and next milestone gate

Deterministic reviewer ordering is not workload balancing; overlapping eligible
roles may select the same person for multiple steps. There is no distinct-human
or parallel-quorum policy. Receipts have no automatic expiry/retention workflow.
Default read snapshots can overlap concurrent revocation already in flight; new
commands reauthorize after the tenant lock. Global account disabling requires a
future compatible locking/recovery design. Database owners can bypass API policy
and change/drop safeguards; production runtime least privilege is not configured.

Existing populated databases need preflight/lock planning for V6 ordinary DDL and
unique-index creation. No production load/SLA/capacity or complete dependency/
security-review claim is made. Deployment ingress/timeouts, comprehensive audit
browsing/retention, event delivery, notification providers and frontend remain
later milestones. OpenAPI remains M12.

Milestone 6 — Search and Filtering — requires explicit user confirmation.
