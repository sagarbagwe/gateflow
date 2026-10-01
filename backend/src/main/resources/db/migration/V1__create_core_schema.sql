-- Tenant-owned rows use composite FKs. UUID PKs do not alone enforce tenant scope.
CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(254) NOT NULL CHECK (email = btrim(email) AND position('@' in email) > 1),
    display_name varchar(120) NOT NULL CHECK (length(btrim(display_name)) > 0),
    password_hash varchar(255) NOT NULL CHECK (length(password_hash) >= 20),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email_ci ON users (lower(email));

CREATE TABLE organizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    slug varchar(80) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_organizations_slug UNIQUE (slug)
);

CREATE TABLE memberships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    user_id uuid NOT NULL REFERENCES users (id),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_memberships_user_org UNIQUE (organization_id, user_id),
    CONSTRAINT ux_memberships_tenant_id UNIQUE (organization_id, id)
);
CREATE INDEX ix_memberships_user ON memberships (user_id);

CREATE TABLE roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    code varchar(60) NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_roles_code UNIQUE (organization_id, code),
    CONSTRAINT ux_roles_tenant_id UNIQUE (organization_id, id)
);
CREATE TABLE permissions (
    code varchar(80) PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    description varchar(240) NOT NULL
);
CREATE TABLE membership_roles (
    organization_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    role_id uuid NOT NULL,
    PRIMARY KEY (membership_id, role_id),
    FOREIGN KEY (organization_id, membership_id) REFERENCES memberships (organization_id, id),
    FOREIGN KEY (organization_id, role_id) REFERENCES roles (organization_id, id)
);
CREATE INDEX ix_membership_roles_role ON membership_roles (organization_id, role_id);
CREATE TABLE role_permissions (
    organization_id uuid NOT NULL,
    role_id uuid NOT NULL,
    permission_code varchar(80) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code),
    FOREIGN KEY (organization_id, role_id) REFERENCES roles (organization_id, id)
);

CREATE TABLE workflow_definitions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    description text CHECK (length(description) <= 4000),
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_workflow_definitions_tenant_id UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX ux_workflow_definitions_name_ci
    ON workflow_definitions (organization_id, lower(name));

CREATE TABLE workflow_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    workflow_definition_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number > 0),
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, workflow_definition_id)
        REFERENCES workflow_definitions (organization_id, id),
    CONSTRAINT ux_workflow_version_number UNIQUE (workflow_definition_id, version_number),
    CONSTRAINT ux_workflow_versions_tenant_id UNIQUE (organization_id, id),
    CONSTRAINT ux_workflow_versions_definition_id UNIQUE (organization_id, workflow_definition_id, id),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE TABLE workflow_steps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    workflow_version_id uuid NOT NULL,
    position integer NOT NULL CHECK (position > 0),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    approver_role_id uuid NOT NULL,
    -- Structured conditions are validated by the future domain service, not arbitrary SQL/code.
    conditions jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(conditions) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, workflow_version_id) REFERENCES workflow_versions (organization_id, id),
    FOREIGN KEY (organization_id, approver_role_id) REFERENCES roles (organization_id, id),
    CONSTRAINT ux_workflow_steps_position UNIQUE (workflow_version_id, position),
    CONSTRAINT ux_workflow_steps_version_id UNIQUE (organization_id, workflow_version_id, id)
);
CREATE INDEX ix_workflow_steps_role ON workflow_steps (organization_id, approver_role_id);

CREATE TABLE requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    requester_membership_id uuid NOT NULL,
    workflow_definition_id uuid NOT NULL,
    -- Drafts may be unbound. Submission requires a published version.
    workflow_version_id uuid,
    title varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description text CHECK (length(description) <= 20000),
    request_type varchar(30) NOT NULL CHECK (request_type IN ('PURCHASE', 'SOFTWARE_ACCESS', 'POLICY_EXCEPTION')),
    request_data jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(request_data) = 'object'),
    purchase_amount numeric(14, 2),
    currency varchar(3),
    state varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    submitted_at timestamptz,
    completed_at timestamptz,
    FOREIGN KEY (organization_id, requester_membership_id) REFERENCES memberships (organization_id, id),
    FOREIGN KEY (organization_id, workflow_definition_id) REFERENCES workflow_definitions (organization_id, id),
    FOREIGN KEY (organization_id, workflow_definition_id, workflow_version_id)
        REFERENCES workflow_versions (organization_id, workflow_definition_id, id),
    CONSTRAINT ux_requests_version_id UNIQUE (organization_id, id, workflow_version_id),
    CONSTRAINT ux_requests_tenant_id UNIQUE (organization_id, id),
    CHECK ((purchase_amount IS NULL) = (currency IS NULL)),
    CHECK (purchase_amount IS NULL OR purchase_amount > 0),
    CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CHECK (request_type = 'PURCHASE' OR purchase_amount IS NULL),
    CHECK (state = 'DRAFT' OR request_type <> 'PURCHASE' OR purchase_amount IS NOT NULL),
    CHECK ((state = 'DRAFT' AND submitted_at IS NULL) OR
           (state <> 'DRAFT' AND submitted_at IS NOT NULL AND workflow_version_id IS NOT NULL)),
    CHECK ((state IN ('APPROVED', 'REJECTED', 'WITHDRAWN')) = (completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR completed_at >= submitted_at)
);
CREATE INDEX ix_requests_org_state_created ON requests (organization_id, state, created_at DESC, id DESC);
CREATE INDEX ix_requests_requester_created ON requests (organization_id, requester_membership_id, created_at DESC, id DESC);
CREATE INDEX ix_requests_workflow_version ON requests (organization_id, workflow_definition_id, workflow_version_id);

CREATE TABLE request_steps (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    request_id uuid NOT NULL,
    workflow_version_id uuid NOT NULL,
    workflow_step_id uuid NOT NULL,
    assigned_membership_id uuid,
    state varchar(20) NOT NULL DEFAULT 'WAITING'
        CHECK (state IN ('WAITING', 'ACTIVE', 'APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED')),
    row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    completed_at timestamptz,
    FOREIGN KEY (organization_id, request_id, workflow_version_id)
        REFERENCES requests (organization_id, id, workflow_version_id),
    FOREIGN KEY (organization_id, workflow_version_id, workflow_step_id)
        REFERENCES workflow_steps (organization_id, workflow_version_id, id),
    FOREIGN KEY (organization_id, assigned_membership_id) REFERENCES memberships (organization_id, id),
    CONSTRAINT ux_request_steps_definition UNIQUE (request_id, workflow_step_id),
    CONSTRAINT ux_request_steps_reviewer UNIQUE (organization_id, id, request_id, assigned_membership_id),
    CHECK (state <> 'ACTIVE' OR (assigned_membership_id IS NOT NULL AND activated_at IS NOT NULL)),
    CHECK ((state IN ('APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED')) = (completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR activated_at IS NULL OR completed_at >= activated_at)
);
CREATE INDEX ix_request_steps_definition ON request_steps (organization_id, workflow_version_id, workflow_step_id);
CREATE INDEX ix_request_steps_active_inbox
    ON request_steps (organization_id, assigned_membership_id, created_at DESC, id DESC)
    WHERE state = 'ACTIVE';

CREATE TABLE approval_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    request_id uuid NOT NULL,
    request_step_id uuid NOT NULL,
    reviewer_membership_id uuid NOT NULL,
    decision varchar(10) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    comment varchar(2000),
    decided_at timestamptz NOT NULL DEFAULT now(),
    -- Sequential single-reviewer steps: one immutable decision, by the pinned assignee.
    FOREIGN KEY (organization_id, request_step_id, request_id, reviewer_membership_id)
        REFERENCES request_steps (organization_id, id, request_id, assigned_membership_id),
    CONSTRAINT ux_approval_decisions_step UNIQUE (request_step_id)
);
CREATE INDEX ix_approval_decisions_request ON approval_decisions (organization_id, request_id, decided_at, id);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    actor_kind varchar(10) NOT NULL CHECK (actor_kind IN ('USER', 'SYSTEM')),
    actor_membership_id uuid,
    action varchar(80) NOT NULL CHECK (length(btrim(action)) > 0),
    resource_type varchar(80) NOT NULL CHECK (length(btrim(resource_type)) > 0),
    resource_id uuid NOT NULL,
    old_value jsonb CHECK (old_value IS NULL OR jsonb_typeof(old_value) = 'object'),
    new_value jsonb CHECK (new_value IS NULL OR jsonb_typeof(new_value) = 'object'),
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, actor_membership_id) REFERENCES memberships (organization_id, id),
    CHECK ((actor_kind = 'USER' AND actor_membership_id IS NOT NULL) OR
           (actor_kind = 'SYSTEM' AND actor_membership_id IS NULL))
);
CREATE INDEX ix_audit_logs_org_time ON audit_logs (organization_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_logs_resource ON audit_logs (organization_id, resource_type, resource_id, occurred_at DESC, id DESC);
