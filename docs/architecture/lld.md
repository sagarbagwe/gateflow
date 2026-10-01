# Low-level design — authentication slice

This document describes implemented M3 classes, not the future workflow engine.
Expand it with subsequent modules. No generic factories or event bus are added
just to demonstrate patterns.

## Components and responsibilities

| Component | Responsibility |
| --- | --- |
| AuthController | HTTP DTO validation, status codes, cookies/CSRF event lifecycle |
| AuthService | Credential policy, normalization, generic failure, registration orchestration |
| UserRepository / UserAccount | JPA persistence of identity; explicit lower(email) lookup matches unique-index expression |
| SessionService | Hashed-token persistence, active lookup, atomic rotation/revocation |
| TokenCodec | SecureRandom generation and SHA-256 hashing; malformed-token bounds |
| AuthCookies | Host-only session-cookie construction/read/clear; rejects ambiguous duplicate cookies |
| AuthRateLimiter | Atomic shared fixed-window IP/account counters, conservative retry duration |
| SessionAuthenticationFilter | Restore principal from DB; never authenticate on storage failure |
| AuthRateLimitFilter | Pre-hash IP throttle and fail-closed storage handling |
| SecurityConfig | Default authentication gate, header-only CSRF, encoder and explicit security-context strategy |
| ApiExceptionHandler / Problems | Sanitized RFC 9457 errors from controllers and filters |
| RequestIdFilter | Server-generated correlation ID, safe structured request fields |
| AuthStorageCleanup | Bounded best-effort expiry cleanup, with eligibility rechecked on deletion |

## Interfaces, dependency injection, and patterns

- **Repository:** UserRepository uses Spring Data JPA. Session/limiter operations
  use explicit JdbcTemplate SQL for atomic predicates/upserts rather than pretending
  an ORM read-modify-write cycle is concurrency-safe.
- **Strategy:** PasswordEncoder isolates hashing choice; BCrypt cost 12 is current
  policy. CsrfTokenRepository is a framework cookie strategy.
- **Dependency injection:** constructors receive repositories, services, properties,
  encoder, Problems, and TransactionTemplate. No controller constructs domain services.
- **Transaction boundary:** signup hashes first, then TransactionTemplate groups
  user insertion and session creation. SessionService.create joins it; on login it
  owns its short @Transactional rotation block. Limiter writes are separate so
  failed credentials cannot roll back abuse counters.
- **Middleware:** a filter chain composes request IDs, CSRF, limiting, session
  restoration, and final authentication checks. Filters are created only in the
  security chain, not also servlet-auto-registered.

These are concrete uses of single responsibility, interface boundaries, and
composition. No factory, inheritance hierarchy, or observer mechanism is needed
for this slice; domain events/notification strategies belong to later milestones.

## Security-context lifecycle

The database session is restored each request. NullSecurityContextRepository avoids
servlet context persistence, and servlet session-management strategies are disabled
so session restoration does not trigger new-login CSRF clearing. SecurityContextHolder
is cleared by the framework after each request. Actual login/signup/logout clear
CSRF through AuthController. A regression test verifies read and validation-error
stability; missing/wrong/headerless CSRF still fails.

## Data handling

Credential DTOs and internal auth results have redacted toString methods. UserPrincipal
contains no password/session token. Password encoding occurs before SQL; only token
hashes reach session storage. IDs and safe route categories are logged, not request
bodies, query strings, email, cookies, or arbitrary unmatched URL text. Raw ORM SQL
errors/bind logs are disabled to avoid leaking duplicate-key values. This sacrifices
some raw diagnostics; future observability must add safe SQLSTATE/error-class signals.

## Alternatives and failure cases

A standard Spring Session JDBC implementation is viable but trades explicit token
hash storage/lifecycle for framework-managed session storage. JWT verification
reduces DB reads but complicates revocation. Current storage outages produce 503
rather than accepting an unchecked identity or dropping rate protection. Absolute
session expiry simplifies revocation semantics at the cost of re-login UX.

## Scaling boundary

Shared PostgreSQL sessions and atomic limits work across backend replicas, unlike
in-memory maps. They also add DB traffic, hashed-key cardinality, and cleanup work.
No throughput benchmark is claimed. Redis evaluation must preserve revocation and
outage behavior; a stale cached ACTIVE identity must not silently undo deactivation.
