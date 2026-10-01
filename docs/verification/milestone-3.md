# Milestone 3 verification

Execution date: 2026-10-01. Implementation and all verification ran in the agent's
Linux sandbox. No user-side execution was requested.

## Scope and runtime

Implemented authentication slice: signup/login/logout/current user/CSRF endpoints,
BCrypt password hashing, hashed opaque sessions, cookie/CSRF protection, shared
PostgreSQL rate limits, validation, safe HTTP errors/request IDs/logging, auth storage
migration, bounded expiry cleanup, and build/run/test tooling.

Java Corretto 21.0.12, Maven 3.8.4, Spring Boot 3.5.16, Flyway OSS 13.8.1,
PostgreSQL 17.11, Docker 25.0.16, Docker Compose v5.5.1. PostgreSQL/Flyway images
remain digest-pinned. Maven targets Java 21; verification actually used Java 21,
not merely a newer JDK compiling with release=21.

## Java build and tests

Command: `bash scripts/test-backend.sh` (Maven verify).

**BUILD SUCCESS: 34 JUnit tests, zero failures, zero errors, zero skipped.**

| Class | Count | Type |
| --- | --- | --- |
| AuthServiceTest | 4 | Mockito unit orchestration/policy |
| PasswordValidationTest | 3 | Validation/redaction/configuration unit |
| StorageFailureFilterTest | 3 | Filter fail-closed/error unit |
| TokenAndCookieTest | 5 | Token format/hash and secure-cookie unit |
| AuthenticationIntegrationTest | 19 | Real HTTP server + fresh Testcontainers PostgreSQL |

The integration class runs a real HTTP server and applies all four migrations to
an isolated PostgreSQL container. Docker absence fails rather than skipping tests.
The executable backend JAR is packaged by verify. No coverage percentage is claimed.

### Behaviors exercised

- Signup creates one user/session; password is BCrypt cost 12, token is hashed.
- Case-insensitive login, rotation, old-token rejection, failed-login session preservation.
- Logout cookie expiry, token revocation/replay rejection, and idempotent logout.
- Anonymous, malformed/unknown, expired, and disabled-account sessions rejected.
- CSRF missing/wrong/form-substituted tokens rejected; token remains usable across
  authenticated reads and validation errors; auth transitions clear it explicitly.
- Weak and oversized UTF-8 passwords, invalid email, malformed JSON and extra fields rejected.
- 400/401/403/404/405/409/415/429 behavior, request IDs, sanitized problem responses,
  and preservation of Allow/Retry-After headers.
- IP limit cannot be bypassed by forged forwarded headers; signup invalid-input
  attempts count; expired windows reset; account counts survive failed logins.
- Concurrent account-limit SQL permits exactly the configured number of attempts.
- Mocked session/limiter storage failures produce 503 without calling downstream
  handlers or accepting authentication; this is not a full live database-outage drill.
- Secure production cookies use __Host prefixes; session is HTTP-only and CSRF is
  intentionally readable. Cookie attributes are checked in unit tests; SameSite
  is also checked on the actual HTTP server in local mode.

## Database regression suite

`bash scripts/test-db.sh`: **59 checks passed** (53 SQL assertions + six migration
lifecycle checks). All four migrations apply fresh; validate succeeds; second
migrate is a no-op; checksum tampering is rejected; a copied next-version failure
rolls back DDL/history; original migrations validate afterward. Disposable test
database cleanup succeeded. V1–V3 applied sources were not modified.

Auth additions test token-hash format/uniqueness, user FK, expiry consistency, and
positive limiter counts. Core tenant/workflow/audit integrity checks still pass.

## Packaged application live smoke

Started the executable JAR through the loopback-only development launcher against
GateFlow's running PostgreSQL. **Ten smoke checks passed:** anonymous denial with
request ID, signup/session creation, authenticated access, case-insensitive login,
rotation, old-token rejection, logout 204, post-logout replay denial, anonymous
state afterward, and cleanup of the disposable live account/sessions.

No temporary password/token was committed or printed. Fixture user, session, and
account-specific limiter data were removed; ordinary transient IP limiter counters
may remain until expiry/cleanup. V4 is applied and validated in the GateFlow DB.
The app was successfully started in the sandbox; this is not a public deployment
or a promise of persistent hosted availability.

## Failures found and corrected

1. Corrected JAVA_HOME to the actual Java 21 installation path.
2. Testcontainers required an explicit compatibility marker for the official
   digest-pinned PostgreSQL image reference. No alternative/fake database was used.
3. Updated DB regression helper to derive migration count/next version; fixed a
   Python parenthesis typo and reran the full suite successfully.
4. Hibernate duplicate-key error logging exposed a fixture email. Disabled raw
   SQL/error/bind logging, used terse DB error settings, and confirmed the fixture
   email is absent from final application logs. This is a targeted check, not proof
   that arbitrary future logs are universally safe.
5. Servlet session-management strategies treated database session restoration as a
   new authentication and cleared CSRF on ordinary requests. Disabled those servlet
   strategies, used NullSecurityContextRepository, kept CSRF enabled, and tested
   stable token reuse plus required clearing at actual auth transitions.
6. Mock servlet headers omitted SameSite from their generated cookie header;
   validated the mock cookie attribute and added a real HTTP header check instead
   of weakening cookie settings.

## Remaining limitations

Organization RBAC/resource authorization is M4. No workflow engine, UI, Redis,
broker, OpenAPI, CI, application Dockerfile, or cloud deployment was added. Public
signup needs email verification/recovery/abuse and ingress/body-size policies before
production exposure. MFA/SSO, password changes/all-device revocation, separate
production DB roles, dependency review, live outage/load testing, and full
observability remain future work. Fixed-window/NAT/proxy limits and database-backed
session lookup costs are documented trade-offs, not measured scaling claims.
