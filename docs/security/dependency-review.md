# Dependency and runtime image review

## Application runtime

The reviewed backend/frontend images passed Trivy 0.75.0 HIGH/CRITICAL gates. These
include OS packages and nested backend JAR dependencies. No unfixed-advisory exclusion
was used. This is a dated severity-scoped result, not a guarantee of no vulnerabilities.
Runtime CycloneDX inventory and image scan results are linked from the hardening evidence.

Compatible backend dependency fixes: Jackson BOM 2.21.7, RabbitMQ AMQP client 5.34.0,
Netty 4.1.137.Final, Tomcat 10.1.60 and PostgreSQL JDBC 42.7.12. Maven verify and real
runtime approval/async/browser checks passed with these resolved versions. Tomcat 10.1.58
was not available in Maven Central; 10.1.60 was available and fixes the flagged line.
The AMQP override uses Boot's actual `rabbit-amqp-client.version` property.

Container bases were refreshed and pinned by digest. The latest Nginx base still required
fixed Alpine libexpat 2.8.5-r0 and pcre2 10.49-r0; those package versions are pinned.
Rebuild and re-review image/package pins regularly rather than assuming a pinned old image is safe.

## Frontend build/test dependencies

Vitest was updated from 3.0.5 to 3.2.7, TypeScript ESLint from 8.22.0 to 8.71.0,
and ESLint/@eslint/js to 9.39.5. Lint and all 26 tests passed, the production build and
mocked/real browser flows were rechecked. Production npm audit reports zero findings.
The full npm graph reports zero HIGH/CRITICAL or LOW findings, with two affected
MODERATE entries for the same test-only advisory:

- [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9):
  redirect-mock path traversal/arbitrary read in Vitest/@vitest/mocker.
- npm proposes Vitest 5.0.3, a major toolchain upgrade; do not use `npm audit fix --force`.
- The repository runs `vitest run`; no Vitest UI/browser mock server is publicly deployed,
  and production serves bundled assets through Nginx, not a Vitest/Vite development server.
- This review is **not blanket risk acceptance**. Before enabling browser mock servers or
  widening CI trust/network exposure, require a separately tested toolchain upgrade or
  upstream supported-line fix. Review again by 2026-11-03. PR reviewers must approve any exception.

The proposed CI runs the all-dependency `npm audit --audit-level=high`, so development
HIGH/CRITICAL findings also fail. This gate is still pending workflow-write permission.

## Infrastructure and scan coverage

The non-production PostgreSQL bookworm scan contains unresolved HIGH/CRITICAL
package/advisory entries. Its condensed inventory retains installed/fixed versions and the
original raw-report hash. Do not deploy that self-hosted image under a "clean stack" claim.
Redis/broker/mail/managed-service reviews are separate; local containers are not a certified
AWS production environment. Application-image success does not close those gates.

The agent's source-secret scan covers the source files available in its worktree, not every
backend source file in GitHub. Complete repository coverage requires the proposed CI gate
or an authorized checkout/repository scanner. Source-secret scanning is offline on purpose;
resolving Maven dependency BOMs is neither required for secret detection nor proof of a
runtime dependency scan. Secret findings/fixtures and private dumps must not be uploaded.
