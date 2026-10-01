# OpenAPI and Swagger UI

Spring Boot 3.5 uses pinned springdoc-openapi-starter-webmvc-ui 2.8.17, the compatible
2.8.x line in the upstream Boot matrix. No Boot upgrade or separate documentation
server is needed. Compatibility source: https://springdoc.org/v2/ (checked before
adding dependency); published version resolved from Maven Central.

Documentation is OFF by default. Opt in with API_DOCS_ENABLED=true when launching
an internal development instance. Both JSON and Swagger UI use the existing opaque
session security chain; anonymous access is 401. With docs disabled an authenticated
request sees 404. This switch must remain false on public production entrypoints.

Routes when enabled:
- GET /v3/api-docs/gateflow — grouped OpenAPI 3.0 JSON for /api/v1/** only
- GET /v3/api-docs — generated default specification
- GET /v3/api-docs/swagger-config — UI configuration
- GET /swagger-ui/index.html — locally bundled Swagger UI

Use a same-origin authenticated browser session (login/signup API), then open Swagger.
The UI is not a separate login screen. HttpOnly session cookies cannot be pasted into
Swagger Authorize or localStorage: the browser supplies them. GET /api/v1/auth/csrf
before writes and again after login/logout. Swagger's CSRF interceptor reads the
matching XSRF-TOKEN (local) or __Host-XSRF-TOKEN (secure) cookie and sends X-XSRF-TOKEN.
Security requirements model cookie AND CSRF together for protected writes, CSRF-only
for public signup/login/logout, and cookie-only for protected reads.

Relative server / avoids committing sandbox hosts and untrusted Host headers.
No external validator or petstore endpoint; authorization persistence is disabled.
No real accounts, credentials or resource payloads are embedded in the exported
schema. Optional generated docs increase dependencies and internal discovery surface,
which is why exposure stays explicit and authenticated. Reverse proxy/TLS/context
prefix deployment still requires review; forward headers remain untrusted by default.

Generated docs/api/openapi.json is a reviewable snapshot exported from the running
packaged JAR, not a hand-written claim. Live docs are authoritative at runtime.
Regenerate after contract changes and inspect the diff; tests validate exact route
coverage, unique operation IDs, local references, security, typed success/problems,
write-only passwords and explicit search query keys/bounds. No generated client or
speculative SDK is introduced. See conventions.md for compatibility decisions.
