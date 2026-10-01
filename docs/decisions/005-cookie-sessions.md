# ADR 005: Opaque database sessions for browser authentication

Status: implemented in Milestone 3. This is authentication, not organization RBAC.

## Context and decision

GateFlow initially serves one authenticated browser UI. Choose a random 256-bit
opaque session token in a host-only HTTP-only cookie. PostgreSQL stores only its
SHA-256 hash, user reference, creation/expiry/revocation times. Session TTL defaults
to an absolute 12 hours (configuration bound: 1 second through 24 hours). There is
no sliding refresh, JWT, or refresh-token endpoint: expiry requires login again.

Passwords use BCrypt cost 12. Validate 12–64 Java string code units and at most
72 UTF-8 bytes at signup so BCrypt does not silently truncate long Unicode input.
Passwords are not trimmed or lowercased. Email is normalized separately. The cost
is a named policy constant, not a throughput claim; benchmark before changing it.
Argon2id is a viable memory-hard alternative but needs its provider/deployment
memory budget; PasswordEncoder keeps the encoding decision replaceable.

## Lifecycle

- Signup hashes before the short atomic user/session transaction, returns 201,
  and signs the new user in. No organization or permission grant is created.
- Login verifies credentials, executes a dummy hash check for unknown identities,
  rechecks active status during session creation, rotates the presented session,
  and returns 200. A failed login does not revoke an existing session.
- Logout is CSRF-protected and idempotent (204), revokes the presented token, and
  expires its browser cookie. Only that session is revoked, not all devices.
- Each authenticated request looks up an unexpired/unrevoked hash and ACTIVE user.
  Deactivation/revocation is effective on subsequent lookups; already-running
  requests are not retroactively cancelled.
- Expired/revoked sessions and limiter rows are removed in bounded hourly batches.
  Cleanup is best-effort, not a strict retention guarantee under sustained overload.

## Cookie and CSRF boundary

`__Host-GATEFLOW_SESSION` in secure mode: HTTP-only, Secure, SameSite=Lax,
Path=/, no Domain. The browser host prefix prevents Domain-cookie injection.
Local HTTP uses `GATEFLOW_SESSION` without the prefix.
The development launcher explicitly binds loopback and disables Secure only for
local HTTP. Production needs TLS and must retain the secure default.

A separate `__Host-XSRF-TOKEN` cookie (`XSRF-TOKEN` for local HTTP) is intentionally
JavaScript-readable. Obtain its
current value/header name from GET `/api/v1/auth/csrf`; state-changing requests
must send `X-XSRF-TOKEN`. Form parameters cannot substitute for the header. The
CSRF cookie is cleared on successful signup/login/logout; fetch a fresh one afterward.
No arbitrary cross-origin credential access/CORS configuration is enabled.

The application restores database sessions without servlet HttpSession storage:
use NullSecurityContextRepository and disable servlet session-management strategies.
Restoring an existing opaque session must not count as a fresh authentication
and repeatedly clear CSRF. The controller explicitly handles rotation at actual
authentication events. Regression tests verify token stability across reads and
CSRF reuse across validation failures. No JSESSIONID session is used.

## Rate limiting and failures

Before expensive hashing: shared PostgreSQL fixed-window IP limits (signup 5/min,
login 10/min) and normalized-account login limit (30/min). Atomic upserts count
concurrent attempts, including failed credentials. Account-counter writes happen
outside the credential/session write transaction so failures cannot roll them back.
Retry-After conservatively reports a full configured window. Limit storage failure
returns 503, not a fail-open bypass. Expired-window counters reset atomically.

Hashing IP/account bucket keys is pseudonymization, not anonymization; low-entropy
inputs can be guessed. Limits can produce false positives behind shared NAT. The
server ignores Forwarded/X-Forwarded-For headers; a production reverse proxy needs
an explicitly vetted trusted-client-address design. Fixed windows allow boundary
bursts, and distributed abuse of many accounts still needs ingress protection.
Redis/token-bucket evaluation follows at M7; no distributed lock is needed here.

## Alternatives and trade-offs

- JWT access/refresh tokens offer local verification, but immediate revocation,
  refresh reuse detection, signing-key rotation, and stale privilege handling add
  complexity not needed for this first browser product.
- Spring Session JDBC is a viable framework-managed alternative. Explicit hashed
  token rows avoid serializing security context objects and expose lifecycle rules,
  but require more custom security code and thorough tests.
- In-memory sessions/limiters lose continuity and consistency across replicas.
- Database lookups add one query per authenticated request and make authentication
  unavailable during database outages. This is an intentional correctness trade-off,
  not evidence of measured scalability.

## Remaining work

Email ownership verification, recovery/password change and all-device revocation,
MFA/SSO, trusted ingress policies, runtime DB privilege separation, dependency
security review, and public-deployment hardening are not implemented. Signup 409
still reveals account existence indirectly; generic wording is not full
anti-enumeration protection. Unknown-account dummy hashing is not a timing-proof
claim. Organization membership/resource authorization remains M4.
