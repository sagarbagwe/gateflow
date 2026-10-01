# ADR 002: PostgreSQL and immutable workflow versions

Status: accepted; schema implementation is Milestone 2.

## Context

Decisions, active step state, organization scope, and audit evidence must remain
consistent. Workflow administrators must not rewrite in-flight approval policies.

## Decision

Use PostgreSQL 17, Flyway migrations, and relational constraints. Separate editable
definitions/drafts from immutable published versions and request execution instances.
Start with PostgreSQL-based search. Use JPA/Hibernate with explicit query control
and schema validation, never production automatic schema updates.

## Alternatives

Document databases can represent nested workflows, but cross-entity constraints
and approval transactions are central here. Event sourcing offers historical
reconstruction but introduces projection/replay complexity we do not need. Editing
one shared workflow row in place makes request history ambiguous.

## Consequences

Additional version/instance tables make the model more explicit. Transaction
boundaries and tenant-safe foreign-key design must be documented in Milestone 2.
Migration rollback often means a compatible forward fix, not blindly running down
scripts that destroy data.

## Revisit when

Measured search workloads require a dedicated index or workflow history requires
capabilities beyond versioned definitions plus audit evidence.
