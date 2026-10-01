# ADR 001: Start with a modular monolith

Status: accepted for initial development.

## Context

The product has several domains, but a small implementation team and no measured
need for independent service deployment. Approvals need coherent transactions.

## Decision

Use Java 21, Spring Boot, Maven, and explicit modules inside one backend. Use a
React/TypeScript/Vite SPA because this is an interactive authenticated product,
not a content site needing SEO or server rendering.

## Alternatives

Microservices add network failures, distributed consistency, deployment and
observability costs without a demonstrated benefit. An unstructured monolith is
simpler initially but encourages tightly coupled domain rules. Next.js is viable,
but its server/runtime boundary is unnecessary for the first UI.

## Consequences

One deployment and database simplify local setup and transactional correctness.
Module boundaries require discipline. Later worker deployments may share the
codebase; extracting a service requires measured pressure, not fashion.

## Revisit when

Independent teams, sustained workload isolation needs, or incompatible release
cadences outweigh the additional operational cost.
