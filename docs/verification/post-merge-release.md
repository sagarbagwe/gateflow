# Post-merge release verification

## Reviewed source and CI snapshot

- Maintainer-merged PR: [#25](https://github.com/sagarbagwe/gateflow/pull/25).
- Reviewed main: `c5753c878ee8953a732783be2ef59375e895063a` (merged 2026-10-04).
- Final PR head: `4e786112a882b923fe8799384c542b49b6daa9ed`; CI/full-stack,
  Java/TypeScript CodeQL and Vercel preview checks passed before merge.
- [Main CI run 37181650946](https://github.com/sagarbagwe/gateflow/actions/runs/37181650946):
  backend Maven verify and frontend lint/test/build/browser/npm-audit jobs passed.
  The run completed successfully, including the full-stack job: source Docker builds,
  least-privilege database checks, authenticated API/browser lifecycle, k6 smoke,
  restore, application-image and complete-checkout source-secret gates.
- [Main CodeQL run 37181650973](https://github.com/sagarbagwe/gateflow/actions/runs/37181650973):
  completed successfully on the reviewed main SHA. This establishes execution, not
  absence of all security alerts.

## Read-only hosted deployment checks

The first supplied demo password was rejected. A single retry with the corrected password
succeeded. Read-only authenticated navigation inspected requests, reviewer inbox, one
request detail dialog, notifications, workspace setup and audit logs; no visible alerts
were observed. No live accounts, requests, approvals, roles or notification preferences
were created/modified; notifications were not marked read. Login creates normal session
state, and sign-out revokes it. No password reset or provider-setting change was made.
Credentials and session cookies are excluded from documentation and evidence.
Destructive/load/recovery scripts were not run on the demo.

| Check | Observed result |
| --- | --- |
| Vercel root | HTTPS 200; sign-in page renders with email/password and signup action |
| Demo login | Corrected credentials accepted; first password rejected |
| Authenticated navigation | Requests, reviewer inbox, request detail, notifications, workspace setup and audit pages loaded without visible alerts; no business writes |
| Frontend headers | CSP, nosniff, DENY framing, no-referrer, restricted permissions, HSTS present |
| Same-origin current user | `/api/v1/auth/me`: 401 JSON ProblemDetail without a session |
| Same-origin CSRF | `/api/v1/auth/csrf`: 200 JSON; `__Host-XSRF-TOKEN` cookie has Secure and SameSite=Lax; intentionally readable by browser code |
| Backend aggregate health | Railway `/actuator/health`: **503**, JSON status **DOWN**; no component details publicly exposed |
| Backend health groups | `/actuator/health/liveness` and `/actuator/health/readiness`: 200 / UP; this does not override aggregate DOWN |
| Mobile layout | Notifications fit at 390px; audit page has horizontal overflow from a long action/resource label button; branch fix and automated regression added |
| Backend anonymous metrics | `/actuator/prometheus`: 401 |
| Backend anonymous API/docs | `/api/v1/auth/me` and `/v3/api-docs`: 401; anonymous docs denial does not establish authenticated docs availability |
| Frontend health/docs paths | `/actuator/health` and `/v3/api-docs`: 404, not backend health evidence |

Public JavaScript asset: `/assets/index-DOXbwnGg.js`; stylesheet:
`/assets/index-DOXg61Q4.css`. Asset names, HTTP 200 and a passing Vercel preview check
are **not** proof of the production deployment commit. Provider deployment metadata
must confirm Vercel and Railway source SHAs before claiming revision parity.

## Release decision and next actions

**Not production-certified.** Backend aggregate health is a current blocker even though
the login page and selected API responses work. Public health details are intentionally
limited; do not guess whether PostgreSQL, Redis, RabbitMQ, SMTP or another indicator
caused DOWN, disable checks to hide failure, or change secrets blindly.

1. Main CI and CodeQL passed. Require equivalent checks on subsequent revisions;
   confirm branch protection and retain dated check/artifact evidence.
2. Obtain authorized Railway deployment logs and component health diagnostics; identify
   the failing indicator, remediate with tested rollback and recheck aggregate/readiness.
3. Confirm both provider deployment SHAs and run a consented authenticated smoke flow
   using a dedicated test tenant with explicit mutation consent. Corrected demo login and
   read-only navigation passed; submit/approve/reject writes were not exercised live.
4. Verify production DB/IAM/secrets/ingress, alert delivery, recovery/HA, email ownership
   and provider setup, infrastructure advisories and representative staging load.
5. Require branch CI and review before merging this documentation update; no main push
   or merge is part of this review.

See [release gates](../security/release-gates.md) and
[dependency risks](../security/dependency-review.md). Do not publish tokens, cookies,
signed download URLs, raw provider logs containing secrets, or database dumps.

## Mobile audit regression fix (branch only)

The live audit-list button with a long action/resource label extended to 417px in a
390px viewport. Added bounded width and word wrapping to card list-item buttons,
without hiding overflow or truncating evidence. Expanded the mocked browser fixture
with `NOTIFICATION_PREFERENCES_CHANGED` / `NOTIFICATION_PREFERENCES` and a
390px audit capture. The test fails with horizontal overflow before the CSS fix.
After the fix: frontend lint, 26 unit tests, TypeScript/Vite build and all nine browser
states pass. This verifies the branch build, not an already-deployed live fix.

The current review has not inspected Railway service logs/component health with
privileged credentials. Both health groups report UP, but aggregate DOWN remains
unexplained. Do not weaken monitoring or enable public sensitive diagnostics as a fix.
