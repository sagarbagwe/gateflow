# Production release gates

Repository implementation and passing frontend checks are not production certification.

| Gate                              | Current status                                                                                                            | Required evidence                                                                                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Frontend response hardening       | Headers configured; live public HTML observed with CSP/nosniff/referrer/frame/permissions and HTTPS HSTS after push | inspect responses after deployment and test authenticated pages for CSP violations                                                                                 |
| Current CI and live revision      | branch CI and manual real-stack verification passed; extended workflow installation blocked                                       | green frontend/backend/full-stack/CodeQL runs; deployed revision matches reviewed SHA                                                                              |
| Database privilege separation     | proven on disposable stack; production rollout unverified                                                                                                                | migration owner separate from non-owner runtime role; negative DDL/direct audit-mutation tests; rotate and revoke old credentials                                  |
| Email identity/delivery           | not configured or certified                                                                                               | verified address acceptance, verified SES/provider identity, suppression/bounce/complaint processing, abuse controls; external transport stays disabled until then |
| Recovery                          | local restore and queue recovery passed; production failover/DR unverified                                                                                                                | isolated backup restore, failover, queue recovery and rollback rehearsal; measured RPO/RTO, not targets presented as facts                                         |
| Representative performance        | authenticated business workload measured; not production capacity                                                                | mixed authenticated staging workload with real cardinality, latency/resource/queue measurements, repeat runs and before/after evidence for any optimization        |
| Dependency/image risks            | application image and production npm gates passed; infrastructure advisories remain                                           | review JVM/image/OS advisories, CodeQL and secret scans; approved image rebuilds                                                                                   |
| Audit retention/tamper resistance | normal application flows are append-only; owner tampering still possible                                                  | retention policy and independently controlled evidence export where required                                                                                       |
| Onboarding/recovery               | account UUID enrollment; no verified-email invitation/reset flow                                                          | design expiring invitation acceptance, verification, password recovery and anti-abuse before offering public enterprise onboarding                                 |

Do not put production passwords/tokens in chat, source, CI artifacts, or logs. Do not
change production DB credentials, paid cloud resources, or external mail settings
without appropriate environment access and a tested rollback. An email preference
checkbox is not provider setup or proof of address ownership.

## Verify HTTPS deployment

Inspect HTML and API responses separately. Expect frontend CSP with `frame-ancestors
'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and HSTS
on HTTPS responses. Protected GETs without a session should remain 401. Do not
publicly enable Swagger to satisfy a checklist: docs are intentionally off by default.

## Safe runtime verification

`verify-browser-flow.py` refuses non-loopback hosts and requires
`GATEFLOW_DISPOSABLE_STACK=1`. Run only against disposable Compose data. CI deletes
that project's volumes afterwards. This script is not a production smoke tool.

See [branch hardening evidence](../verification/hardening.md) for exact tested artifact, measured scope and remaining blockers.
