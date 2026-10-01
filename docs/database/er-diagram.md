# Core ER diagram

Mermaid renders on GitHub. Every tenant-owned relationship also uses tenant-aware
composite foreign keys; arrows alone do not show all composite key columns.
Draft requests may be unbound until submission. M3 adds global auth-session and
rate-limit storage. M5 adds command receipts; M8 adds outbox, consumer receipts and activity. M9 adds notification preferences/inbox/email delivery/attempts.

```mermaid
erDiagram
    USERS o|--o{ ORGANIZATIONS : created_by
    USERS ||--o{ MEMBERSHIPS : joins
    USERS ||--o{ AUTH_SESSIONS : authenticates
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
    ORGANIZATIONS ||--o{ COMMAND_RECEIPTS : commands
    MEMBERSHIPS ||--o{ COMMAND_RECEIPTS : retries
    REQUESTS ||--o{ COMMAND_RECEIPTS : result
    ORGANIZATIONS ||--o{ AUDIT_LOGS : evidence
    MEMBERSHIPS o|--o{ AUDIT_LOGS : user_actor

    AUTH_SESSIONS {
        uuid id PK
        uuid user_id FK
        char token_hash UK
        timestamptz expires_at
        timestamptz revoked_at
    }
    AUTH_RATE_LIMIT_BUCKETS {
        char key_hash PK
        timestamptz window_started_at
        int attempts
    }
    USERS {
        uuid id PK
        varchar email UK
        varchar password_hash
        varchar status
    }
    ORGANIZATIONS {
        uuid created_by_user_id FK
        uuid id PK
        varchar slug UK
    }
    MEMBERSHIPS {
        uuid id PK
        uuid organization_id FK
        uuid user_id FK
        bigint row_version
        varchar status
    }
    ROLES {
        boolean is_system
        bigint row_version
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
        bigint row_version
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
    COMMAND_RECEIPTS {
        uuid organization_id PK,FK
        uuid actor_membership_id PK,FK
        uuid idempotency_key PK
        varchar operation
        char payload_hash
        uuid request_id FK
        timestamptz created_at
    }
    REQUESTS ||--o{ OUTBOX_EVENTS : emits
    MEMBERSHIPS ||--o{ OUTBOX_EVENTS : actor
    REQUEST_STEPS o|--o{ OUTBOX_EVENTS : optional_step
    OUTBOX_EVENTS ||--o{ PROCESSED_EVENTS : consumed_by
    OUTBOX_EVENTS ||--o| REQUEST_ACTIVITY : projected
    REQUESTS ||--o{ REQUEST_ACTIVITY : timeline
    OUTBOX_EVENTS {
        uuid id PK
        uuid organization_id FK
        uuid request_id FK
        bigint request_version UK
        uuid actor_membership_id FK
        uuid step_id FK
        varchar event_type
        varchar request_state
        uuid lease_token
        timestamptz lease_until
        timestamptz published_at
    }
    PROCESSED_EVENTS {
        varchar consumer_name PK
        uuid event_id PK,FK
        uuid organization_id FK
        timestamptz processed_at
    }
    REQUEST_ACTIVITY {
        uuid event_id PK,FK
        uuid organization_id FK
        uuid request_id FK
        bigint request_version UK
        timestamptz occurred_at
        timestamptz projected_at
    }
    MEMBERSHIPS ||--o| NOTIFICATION_PREFERENCES : prefers
    MEMBERSHIPS ||--o{ NOTIFICATIONS : receives
    OUTBOX_EVENTS ||--o{ NOTIFICATIONS : source
    REQUESTS ||--o{ NOTIFICATIONS : updates
    NOTIFICATIONS ||--o| NOTIFICATION_EMAIL_DELIVERIES : email_job
    NOTIFICATION_EMAIL_DELIVERIES ||--o{ NOTIFICATION_EMAIL_ATTEMPTS : outcomes
    NOTIFICATION_PREFERENCES {
        uuid organization_id PK,FK
        uuid membership_id PK,FK
        boolean in_app_enabled
        boolean email_enabled
        bigint row_version
    }
    NOTIFICATIONS {
        uuid id PK
        uuid organization_id FK
        uuid event_id FK
        uuid request_id FK
        uuid recipient_membership_id FK
        uuid step_id FK
        varchar kind
        boolean in_app
        timestamptz read_at
    }
    NOTIFICATION_EMAIL_DELIVERIES {
        uuid id PK
        uuid notification_id FK,UK
        uuid organization_id FK
        uuid recipient_membership_id FK
        varchar status
        int attempts
        uuid lease_token
        timestamptz lease_until
        timestamptz available_at
    }
    NOTIFICATION_EMAIL_ATTEMPTS {
        uuid delivery_id PK,FK
        int attempt_number PK
        uuid organization_id FK
        uuid lease_token
        varchar outcome
        varchar reason
        timestamptz finished_at
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

M6 adds a derived `requests.search_document` tsvector and indexes/creation-time guard. No entities or relationships changed; the ER diagram remains structurally correct.
