# GateFlow frontend

React/TypeScript SPA with same-origin cookie authentication and CSRF-protected writes.

## Implemented

- Login/signup/logout, effective-permission visibility and organization-scoped state.
- Bounded request/reviewer inbox navigation; status/type/date/workflow filters and sorting.
- Stable idempotency keys for retrying unchanged submit/decision/withdraw/reassign commands.
- Notification-to-request navigation, read acknowledgment, versioned channel preferences.
- Custom role grants, administrative account enrollment, membership role/status changes.
- Ordered conditional workflow drafts, version checks, immutable publication.
- Request activity and reviewer reassignment; read-only audit metadata/evidence.
- Responsive layouts, visible error/loading/empty states, modal focus containment and Escape.

Server authorization remains authoritative. Cached UI permissions never grant access.
Async response scopes suppress older results after account/workspace/view changes.
Command keys persist only for the mounted intent: an ambiguous result must be retried
before closing the form or reloading. No business payloads or tokens are persisted in localStorage.
The stored organization/policy IDs are preferences, not credentials or authorization.
Existing policy versions and administrative account/role enrollment use UUIDs because
no global account search, invitation acceptance, or version-directory API exists.

## Verification

```sh
npm ci --ignore-scripts
npm run lint
npm test
npm run build
npx playwright install chromium
npm run test:browser
```

The browser suite exercises production-built assets with **mocked API responses**;
it checks interactions, CSP-compatible rendering, desktop/mobile layouts, and errors.
It is not evidence of live authenticated backend correctness. The isolated Docker CI
job uses `scripts/verify-browser-flow.py` against real API/database/broker services.

Vite proxies `/api` to localhost:8080. Production Nginx proxies to the Compose backend;
Vercel retains its existing Railway rewrite. Vercel headers are configured in
`vercel.json`; HTTPS ingress must deploy them before live remediation is claimed.
