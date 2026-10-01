# Authentication API — implemented slice

Base path: `/api/v1/auth`. Browser cookie authentication; no bearer JWT or refresh
endpoint. Defaults require HTTPS; local launcher is loopback HTTP only. Secure cookie names
use `__Host-GATEFLOW_SESSION` and `__Host-XSRF-TOKEN`; local names omit the prefix.
The required header remains `X-XSRF-TOKEN` in both modes.

| Method | Route | Success | Authentication / CSRF |
| --- | --- | --- | --- |
| GET | /csrf | 200 token/headerName | Public; creates/reads CSRF cookie |
| POST | /signup | 201 user view + session cookie | Public; CSRF header required |
| POST | /login | 200 user view + rotated cookie | Public; CSRF header required |
| POST | /logout | 204 empty body + expired cookie | Idempotent even without active session; CSRF required |
| GET | /me | 200 user view | Active session required |

## Requests and responses

Signup JSON: `email`, `displayName`, `password`. Login JSON: `email`, `password`.
Unknown fields are rejected, preventing client assignment of status/roles.
Signup requires a nonblank name, email up to 254 characters, and password length
12–64 Java string code units with maximum 72 UTF-8 bytes. Login retains byte/maximum
bounds. Passwords remain case-sensitive and whitespace-preserving.

A user view contains only `id`, `email`, `displayName`. Session tokens, password
hashes, role grants, and organization membership are not returned in JSON. A new
account does not automatically receive an organization or administration rights.

Example shape (synthetic identity, no example passwords/tokens):

```json
{"id":"00000000-0000-0000-0000-000000000001","email":"user@example.test","displayName":"User"}
```

## Browser flow

1. GET `/csrf`, retaining the cookie and reading `token`/`headerName`.
2. Send JSON to signup/login with the indicated header (default X-XSRF-TOKEN).
3. Retain the HTTP-only session cookie through the browser; never copy it into
   localStorage or application JavaScript.
4. Successful authentication clears the CSRF cookie; GET `/csrf` again before
   the next write. Existing CSRF tokens remain valid across ordinary reads and
   validation errors until an actual auth transition clears them.
5. Logout with the current CSRF header. The old session cannot be replayed.

For same-origin UI, cookies work naturally. Do not add wildcard/reflected credential
CORS when building the frontend. Form `_csrf` parameters are deliberately rejected.

## Errors

RFC 9457 `application/problem+json` includes `status`, `title`, safe `detail`,
`instance`, `code`, and `requestId`. Responses carry the same X-Request-ID value.
Validation errors add field/message pairs but never echo rejected values.

| Status | Meaning |
| --- | --- |
| 400 | Invalid fields, malformed JSON, unsupported JSON properties |
| 401 | Missing/expired/revoked session or invalid credentials |
| 403 | Missing/incorrect CSRF header or other access denial |
| 404 | Authenticated request to missing resource |
| 405 | Unsupported method; Allow header preserved |
| 409 | Account creation conflict; no additional user/session committed |
| 415 | Unsupported request media type after security checks |
| 429 | Shared IP/account rate limit; Retry-After included |
| 503 | Authentication/limiter database unavailable |
| 500 | Unexpected failure; internal details are not exposed |

Security checks run before request-body parsing. For example, an unsupported
payload without CSRF can correctly receive 403 before media-type validation.
Known/unknown/disabled login failures use the same credential error wording, but
signup conflict status is an acknowledged account-enumeration trade-off.

## Verification and scope

Tests use a real embedded HTTP server with a fresh Testcontainers PostgreSQL
instance, not only controller mocks. Unit tests cover hashing boundaries, cookie
flags, orchestration, and safe fail-closed filter behavior. Exact results are in
[Milestone 3 evidence](../verification/milestone-3.md). OpenAPI, product RBAC,
password recovery, and email verification are later work, not current endpoints.
