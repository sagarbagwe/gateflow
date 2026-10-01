# Milestone 4 verification

Execution date: 2026-10-01. Scope: organization-scoped RBAC only.

## Environment and recovery

Java 21 (Corretto), Maven 3.8.4, Spring Boot 3.5.16, PostgreSQL 17.11,
Docker 25.0.16, Compose v5.5.1, Flyway 13.8.1. PostgreSQL/Flyway image digests
remain pinned. The workspace restarted during setup; the committed source was
restored from GitHub commit patches in order, without changing repository visibility
or asking the user to execute anything. Java/Docker tooling was restored here.

All tests ran in the agent environment. No user-side local execution was required.

## Implemented scope

Organization bootstrap and listing; four protected default roles; separate
permission catalog and custom roles; administrative existing-user enrollment;
membership role/status changes; scoped authorization and safe delegation;
last-active-admin protection; conditional row versions; quotas/bounded directories;
allowed-field transactional security audits; V5 non-destructive metadata migration.

V1–V4 sources were not edited. No workflow execution, invitations/email delivery,
notification broker, cache, frontend, audit browsing API, or deployment was added.

## Final full backend suite

`mvn -B -f backend/pom.xml verify`: **64 tests passed**, zero failures/errors/skips.
The executable Spring Boot JAR was packaged successfully.

| Suite | Tests | Result |
| --- | --- | --- |
| AuthServiceTest | 4 | Passed |
| AuthenticationIntegrationTest | 19 | Passed against real HTTP/PostgreSQL |
| PasswordValidationTest | 3 | Passed |
| StorageFailureFilterTest | 3 | Passed |
| TokenAndCookieTest | 5 | Passed |
| AuthorizationServiceTest | 2 | Passed; privileges reloaded after mutation lock |
| RolePolicyTest | 8 | Passed |
| RbacIntegrationTest | 20 | Passed against real HTTP/PostgreSQL |

The 30 new tests cover default grants, organization/bootstrap integrity,
foreign tenant/resource IDs, spoofed headers, distinct roles across organizations,
custom role versioning, protected defaults/codes, delegation ceilings, custom-all
versus protected ADMIN, existing-session revocation, tenant-local suspension,
reactivation constraints, stale versions, last-admin invariants, simultaneous
admin demotions, same-version competing membership updates, audit actor/request ID,
audit-storage rollback, failed enrollment/slug atomicity, bounded pagination,
invalid JSON/enum/null grants, CSRF, quotas, disabled identities/organizations,
and legacy named-role non-promotion.

Both concurrent-admin-demotion and same-membership-version tests additionally
passed **three repeat runs**, each against a fresh PostgreSQL container: six
additional executions, zero failures/errors/skips. Two live parallel operations
produce one winner and a domain conflict. This is not a throughput/load benchmark
or an exhaustive proof of all interleavings.

## Database suite

`bash scripts/test-db.sh`: **64 checks passed** (58 SQL assertions + 6 migration
lifecycle checks). Fresh V1–V5 application, validation, repeat no-op, core/auth/RBAC
constraints, copied checksum rejection, intentionally invalid copied next migration
rollback, and post-failure original validation passed. New assertions cover legacy
role identity, negative role/member versions, invalid system-role code, creator FK.

Disposable test databases were dropped; no Flyway clean/repair or audit-trigger
bypass was used for fixture cleanup. Main GateFlow DB migrations are applied and
validated separately; no demo user/organization is installed there.

## Packaged application smoke

Started the executable JAR against another disposable PostgreSQL database.
**18 assertions passed**, followed by successful database cleanup:

- startup applies five migrations;
- two real signups, organization bootstrap, four default roles;
- outsider denial, custom role creation and member enrollment;
- current grants, role-management denial, versioned permission revocation;
- immediate revocation on the existing session;
- suspension, tenant denial, preserved global authentication;
- stale-version and CSRF rejection;
- exactly five committed security audit records.

Smoke fixture credentials were generated in memory and not printed or committed.
The fixture DB was dropped after stopping the JAR. This is a sandbox startup check,
not a hosted demo or a guarantee of persistent service availability.

## Static checks and corrections

Scaffold/local documentation links, shell syntax, Git whitespace, and real Compose
configuration passed. RBAC Java was formatted with Google Java Format (AOSP style).

An initial test expected CSRF_INVALID, while M3's deliberate contract is
ACCESS_DENIED; corrected the assertion without weakening CSRF. Review also found
that null set elements and numeric enum ordinals needed explicit rejection; added
container-element validation and strict Jackson enum handling with HTTP tests.

## Limits and next gate

Authorization is not cached: current DB grants are authoritative for subsequent
requests; a read already in flight may overlap a concurrent revocation. Mutation
paths reauthorize after acquiring their transaction lock. Per-org RBAC writes
serialize; no measured high-scale claim is made. No global tenant-bypass role.

Default/system-role protection and last-admin checks cover the implemented API,
not a privileged DB-owner bypass or a future global account-disabling command.
Enrollment is by known existing UUID, not consent-based invitations. Production
least-privilege DB roles, recovery/invitations, ingress hardening, comprehensive
audit coverage/retention, OpenAPI, and dependency/security review remain later work.

Milestone 5 (core sequential workflow) requires explicit user confirmation.
