# Core ER diagram

Mermaid renders on GitHub. Every tenant-owned relationship also uses tenant-aware
composite foreign keys; arrows alone do not show all composite key columns.
Draft requests may be unbound until submission. No notification/outbox/auth-session
tables have been introduced early.

```mermaid
erDiagram
    USERS ||--o{ MEMBERSHIPS : joins
    ORGANIZATIONS ||--o{ MEMBERSHIPS : has
    ORGANIZATIONS ||--o{ ROLES : owns
    MEMBERSHIPS ||--o{ MEMBERSHIP_ROLES : receives
    ROLES ||--o{ MEMBERSHIP_ROLES : assigned
    ROLES ||--o{ ROLE_PERMISSIONS : grants
    PERMISSIONS ||--o{ ROLE_PERMISSIONS : catalog
    ORGANIZATIONS ||--o{ WORKFLOW_DEFINITIONS : owns
    WORKFLOW_DEFINITIONS ||--o{ WORKFLOW_VERSIONS : versions
    WORKFLOW_VERSIONS ||--o{ WORKFLOW_STEPS : defines
    ROLES ||--o{ WORKFLOW_STEPS : reviewer_role
    MEMBERSHIPS ||--o{ REQUESTS : submits
    WORKFLOW_DEFINITIONS ||--o{ REQUESTS : selected
    WORKFLOW_VERSIONS o|--o{ REQUESTS : pinned_at_submission
    REQUESTS ||--o{ REQUEST_STEPS : executes
    WORKFLOW_STEPS ||--o{ REQUEST_STEPS : instantiated
    MEMBERSHIPS o|--o{ REQUEST_STEPS : assigned
    REQUEST_STEPS ||--o| APPROVAL_DECISIONS : decided_once
    MEMBERSHIPS ||--o{ APPROVAL_DECISIONS : reviewer
    ORGANIZATIONS ||--o{ AUDIT_LOGS : evidence
    MEMBERSHIPS o|--o{ AUDIT_LOGS : user_actor

    USERS {
        uuid id PK
        varchar email UK
        varchar password_hash
        varchar status
    }
    ORGANIZATIONS {
        uuid id PK
        varchar slug UK
    }
    MEMBERSHIPS {
        uuid id PK
        uuid organization_id FK
        uuid user_id FK
        varchar status
    }
    ROLES {
        uuid id PK
        uuid organization_id FK
        varchar code
    }
    PERMISSIONS {
        varchar code PK
    }
    MEMBERSHIP_ROLES {
        uuid organization_id FK
        uuid membership_id PK,FK
        uuid role_id PK,FK
    }
    ROLE_PERMISSIONS {
        uuid organization_id FK
        uuid role_id PK,FK
        varchar permission_code PK,FK
    }
    WORKFLOW_DEFINITIONS {
        uuid id PK
        uuid organization_id FK
        varchar name
    }
    WORKFLOW_VERSIONS {
        uuid id PK
        uuid organization_id FK
        uuid workflow_definition_id FK
        int version_number
        varchar status
    }
    WORKFLOW_STEPS {
        uuid id PK
        uuid organization_id FK
        uuid workflow_version_id FK
        uuid approver_role_id FK
        int position
        jsonb conditions
    }
    REQUESTS {
        uuid id PK
        uuid organization_id FK
        uuid requester_membership_id FK
        uuid workflow_definition_id FK
        uuid workflow_version_id FK
        varchar state
        bigint row_version
    }
    REQUEST_STEPS {
        uuid id PK
        uuid organization_id FK
        uuid request_id FK
        uuid workflow_version_id FK
        uuid workflow_step_id FK
        uuid assigned_membership_id FK
        varchar state
        bigint row_version
    }
    APPROVAL_DECISIONS {
        uuid id PK
        uuid organization_id FK
        uuid request_id FK
        uuid request_step_id FK,UK
        uuid reviewer_membership_id FK
        varchar decision
    }
    AUDIT_LOGS {
        uuid id PK
        uuid organization_id FK
        uuid actor_membership_id FK
        varchar actor_kind
        varchar action
        uuid resource_id
        jsonb old_value
        jsonb new_value
        varchar correlation_id
    }
```

The email UK is an expression index on `lower(email)`. Version numbering is unique
per definition, and role codes are unique per organization. Approval cardinality
is intentionally one decision per step for the first sequential engine.
