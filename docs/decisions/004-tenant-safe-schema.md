# ADR 004: Tenant-aware relational integrity and a sequential core

Status: accepted and implemented in Milestone 2.

## Context

Global UUID identifiers alone allow an organization-owned child to reference a
parent owned by another tenant. Published policies must remain historically stable.
The first product workflow is sequential, not a general parallel process engine.

## Decision

Use a shared PostgreSQL schema with organization discriminator columns and explicit
composite tenant-aware FKs. Separate workflow versions/step definitions from
request/step execution instances. Freeze published definitions and submitted
payload/version bindings. Store one immutable decision per sequential step.
Version changes happen through Flyway, using digest-pinned PostgreSQL/Flyway images.

## Alternatives

Database-per-tenant increases provisioning and migration overhead. Schema-per-tenant
multiplies schema management. UUID-only FKs fail to enforce tenant reference
integrity. RLS can add read protection, but requires pooled transaction-local
context and leakage tests; it is not silently enabled here. Parallel approval
structures would add unused tables/rules before the sequential engine exists.

## Consequences

Composite unique indexes add storage/write cost, accepted for reference correctness.
Queries still require tenant scope and authorization. Triggers enforce narrow
storage invariants, not full domain authorization. Schema owners can bypass guards;
production runtime must be a restricted non-owner identity. Later parallel/quorum
work needs deliberate schema evolution instead of pretending one-decision storage
already supports it.
