# Branch-only hardening: milestones 13–17, 19 and 20

Branch: `hardening/m17-m20-verified`. Review: [draft PR #25](https://github.com/sagarbagwe/gateflow/pull/25).
No merge, main push or production deployment was performed. Base main revision:
`f86dffc7ef69c10297a8b03757340751ad117206`.

## Verification boundaries

Backend dependency/application patch `0163c5be439dbbfba1d3bdb1c622bb72228395c1`
passed [CI run 37140464784](https://github.com/sagarbagwe/gateflow/actions/runs/37140464784),
including Maven verify, frontend checks and normal source-based Docker builds.
Its executable JAR was downloaded from artifact `11280810576` and checked against
GitHub's archive digest before isolated runtime testing. [Artifact provenance](evidence/hardening/patched-artifact.json)
records archive/JAR SHA256 values. Agent runtime used a temporary artifact-copy Dockerfile;
it did not independently recompile the backend source in the agent worktree.

Tests never touched the public Vercel deployment, external mailboxes, cloud accounts
or real customer data. Local fixture users use `example.invalid` addresses; Mailpit
captures local email only. Hosted demo and main remain unchanged and are not certified.

## Verified results

| Area | Evidence and result |
| --- | --- |
| Frontend | Lint, 26 tests, TypeScript/Vite production build, eight mocked-browser states; no uncaught errors. |
| Real browser | Authenticated workspace/search, notifications, mobile request details, workflow administration, audit browsing against the real backend; no 5xx responses or mobile overflow in the checked route. |
| Actual API | Final patched JAR: 53 successful HTTP checks including signup, RBAC, publish, submit/replay, approval, search, audit, async notifications/preferences/read, activity, logout/login/revocation. |
| Negative API boundaries | Anonymous API/metrics denied; missing-CSRF write denied; cross-tenant requests concealed with 404; missing audit/role-management permission denied; conflicting idempotency payload and stale duplicate decision rejected. Repeated invalid login reached 429. |
| Database role | Non-owner `gateflow_app` runs the complete lifecycle. Seven forbidden direct operations rejected: audit UPDATE/DELETE/TRUNCATE, table ALTER, schema CREATE, permission-catalog DELETE, Flyway-history SELECT. |
| Async resilience | Redis stopped: business requests still succeeded with DB fallback. Broker stopped: business writes still committed, eight unpublished outbox records remained durable; after restart the pending count returned to zero. These probes ran before the final dependency artifact; the final artifact's normal async lifecycle also passed. |
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
- **17:** existing CI passes; extended real-stack/browser/restore/security pipeline is prepared
  and syntax-checked, but workflow installation is blocked by GitHub workflow-write 403.
- **19:** basic authenticated performance measurement and query review complete. No fabricated
  optimization claim. Representative staging capacity/soak/saturation testing remains a release gate.
- **20:** code/image fixes, compatible development-tooling patches (zero HIGH/CRITICAL npm findings), runtime-role separation and local recovery
  verification implemented; production operational rollout/certification remains gated.

## Remaining production/operator gates — do not merge/promote as certified

1. Grant authorized workflow-write access, install and run the pending pipeline, verify required
   checks/branch protection and review latest branch CI before a human-approved merge.
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
