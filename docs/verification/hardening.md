# Verified hardening: milestones 13–17, 19 and 20

The work was reviewed in [PR #25](https://github.com/sagarbagwe/gateflow/pull/25) and merged to `main` as
`c5753c878ee8953a732783be2ef59375e895063a`. Before merge, the installed frontend,
backend, full-stack, Java/Kotlin CodeQL, JavaScript/TypeScript CodeQL, GitGuardian and
Vercel checks all passed. Vercel and Railway then deployed the merge successfully, and
public frontend/API/readiness smoke checks returned 200/200/UP. The original branch base
was `f86dffc7ef69c10297a8b03757340751ad117206`.

## Verification boundaries

Backend dependency/application patch `0163c5be439dbbfba1d3bdb1c622bb72228395c1`
passed [CI run 37140464784](https://github.com/sagarbagwe/gateflow/actions/runs/37140464784),
including Maven verify, frontend checks and normal source-based Docker builds.
Its executable JAR was downloaded from artifact `11280810576` and checked against
GitHub's archive digest before isolated runtime testing. [Artifact provenance](evidence/hardening/patched-artifact.json)
records archive/JAR SHA256 values. Agent runtime used a temporary artifact-copy Dockerfile;
it did not independently recompile the backend source in the agent worktree.

The isolated verification did not use external mailboxes, cloud customer data or real
identities. Fixture users use `example.invalid` addresses and Mailpit captures local email
only. After the verified merge, only non-destructive public frontend/API/readiness smoke
checks were run against the hosted demo; that does not certify the environment.

## Verified results

| Area | Evidence and result |
| --- | --- |
| Frontend | Lint, 26 tests, TypeScript/Vite production build, eight mocked-browser states; no uncaught errors. |
| Real browser | Authenticated workspace/search, notifications, mobile request details, workflow administration, audit browsing against the real backend; no 5xx responses or mobile overflow in the checked route. |
| Actual API | Final patched JAR: 53 successful HTTP checks including signup, RBAC, publish, submit/replay, approval, search, audit, async notifications/preferences/read, activity, logout/login/revocation. |
| Negative API boundaries | Anonymous API/metrics denied; missing-CSRF write denied; cross-tenant requests concealed with 404; missing audit/role-management permission denied; conflicting idempotency payload and stale duplicate decision rejected. Repeated invalid login reached 429. |
| Database role | Non-owner `gateflow_app` runs the complete lifecycle. Seven forbidden direct operations rejected: audit UPDATE/DELETE/TRUNCATE, table ALTER, schema CREATE, permission-catalog DELETE, Flyway-history SELECT. |
| Async resilience | Redis stopped: business requests still succeeded with DB fallback. Broker stopped: business writes still committed, eight unpublished outbox records remained durable; after restart the pending count returned to zero. These probes were repeated on the final patched backend with zero business errors; its normal async lifecycle also passed. |
| Recovery | Final separate-database pg_dump/pg_restore drill passed schema/count checks for 10 users, 10 organizations, 721 requests, 720 decisions, 1,480 audit rows and 11 successful migrations. A real restored audit-row update was rejected. Coarse dump/restore elapsed time was one second, not a production RTO. |
| Application images | Trivy 0.75.0 final backend/frontend scans: zero HIGH/CRITICAL findings. Includes unfixed advisories; no blanket ignore applied. Other severities, infrastructure and future advisories are separate concerns. |
| Supply chain | Digest-pinned refreshed builder/runtime images; pinned fixed Nginx runtime packages; patched Jackson, RabbitMQ client, Netty, Tomcat and PostgreSQL JDBC lines; runtime CycloneDX SBOM retained. |
| Source secrets | Offline source-secret scan passed for files available in the agent worktree. Full backend source was not materialized there: do not claim repository-wide secret coverage. The proposed full-repository CI gate remains pending installation. |
| Internal docs | OpenAPI stays disabled for authenticated access in the runtime-role overlay. Anonymous backend metrics remain 401. Public frontend SPA fallback is not a backend metrics probe. |
| Performance | Real authenticated ten-VU, one-minute workload with successful submit/approve writes. See measured scope and limitations in [performance evidence](../performance/hardening-workload.md). |

The image gate was also run against the old backend and returned nonzero, then passed
against the patched backend/frontend, demonstrating fail-closed scanner behavior.
The source-secret scan uses `--offline-scan` because secret detection does not require
resolving Maven dependencies; an unnecessary online POM resolution hit Maven Central 429.
Missing dependency-resolution warnings do not establish a dependency scan. Runtime JAR/OS
scans and production npm audit provide their own distinct dependency evidence.

## What is and is not complete

- **13–16:** current backend CI, frontend regression checks, real isolated-stack/browser flow,
  health/security boundaries and Docker runtime reverified. No cloud telemetry certification.
- **17:** corrected CI and separate Java/Kotlin plus JavaScript/TypeScript CodeQL workflows are installed; frontend, backend, disposable full-stack, both CodeQL languages, GitGuardian and Vercel checks passed before merge.
- **19:** basic authenticated performance measurement and query review complete. No fabricated
  optimization claim. Representative staging capacity/soak/saturation testing remains a release gate.
- **20:** code/image fixes, compatible development-tooling patches (zero HIGH/CRITICAL npm findings), runtime-role separation and local recovery
  verification implemented; production operational rollout/certification remains gated.

## Remaining production/operator gates — do not merge/promote as certified

1. Keep the installed CI, full-stack, CodeQL and security checks required for protected-branch merges; periodically review action pins and runner behavior.
2. Provision/review the actual deployment environment: ingress TLS/HSTS, secure cookies,
   secret storage/rotation, runtime DB/IAM least privilege, network isolation and rollback.
3. Review local infrastructure advisories independently. The bookworm PostgreSQL image has
   unresolved HIGH/CRITICAL package/advisory findings; managed RDS/ElastiCache architecture is
   not deployed evidence and does not magically resolve self-hosted image risks.
4. Keep external email disabled until verified recipient ownership, provider identity,
   bounce/complaint/suppression/abuse controls and delivery tests exist. No provider was configured.
5. Verify production backup/failover/restore and broker HA, retention, dashboards and alert delivery;
   local recovery is not disaster-recovery certification. Owner-proof audit export remains optional future work.
6. Public onboarding/invitations, email ownership verification and password recovery require a
   separately scoped secure product flow before broad enterprise onboarding.

One MODERATE test-only advisory still affects two npm graph entries; see [dependency review](../security/dependency-review.md) for scope and upgrade/review requirements.

Detailed runbooks: [CI gates](../ci/hardening-gates.md), [DB runtime identity](../security/runtime-role.md),
[release gates](../security/release-gates.md). Metrics/SBOM/scan inventories are in
[evidence](evidence/hardening/), with a SHA256 manifest. Never publish session fixtures,
passwords, signed artifact download URLs or database dumps.
