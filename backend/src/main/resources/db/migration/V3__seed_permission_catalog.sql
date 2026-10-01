-- Permission vocabulary only. No users, passwords, tenants, or role assignments.
INSERT INTO permissions (code, description) VALUES
    ('WORKFLOW_VIEW', 'View organization workflow definitions'),
    ('WORKFLOW_CREATE', 'Create workflow drafts'),
    ('WORKFLOW_UPDATE', 'Edit workflow drafts'),
    ('WORKFLOW_PUBLISH', 'Publish an immutable workflow version'),
    ('REQUEST_SUBMIT', 'Submit own requests'),
    ('REQUEST_VIEW_OWN', 'View own requests'),
    ('REQUEST_VIEW_ALL', 'View all organization requests subject to policy'),
    ('REQUEST_APPROVE', 'Decide an eligible assigned approval step'),
    ('REQUEST_WITHDRAW_OWN', 'Withdraw own requests where the lifecycle allows'),
    ('MEMBERSHIP_MANAGE', 'Manage organization membership'),
    ('ROLE_MANAGE', 'Manage organization roles and permission grants'),
    ('AUDIT_VIEW', 'Read organization audit records');
