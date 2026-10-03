# Audit remediation verification

Base inspected revision: `ddd838ee48e1db425ed06441de68cc8674417923`.
The original locked frontend built successfully; its generated asset filenames
matched the deployed assets during the audit. This is supporting build correspondence,
not cryptographic proof of the deployment's source revision.

## Corrected implementation

- Notification cards open `requestId`, not notification `id`; acknowledgment uses the notification ID.
- CommandIntent retains a key/fingerprint after failure and coalesces identical pending clicks.
- Changed intent rotates keys; acknowledged success ends the intent. No automatic unsafe replay.
- AsyncScope and workspace epochs suppress stale list/access/detail/action callbacks, including account/workspace changes.
- Logout preserves the UI on server failure; successful logout or protected-endpoint 401 clears account state.
- Network operations have bounded waits. CSRF accepts only supported server-selected header names.
- Request/inbox/notification lists navigate bounded pages. Request pagination preserves applied filters.
- Status/type/date/workflow/sort filters; notification reads/preferences; bounded admin directories,
  versioned custom grants/member changes, conditional draft editing, immutable publication,
  request activity/reassignment and read-only audit inspection.
- Published policy editing is disabled; publication of unsaved local changes is blocked.
- Vercel and Nginx security headers; HSTS remains an HTTPS-edge setting, not a local HTTP listener claim.
- Responsive notification rows/filter inputs, visible mobile logout/new-request controls, modal focus containment/Escape.
- The prepared CI update adds built-asset mock-browser checks, disposable full-stack startup, real authenticated HTTP lifecycle,
  transport smoke artifact and project-specific teardown. No automatic production deployment was added.

## Fresh evidence in this remediation session

- Frontend unit/DOM/API regression suite: **26 tests passed**, zero failures.
- ESLint: passed with zero allowed warnings.
- TypeScript + Vite production build: passed.
- Built-asset Chromium browser flow with mocked APIs: passed across **eight captured states**;
  desktop/mobile navigation, pagination, notification routing, admin/audit and logout errors;
  no uncaught page errors. Visual review found and fixed date-filter clipping and mobile row/header issues.
- Production npm dependency audit: **zero reported vulnerabilities** at the time of this run.
- CI YAML/Vercel JSON parsed; shell syntax and Python runtime-check compilation passed.

## Not claimed / remaining verification

Docker is unavailable in the current remediation sandbox. The new real-service CI check
was syntax/contract reviewed but was **not executed here**. Historical backend tests and
previous single-runner stack evidence were not rerun in this session. The new GitHub CI
run and CodeQL status must be inspected before calling this revision fully verified.
Mocked browser tests are not real authenticated backend or live production evidence.

Live headers/assets must be checked after deployment; changing `vercel.json` alone does
not prove deployment. External email identity, runtime DB privilege/rotation, recovery
rehearsals, image/JVM advisories, representative mixed load and independent audit retention
remain [production gates](../security/release-gates.md). The transport smoke is not a
performance improvement or production-capacity result.

## UI limitations deliberately retained

Existing-version lookup requires UUID because the current backend has no version-directory
route. Administrative enrollment uses an existing account UUID, not an email invitation.
Command recovery keys live with the mounted form; recover an ambiguous result before closing
or reloading. No production secrets, session tokens, or business payloads are persisted in
localStorage, committed, or uploaded as CI artifacts.

## GitHub workflow permission blocker

The frontend commit was pushed successfully. A subsequent push containing
`.github/workflows/ci.yml` returned **403 Resource not accessible by personal access token**.
The workflow update was not committed to the active workflow. The prepared version is
[ci-workflow.pending.yml](../ci/ci-workflow.pending.yml); runtime scripts can be committed
without changing workflow permissions. Existing CI must not be described as running these
new checks until the workflow is applied with authorized workflow-write access.
