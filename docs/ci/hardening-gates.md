# Proposed release pipeline — installation blocked

`ci-workflow.pending.yml` is reviewed source, **not** an active workflow. GitHub MCP
rejected `.github/workflows/ci.yml` updates with HTTP 403 (workflow-write permission).
The current repository CI still runs backend Maven verification, frontend lint/tests/build,
and normal backend/frontend Docker builds on pull requests.

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

The pipeline does not deploy to production or merge a PR. After an authorized workflow
update, run it on this branch and require its checks before review/promotion. Branch protection
and required checks also need repository-administrator verification; no such settings were changed.

Application-image scans cover backend/frontend. Local database/cache/broker/mail images require
separate infrastructure review; do not describe a green application scan as a clean entire stack.
The local PostgreSQL bookworm image scan has unresolved advisories. Production RDS/ElastiCache
are managed-service designs, not proof those hosted services have been provisioned or reviewed.

## Existing CodeQL failure (merge blocker)

[Run 37141719211](https://github.com/sagarbagwe/gateflow/actions/runs/37141719211)
failed: Java autobuild returned exit 1, and init warned about an unexpected
`javascript-typescript` input. In the active YAML, the flow-style value
`with: {languages: java-kotlin,javascript-typescript}` incorrectly creates a second
mapping key rather than a two-language string.

`codeql-workflow.pending.yml` replaces that with separate Java and TypeScript matrix
jobs, supported SHA-pinned CodeQL v4, explicit Java 21 setup and the same Maven backend
build path used by passing CI, plus no-build TypeScript analysis. Both pending workflows
passed actionlint 1.7.12, but neither has been installed or executed as a corrected workflow.
Autobuild failure is not a successful security analysis; uploading failure diagnostics is
not a clean scan. Workflow-write access and a green corrected run are required before merge.
