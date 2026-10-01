# API contract and compatibility

## Version and success bodies
Business routes use /api/v1. OpenAPI's /v3/api-docs names the specification tooling,
not a second business version. Keep typed resource success bodies; offset collections
use PageSlice(items,limit,offset,hasMore). Do not introduce a universal data/success
wrapper that breaks established clients or wrap a 204 empty response.

Request search has its own typed page: items,limit,hasMore,nextCursor,pagination,
offset. Cursor is the default, bound to tenant/actor/query/sort, not authorization;
OFFSET excludes cursor and CURSOR excludes even offset=0. Cursor continuation is not
a database snapshot. Other collections support bounded offset; clients calculate
next offset as offset+limit only when hasMore. There is no PageSlice.nextOffset.
All page sizes are 1..100; offsets 0..10000. Search details remain in search.md.

## Status and headers
200: read/mutation/replay. 201: committed creation. 204: logout, no JSON body.
400: invalid JSON/fields/parameters/query combinations. 401: session missing/invalid
or bad credentials. 403: CSRF or active-member permission failure. 404: missing or
foreign/invisible tenant/resource. 405: unsupported method, Allow retained.
406: unsupported Accept, with a safe application/problem+json error even when the
requested representation cannot be served. 409: stale version, transition or key
conflict. 413: body above 256 KiB. 415: unsupported Content-Type.
429: authentication rate limit with Retry-After seconds. 503: unavailable storage.
500: sanitized unexpected failure. Security gates run before business validation;
anonymous unknown routes return 401, not a public resource-existence oracle.

Server-generated X-Request-ID accompanies responses. Clients cannot inject log IDs;
problem.requestId equals that header and successful audited commands store it.
Logs omit raw query strings/IDs/credentials. Do not branch on human error detail.

Location is a relative implemented GET detail URI on new organizations, roles,
workflow definitions/versions and requests. Signup establishes a session, and
membership creation has no per-ID GET endpoint: no fake Location is invented.
Request creation replay is 200 with Idempotent-Replay:true, no creation Location.
First creation is 201 with Idempotent-Replay:false. Other commands expose the same
replay header. Required Idempotency-Key is a canonical UUID scoped to org/member;
changed operation/body conflicts, same command replays current request state.

## Error format and input strictness
RFC 9457 application/problem+json: type (about:blank), title, status, detail,
instance (path without query), code, requestId. Validation may include errors[] with
field/message only. Never include rejected values, SQL, stack traces or credentials.
JSON rejects unknown fields, duplicate keys, trailing JSON tokens and numeric enums.
Passwords are write-only in generated schema. Existing legitimate payloads/response
structures remain compatible. OpenAPI status catalogs describe possible failures,
not that every handler necessarily produces every listed code.

Adding optional fields/headers is additive; required input changes, enum removals or
changed semantics require review/versioning. Stronger malformed-JSON rejection is
intentional input hardening. Never silently switch auth to JWT, count-all pagination
or a catch-all success envelope. OpenAPI operation IDs are Controller_method and
contract tests compare its operation set to actual Spring MVC mappings.
