# Milestone 12 — API quality and OpenAPI/Swagger

## Delivered
Pinned compatible springdoc 2.8.17; OpenAPI 3.0 for 40 actually mapped versioned
operations. Stable IDs, DTO schemas, write-only passwords, explicit search-map keys,
cookie AND CSRF security, typed success/ProblemDetail errors and response headers.
Swagger uses local assets, same-origin CSRF interceptor and no external validator or
authorization persistence. Docs are disabled by default and require sessions when enabled.
Generated [snapshot](../api/openapi.json) exported from the actual executable JAR.

Preserved existing success/page/error bodies. Added relative Location only for new
resources with existing GET routes; replay remains 200/current view, no new Location.
Rejected duplicate/trailing JSON, mapped missing query parameters to 400 and
unacceptable Accept to safe 406. Corrected earlier audit prose: PageSlice does not
contain nextOffset. No database migration or auth strategy switch.

## Executed verification
- Focused API/doc suite: 15 tests, passed after correcting a Swagger assertion.
  CSRF is injected into swagger-initializer.js, not returned as a csrf object in
  swagger-config JSON; verified actual same-origin cookie/header interceptor.
- Full Maven verify: **313 tests**, zero failures/errors/skips, packaged JAR. All
  previous 298 passed, plus 11 real HTTP/API/OpenAPI cases, one real docs-off case
  and three unit contract/secure-cookie cases. Live spec operation set equals actual
  Spring MVC mappings; all local references resolve and IDs are unique.
- **103 packaged API/database/SMTP assertions**: generated spec and protected docs,
  full previous workflow/audit/notification flows and broker outage/restart recovery.
  Dedicated temporary database/vhost/app and captured local-only fixture emails cleaned.
- Existing 11 migrations and 24 tables unchanged; no product fixture data inserted.
  Java 21 formatting, scaffold/relative links, shell/Compose syntax, whitespace and
  private credential scan passed before commit. No external mailbox or cloud deployment.

Key files: api/OpenApiConfiguration.java, http/JsonInputConfiguration.java,
ApiExceptionHandler.java, selected create controllers, AuthRequests.java,
application.yml; API unit/integration/default-disabled tests.
See [conventions](../api/conventions.md), [Swagger setup](../api/openapi.md) and
[ADR 014](../decisions/014-api-contract-and-internal-openapi.md).

API quality is measured against existing contracts, not a promise that every listed
status occurs on every route. Snapshot regeneration remains necessary after changes.
The user approved all remaining milestones; next is Milestone 13 testing expansion.
