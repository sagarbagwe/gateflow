# ADR 014: stable typed contracts and opt-in generated OpenAPI

Status: accepted for Milestone 12.

Preserve typed resource/PageSlice success bodies and RFC 9457 problems. Do not break
existing clients with a universal wrapper or fake Location routes. Add relative
Location to supported detail resources, preserve replay semantics and request IDs.
Reject duplicate/trailing JSON and handle unacceptable media safely as 406.

Generate OpenAPI from actual mappings/DTOs using compatible pinned springdoc 2.8.17;
central operation customization adds security, error/header contracts and explicit
MultiValueMap search parameters. Tests compare live spec with Spring mappings.
Docs are off by default and require the same session even when enabled. Swagger
uses local assets, same-origin cookies/CSRF and no external validator/token persistence.

Costs: extra documentation dependencies, snapshot regeneration, handwritten domain
constraints still documented alongside generated DTO validation. No JWT auth,
production doc exposure, version migration, generated clients or imaginary endpoint.
