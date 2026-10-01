# Contributing

Work one milestone at a time. Explain the objective, make the smallest coherent
change, run relevant checks, fix errors, update docs, report limitations, and
wait for approval before the next major milestone.

## Git conventions

- Default branch: `main`.
- Feature branches: `feat/<short-topic>`; fixes: `fix/<short-topic>`.
- Small commits: `chore:`, `feat:`, `fix:`, `test:`, `perf:`, `ci:`, `docs:`.
- No credentials, generated build outputs, or unverified production claims.
- Run `./scripts/check.sh` for scaffold changes; feature-specific build/test
  commands will be added as executable components arrive.
- Branch protection and required CI checks are not configured yet.

## Definition of done

A feature has meaningful tests, authorization and tenant checks where applicable,
clear failure behavior, updated documentation, and recorded verification results.
A skipped check is not a passed check.

Business logic belongs in domain/application services, not controllers. Favor
clear composition and dependency injection over generic frameworks and needless
patterns. Record consequential decisions in `docs/decisions/`.
