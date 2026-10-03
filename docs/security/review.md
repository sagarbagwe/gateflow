# Security review

## Threat model

Trust boundaries are browser→ingress, API→PostgreSQL/Redis/RabbitMQ/SMTP, and operator→cloud control plane. Highest-risk assets are tenant data, approval authority, session tokens, audit evidence, email destinations, and recovery credentials.

Reviewed controls: opaque hashed sessions, BCrypt, CSRF, bounded bodies, strict JSON, tenant-scoped RBAC, live permission checks, immutable workflow versions, command idempotency, append-only normal audit paths, generic emails, cache fallback, broker deduplication, and quarantined ambiguous SMTP outcomes.

## Findings

| Severity | Finding | Disposition |
|---|---|---|
| High | Production email lacks verified identity, suppression, bounce and complaint handling | keep external email disabled until SES controls are implemented |
| High | Runtime DB role separation and credential rotation are not proven | required before production |
| Medium | Audit data is not tamper-proof against database owners | export/retention and independent evidence store remain follow-up |
| Medium | Full CSP and edge rate limits depend on production ingress | configure CloudFront/WAF and test report-only CSP before enforcement |
| Medium | Recovery objectives are unproven | execute restore and failover drills |
| Low | Dependency/image updates can drift | Dependabot, CodeQL and reviewed image rebuilds are enabled |

## Release checklist

Require clean CI, CodeQL review, no committed secrets, HTTPS-only secure cookies, least-privilege runtime identities, encrypted backups, log redaction verification, restore evidence, abuse controls, verified SES setup, and rollback rehearsal. Rotate any credential exposed to logs or issue trackers. This review completes the repository milestone; it does not declare the service production-certified.
