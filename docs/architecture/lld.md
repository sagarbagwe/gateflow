# Low-level design — authentication and RBAC

This document describes implemented M3/M4 classes, not the future workflow engine.
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

## M4 organization/RBAC slice

| Component | Responsibility |
| --- | --- |
| RbacController / RbacDtos | Typed validated commands and bounded admin directories |
| OrganizationService | Atomic bootstrap, creator quota, active-membership organization access |
| RoleService | Custom role lifecycle, protected defaults, safe grant delegation |
| MembershipService | Administrative enrollment, role/status transitions, last-admin invariant |
| AuthorizationService | Live tenant membership/grant resolution; recheck after mutation lock |
| RolePolicy / Permission | Explicit default matrix, set union, delegation/admin/version policy |
| OrganizationRepository | Tenant reads/lock and creator quota locking |
| RoleRepository | Tenant-scoped joins grouped into bounded role views; grant replacements |
| MembershipRepository | Tenant-scoped directory and assignments, conditional version updates |
| TenantAuditWriter | Same-transaction security evidence, allowed snapshots from trusted services |
| PageSlice | Defensive immutable bounded list and hasMore metadata |
| RbacProperties | Validated quota configuration |

Controllers perform no policy decisions. Constructor injection separates HTTP,
policy orchestration, and SQL persistence. JDBC repositories are concrete because
there is one storage implementation; additional repository interfaces/factories
would be unnecessary abstraction. Pure RolePolicy functions are directly testable;
authorization collaborators are Mockito-isolated in lock/recheck tests.

The set union is genuine algorithmic work: hash/enum-set membership avoids repeated
permission scans. For P total assigned grant entries and U unique permissions,
union takes O(P) time and O(U) space (currently U <= 12). Grouped join extraction
uses a LinkedHashMap keyed by role ID, O(J) processing/O(R+P) storage for J returned
rows, R roles and P grants, retaining deterministic role order without N+1 reads.
These are modest administrative data structures, not claims of high-scale capacity.

Mutations combine organization serialization with expected row-version checks:
the lock protects cross-membership last-admin invariants, whereas versions protect
stale client intent. Failures throw domain errors before commit; mandatory audit
participation prevents security state without corresponding success evidence.
[ADR 006](../decisions/006-organization-rbac.md) records alternatives and limits.
