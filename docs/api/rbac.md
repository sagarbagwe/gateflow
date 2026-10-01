# Organization and RBAC API

Implemented in M4. All routes require an existing authenticated cookie session.
All writes also require the CSRF header from `GET /api/v1/auth/csrf`.
The principal comes from the server session, never a user/role/tenant header.

## Routes

Base: `/api/v1/organizations`.

| Method | Suffix | Requirement | Success |
| --- | --- | --- | --- |
| POST | / | Authenticated active user; creator quota | 201 organization |
| GET | / | Active memberships only | 200 organization slice |
| GET | /{orgId} | Active membership | 200 organization |
| GET | /{orgId}/me | Active membership | 200 effective access |
| GET | /{orgId}/permissions | ROLE_MANAGE | 200 permission catalog |
| GET | /{orgId}/roles | ROLE_MANAGE | 200 role slice |
| GET | /{orgId}/roles/{roleId} | ROLE_MANAGE | 200 role |
| POST | /{orgId}/roles | ROLE_MANAGE + delegation ceiling | 201 role |
| PUT | /{orgId}/roles/{roleId}/permissions | ROLE_MANAGE + ceiling; custom roles only | 200 role |
| GET | /{orgId}/memberships | MEMBERSHIP_MANAGE | 200 membership slice |
| POST | /{orgId}/memberships | MEMBERSHIP_MANAGE and ROLE_MANAGE + ceiling | 201 membership |
| PUT | /{orgId}/memberships/{memberId}/roles | ROLE_MANAGE + ceiling | 200 membership |
| PATCH | /{orgId}/memberships/{memberId}/status | MEMBERSHIP_MANAGE + ceiling | 200 membership |

Protected ADMIN membership additionally requires a real system ADMIN role.
No global tenant-bypass role, membership deletion, or organization-update API exists.

## Payloads

Create organization:
```json
{"name":"Acme Procurement","slug":"acme-procurement"}
```
Name: nonblank, max 160; lowercase slug: max 80, alphanumeric/hyphen segments.
Creator becomes ADMIN atomically with four default roles and audit evidence.

Create custom role:
```json
{"code":"PROCUREMENT_REVIEWER","name":"Procurement reviewer","permissions":["WORKFLOW_VIEW","REQUEST_APPROVE"]}
```
Code: uppercase identifier, max 60; name: nonblank max 120. Permission strings
must exactly match the catalog; unknown names, numeric ordinals, null elements,
and unknown JSON fields are rejected. Empty permissions are allowed.

Replace custom-role grants:
```json
{"expectedVersion":0,"permissions":["WORKFLOW_VIEW","REQUEST_APPROVE","REQUEST_VIEW_ALL"]}
```

Enroll an existing user (example UUIDs only):
```json
{"userId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","roleIds":["bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]}
```
The authorized admin must know the existing account UUID. There is deliberately
no global user search, email enumeration API, or email invitation/delivery yet.
This is administrative enrollment, not a consent-based invitation flow. Production
onboarding should introduce an expiring invitation/acceptance flow before offering
public cross-company invitations. Role IDs must belong to the specified tenant;
1–10 distinct roles for enrollment, 0–10 for replacement.

Replace membership roles:
```json
{"expectedVersion":0,"roleIds":["bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]}
```
An empty role set removes all grants but leaves membership/history intact.

Change membership status:
```json
{"expectedVersion":1,"status":"SUSPENDED"}
```
Status is ACTIVE or SUSPENDED. Same-status requests with a current version are a
no-op (no version bump or audit); role replacements always bump the version.
All mutations use the version from the latest role/membership response. Missing,
negative, or stale versions are rejected; stale versions return 409.

## Responses and pagination

Role: `id, code, name, system, version, permissions`.
Membership: `id, userId, email, displayName, status, userActive, version, roleIds`.
Only authorized membership managers receive the membership directory's email fields.
Effective access: `organizationId, membershipId, userId, roles, permissions`.
Permissions and roles are unordered where represented as sets; clients must not
assume JSON array order. This access response is advisory for UI rendering, never
proof of authorization for subsequent commands.

Lists accept `limit` (default 20, range 1–100) and `offset` (default 0, range
0–10000). Response: `items, limit, offset, hasMore`; fetches limit+1 without a
full count. Role order is code/id; memberships and organizations use descending
created_at/id. Offset results can shift during concurrent insertions; cursor search
is considered in M6. This is a bounded admin directory, not an export endpoint.

## Errors

Sanitized RFC 9457 body includes `code` and server-generated `requestId` matching
`X-Request-ID`. 401: authentication required. 403: PERMISSION_DENIED,
DELEGATION_DENIED, ADMIN_REQUIRED; missing CSRF uses ACCESS_DENIED. 404:
ORGANIZATION_NOT_FOUND for outsiders, suspended members, disabled organizations;
ROLE_NOT_FOUND/MEMBERSHIP_NOT_FOUND for mismatched tenant resource IDs.
409: LAST_ADMIN, VERSION_CONFLICT, SYSTEM_ROLE_PROTECTED, RESERVED_ROLE_CODE,
ROLE_CONFLICT, MEMBERSHIP_CONFLICT, SLUG_CONFLICT, ACCOUNT_INACTIVE,
ORGANIZATION_LIMIT, ROLE_LIMIT. Validation/type/JSON errors: 400. Storage failure:
503 SERVICE_UNAVAILABLE. No submitted secrets or SQL details are returned.

Unknown account UUIDs return USER_NOT_FOUND to an authorized enrolling manager;
this is an intentional directory-management boundary, not an anonymous endpoint.
