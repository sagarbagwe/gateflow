# Installed release pipeline

The maintainer installed `.github/workflows/ci.yml` and `.github/workflows/codeql.yml`
in PR #25. They are active on pull requests, main/hardening pushes and manual dispatch;
CodeQL also has a weekly schedule. The `*.pending.yml` files are historical proposals,
not the authoritative active workflow configuration. Earlier MCP workflow-write 403s
record a historical installation limitation, not a current pipeline blocker.

The installed CI includes:
The proposed extension adds:

1. SHA-pinned supported Actions and read-only repository token permissions.
2. Frontend mocked browser regression coverage and all-dependency HIGH/CRITICAL npm audit.
3. An isolated Compose stack with fresh private credentials; no production environments/secrets.
4. Separate migration/runtime database credentials and negative privilege checks.
5. Real authenticated API lifecycle and actual-backend desktop/mobile browser navigation.
6. Authenticated k6 business workload with fail-closed functional and latency thresholds.
7. Separate-database restore verification with restored audit-trigger enforcement.
8. Trivy HIGH/CRITICAL application-image gates, including unfixed advisories; source-secret gate.
9. Metric-only artifact upload, explicit removal of credentials/fixtures, disposable-volume teardown.

The pipeline does not deploy to production or merge a PR. Final PR checks passed before
the maintainer merged PR #25. Merged-main CodeQL and frontend/backend/full-stack CI jobs passed. Exact evidence is recorded
in [post-merge verification](../verification/post-merge-release.md). Require successful
checks on each proposed revision. Branch protection/required-check settings still need
repository-administrator verification; this review did not change those settings.

Application-image scans cover backend/frontend. Local database/cache/broker/mail images require
separate infrastructure review; do not describe a green application scan as a clean entire stack.
The local PostgreSQL bookworm image scan has unresolved advisories. Production RDS/ElastiCache
are managed-service designs, not proof those hosted services have been provisioned or reviewed.

## Corrected CodeQL execution

The former [run 37141719211](https://github.com/sagarbagwe/gateflow/actions/runs/37141719211)
failed Java autobuild and used an incorrectly formed flow-style language mapping.
The installed workflow now uses separate Java/TypeScript matrix jobs, SHA-pinned
CodeQL v4, explicit Java 21/Maven build and no-build TypeScript analysis.
[Merged-main run 37181650973](https://github.com/sagarbagwe/gateflow/actions/runs/37181650973)
completed successfully at SHA `c5753c878ee8953a732783be2ef59375e895063a`.
The former execution failure is resolved; run success is not a claim of zero security alerts.
