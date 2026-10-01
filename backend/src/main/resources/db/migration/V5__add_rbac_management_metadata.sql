-- Never infer privileged system identity from an existing custom role's name.
ALTER TABLE organizations ADD COLUMN created_by_user_id uuid REFERENCES users (id);
CREATE INDEX ix_organizations_creator ON organizations (created_by_user_id);
ALTER TABLE roles ADD COLUMN is_system boolean NOT NULL DEFAULT false;
ALTER TABLE roles ADD COLUMN row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0);
ALTER TABLE roles ADD CONSTRAINT ck_system_role_code
    CHECK (NOT is_system OR code IN ('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER'));
ALTER TABLE memberships ADD COLUMN row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0);
