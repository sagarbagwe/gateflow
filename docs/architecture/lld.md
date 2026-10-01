# Low-level design — authentication, RBAC, and workflows

This document describes implemented M3/M4/M5/M6 classes. Parallel/async workflows remain future work.
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

## M5 workflow/request slice

| Component | Responsibility |
| --- | --- |
| WorkflowController / RequestController | HTTP adaptation, validated commands, replay/status headers |
| WorkflowDtos | Typed bounded immutable projections and explicit state/condition enums |
| WorkflowPolicy | Currency-safe conditions, payload rules, legal lifecycle/version guards |
| WorkflowService / WorkflowRepository | Draft/version/publication policy and tenant SQL |
| RequestService | Aggregate submission, visibility, decision/withdraw/reassign orchestration |
| RequestRepository | Scoped row locking, ordered step/decision projections, guarded writes |
| ReviewerService | Current membership/role/permission/self-approval eligibility and deterministic selection |
| CommandReceipts | Same-transaction key serialization, intent comparison, immutable durable receipt |
| WorkflowJson | Bounded typed payload encoding/decoding and canonical SHA-256 fingerprinting |
| RequestBodyLimitFilter | Actual-byte cap before synchronous JSON deserialization |

Constructor injection separates transport, orchestration, pure policy and SQL.
Repositories use JDBC because conditional transitions and row locks are deliberate
operations, not generic entity CRUD. A Supplier callback lets CommandReceipts wrap
transactional commands without a fake command bus, inheritance tree, or factory.
Mandatory transaction propagation rejects receipt/audit usage outside the caller's
transaction. No observer/event bus is claimed; outbox/events belong to M8.

Conditions are a closed enum: ALWAYS and PURCHASE_AMOUNT_AT_LEAST. Adding condition
kinds requires a deliberate DTO/policy/test change. A Strategy hierarchy would be
premature for two simple cases; provider strategies remain justified for M9.

### Algorithmic thinking grounded in this implementation

For S configured steps and D distinct applicable approver roles, evaluate/order
steps in O(S) time and O(S+D) bookkeeping. A hash map memoizes reviewer selection
within the command so repeated roles require D queries, not S. Eligibility queries
have data-dependent SQL cost (not asserted O(1)); indexes/query plans need later
measurement. Next-step lookup is an ordered O(S) scan; with S <= 50 a linked graph,
heap or workflow compiler is unnecessary. Repeated role membership does not enforce
distinct humans; that would require a separate business rule and algorithm.

Canonical hashing sorts object keys: for K fields, O(K log K) sorting plus O(B)
byte traversal and O(B) temporary storage for B bounded encoded bytes. Normalized
decimal values avoid treating 1000 and 1000.00 as different monetary intent. Lists
retain order; reordering a meaningful command list is not canonicalized away.

[ADR 007](../decisions/007-sequential-workflow-commands.md) explains why, alternatives,
transaction/locking trade-offs, failures and scaling limits.

## M6 search components

| Component | Responsibility |
| --- | --- |
| RequestSearchController | Two read-only HTTP routes; forwards explicit parsed parameters |
| RequestSearchQuery | Immutable whitelist/enum/size/date/pagination validation |
| RequestAccessPolicy | Shared detail/list SQL predicates, scope permissions, current assignment eligibility |
| RequestSearchService | Per-request REPEATABLE_READ, fresh authorization, cursor context, sentinel pagination |
| RequestSearchRepository | Bound SQL values, fixed sort identifiers, one summary hydration query |
| SearchCursor | Versioned bounded serialization, normalized search digest, strict structural checks |
| RequestSearchDtos | Immutable list/summary records; no heavy detail history |

Constructor injection separates HTTP validation, authorization, query construction,
serialization and page assembly. Shared eligibility prevents drift between inbox
and detail. No generalized search DSL/factory/cache interface is necessary.
RequestService uses the shared visibility policy but retains command transactions,
locks, state machine, authorization and reviewer rechecks.

### Genuine algorithm: ordered tuple continuation

Problem: page a changing request history without skipping equal timestamps.
Approach/data structure: lexicographic `(created_at, UUID)` comparison over an
organization-leading PostgreSQL B-tree; append a limit+1 sentinel.
Typical aligned index work is O(log N + K), K returned candidates; complex
visibility/FTS filters can inspect many more candidates and sort matches. This is
not a guarantee that every API call is O(K). Application result memory O(K), K≤101.
Tie-breaker is PostgreSQL UUID ordering, not Java UUID signed-long comparison.
Offset visits preceding candidates O(offset+K) for an aligned traversal and cannot
provide stable deep navigation. New context digest sorting is bounded by five
statuses; no handmade balanced tree or artificial DSA library is added.

## M7 cache boundary

| Class | Responsibility |
| --- | --- |
| WorkflowCacheProperties | Validated opt-in, TTL/cooldown/UTF-8 byte bounds |
| WorkflowCacheConfiguration | Explicit Lettuce disconnected-command rejection |
| RedisPolicyCache | One-key bounded Lua read, atomic SET/TTL, UNLINK, monotonic cooldown |
| WorkflowCacheCodec | Typed bounded JSON, immutable identity/header and step validation |
| PublishedWorkflowReader | Cache-aside for the authorized published-policy GET only |

WorkflowService authorizes and checks the active definition before invoking the
reader. WorkflowRepository separates fresh versionHeader and ordered withSteps;
ordinary version/published methods used by writes still hydrate PostgreSQL data.
Constructor injection makes hit/miss/outage behavior independently testable.
No generic cache framework, Factory or distributed-lock abstraction is necessary.

Native Redis dictionaries provide expected O(1) key lookup; string transfer/JSON/
validation is O(B+S), byte bound B≤65,536 and S≤50 steps. allkeys-lru uses approximate
sampling, not an exact application-owned LRU list. TTL expiry and positive jitter
reduce synchronized refreshes; misses can still duplicate work. No artificial
handwritten LRU or lock is added to demonstrate DSA. Lua TYPE/STRLEN+bounded GET and
UNLINK avoid transferring a huge corrupt value or synchronously deleting a large
wrong-type aggregate. This is a cache-aside pattern, not domain event delivery.

## M8 asynchronous boundaries

| Class | Responsibility |
| --- | --- |
| OutboxRepository | Mandatory command append, short fenced claim/mark/retry transactions |
| OutboxRelay | Bounded scheduled claims, confirmed publication, exponential retry |
| ConfirmedEventPublisher | Correlated confirms, mandatory returns and sanitized transport failures |
| EventReferenceCodec | Strict bounded reference and attempt validation |
| ActivityConsumer | Manual ACK, bounded retry/DLQ handoff and recoverable handoff failure |
| ActivityProjector | Transactional receipt plus authoritative INSERT SELECT projection |
| AsyncConfiguration / AsyncProperties | Durable topology, listener tuning, validated configuration |
| RequestActivityService / Controller | Fresh authorized, bounded eventual timeline read |

Constructor injection keeps command transactions independent of broker transport.
The Spring transaction proxy for ActivityProjector is a separate bean: returning
from process means commit completed before ACK. Local relay overlap prevention uses
a ReentrantLock; cross-process correctness comes from PostgreSQL leases, not that
local lock. Records represent immutable event/claim/response values. This implements
transactional-outbox, repository and event-consumer patterns; no speculative Factory,
universal event bus, distributed lock service or premature email abstraction.

[Delivery contract](../async/event-delivery.md) explains why/how/alternatives/failure/
scale and genuine bounded scheduling/index algorithm complexity.
