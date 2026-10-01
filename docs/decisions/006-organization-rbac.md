# ADR 006: Organization-scoped RBAC and safe delegation

Status: accepted and implemented in M4.

## Context

Global roles or caller-supplied tenant headers risk cross-company data access.
Long-lived permission claims in sessions also delay revocation. Administrative
changes can race and remove every administrator or overwrite newer assignments.

## Decision

Use the M2 joins: users -> memberships -> membership_roles -> roles ->
role_permissions -> permissions. Roles live in one organization; permission codes
are a shared vocabulary. Memberships and organizations must be ACTIVE, and the
global account must be ACTIVE. Tenant membership plus resource ownership matters:
a permission alone is not a global authorization. Future request endpoints must
add ownership, reviewer assignment, self-approval, and state checks in M5.

Four default roles are created only by the organization bootstrap transaction:

| Role | Default grants |
| --- | --- |
| ADMIN | All 12 catalog permissions |
| MANAGER | WORKFLOW_VIEW, REQUEST_SUBMIT, REQUEST_VIEW_OWN, REQUEST_VIEW_ALL, REQUEST_APPROVE, REQUEST_WITHDRAW_OWN, AUDIT_VIEW |
| MEMBER | WORKFLOW_VIEW, REQUEST_SUBMIT, REQUEST_VIEW_OWN, REQUEST_WITHDRAW_OWN |
| VIEWER | WORKFLOW_VIEW, REQUEST_VIEW_OWN |

VIEWER is read-only but does not read other people's requests. MANAGER is a
business approver, not an automatic user/role administrator. Grants are explicit,
not a hidden role hierarchy. Multiple roles combine with a deduplicated union.
Default grants are immutable through normal API flows. Reserved codes cannot be
created by users. V5 does not upgrade preexisting ADMIN-named roles: privilege
migration must be explicit and reviewed, not guessed from a name.

Custom role managers may create/edit/assign only privileges contained in their
own effective set. Both previous and replacement grants are checked, so a limited
manager cannot strip or alter a more privileged role/member. Only an actual
system ADMIN can change or grant protected ADMIN membership; a custom role with
all permission codes is still not a protected administrator.

Authentication sessions contain identity, not grants. Every request reloads
current membership/grants. Mutations obtain an organization FOR UPDATE lock and
then reauthorize after waiting, all at READ COMMITTED. This serializes low-volume
RBAC changes so the last-active-admin check is safe across application replicas.
Expected row versions separately reject stale client edits. No Redis lock or
cache is needed. Creator quota uses a user-row lock during bootstrap.

Allowed-field audit snapshots are inserted in the same transaction. Organization
bootstrap includes org, four roles/grants, creator membership, and one audit row.
An audit storage failure rolls back the role/security change as well.

## Alternatives and consequences

- A global SUPER_ADMIN is not needed for this tenant product. Future platform
  support access requires a separately designed, audited, explicit break-glass path.
- Embedded JWT grants are faster to check but risk stale privilege revocation.
  Current DB reads cost latency; measurement precedes any authorization cache.
- Attribute-based rules alone are flexible but harder to explain/manage. RBAC is
  the administrative base; request-specific rules augment it, not replace it.
- Per-organization serialization is simple but a hot-tenant write bottleneck.
  Do not reuse this coarse lock for all workflow requests: M5 should lock/guard
  the relevant aggregate. A future administrative redesign needs measured need.
- SQL joins group role permissions and membership roles in bounded batches rather
  than per-row directory queries. No bulk total count is required.

## Limitations

There is no recovery/transfer/delete/invitation UI yet. Last-admin protection
covers these API mutations, not database-owner writes or a future global account
disabling feature. That feature must preserve/recover affected organizations.
The current local DB credential is an owner, not a production least-privilege
runtime role; triggers/API policies do not protect against an owner bypass.
Full audit browsing/retention/permissions, OpenAPI, business workflows, rate-limits
for admin traffic, and load measurements remain later milestones. Auth routes
retain M3 rate-limits; per-user org and per-org role quotas bound these creations.

M5 evolution: the catalog now has 13 codes. REQUEST_REASSIGN is explicitly added
to protected ADMIN roles; other defaults/custom roles retain their grants. See
[ADR 007](007-sequential-workflow-commands.md) for the business-command locking model.
