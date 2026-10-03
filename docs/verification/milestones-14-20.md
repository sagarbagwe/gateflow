# Milestones 14–20 verification

## Delivered

- M14: responsive React/TypeScript authentication and approval workspace with loading, error, empty, focus, mobile, and reduced-motion states.
- M15: Actuator health probes, authenticated Prometheus endpoint, initial alert signals, and dependency failure runbook.
- M16: multi-stage non-root backend/frontend images, same-origin Nginx proxy, complete Compose topology, health ordering, and smoke script.
- M17: backend/frontend CI, artifact packaging, image builds, CodeQL, and Dependabot; no deployment job.
- M18: AWS service mapping, network/security boundaries, recovery objectives, restore drills, cost controls, and rollout/rollback plan.
- M19: reproducible k6 smoke workload, workload mix, metrics, and acceptance gates; no fabricated production result.
- M20: threat boundaries, finding register, release checklist, and explicit residual risks.

## Checks run in implementation workspace

- `npm run lint`: passed with zero warnings.
- `npm run build`: passed; 30 modules transformed, production assets generated.
- `npm test`: 2 tests passed.
- Visual QA at 1440×900: login and request dashboard had no overlaps or viewport overflow; mobile collapse rules are present.
- Compose YAML parsed and all seven required services were present.
- `bash -n scripts/test-stack.sh`: passed.

Docker was unavailable in the implementation workspace, so image builds and the full stack were not falsely reported as executed. GitHub Actions is the reproducible verification path. No AWS resources, email provider, or production environment were created.
