# Workflow and request API — implemented M5

All routes start with `/api/v1/organizations/{orgId}` and require the cookie/CSRF
lifecycle described in [authentication](authentication.md). Tenant/user/permission
headers are not authoritative. Only active tenant membership can access this API.
Mutation bodies are JSON; the synchronous body filter caps actual bytes at 256 KiB,
including chunked requests, before MVC deserialization. No multipart/upload API.

## Configuration endpoints

| Method | Path suffix | Permission | Success |
| --- | --- | --- | --- |
| POST | /workflows | WORKFLOW_CREATE | 201 definition |
| GET | /workflows?limit=20&offset=0 | WORKFLOW_VIEW | 200 bounded slice |
| GET | /workflows/{id} | WORKFLOW_VIEW | 200 definition |
| POST | /workflows/{id}/versions | WORKFLOW_UPDATE | 201 draft version |
| GET | /workflows/{id}/versions/{versionId} | WORKFLOW_VIEW | 200 version + steps |
| PUT | /workflows/{id}/versions/{versionId} | WORKFLOW_UPDATE | 200 replacement draft |
| POST | /workflows/{id}/versions/{versionId}/publish | WORKFLOW_PUBLISH | 200 published version |

Create a definition:
```json
{"name":"Standard purchase approval","description":"Manager review; finance at USD 1000 or above"}
```
Create a draft version (example IDs must be replaced with real tenant role IDs):
```json
{
  "steps": [
    {"name":"Manager review","approverRoleId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","condition":{"type":"ALWAYS"}},
    {"name":"Finance review","approverRoleId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","condition":{"type":"PURCHASE_AMOUNT_AT_LEAST","amount":1000.00,"currency":"USD"}}
  ]
}
```
Positions are assigned from array order, starting at 1. Each version has 1–50 steps.
Approver roles must belong to the organization and currently grant REQUEST_APPROVE.
An admin can obtain role IDs from the [RBAC directory](rbac.md); no global directory.
ALWAYS may not specify money. Thresholds require positive amount (at most 12 integer
and 2 fractional digits) and uppercase three-letter currency. All monetary
conditions in a version must use one currency. There is no arbitrary expression
language, scripts, FX conversion, parallel quorum, or currency-catalog validation.

PUT uses the same `steps` shape plus `expectedVersion`; it replaces draft steps
atomically and gives them new configuration IDs. Publication uses:
```json
{"expectedVersion":0}
```
`versionNumber` is the human-readable immutable policy ordinal (1, 2, ...).
`version` is the concurrency token: draft starts at 0, editing increments it,
publishing increments it. Published versions/steps cannot be edited. Create a
new draft version to change policy; existing submitted requests do not repin.
GET workflows supports limit 1–100 and offset 0–10000, with `items,limit,offset,hasMore`.
It is a minimal configuration directory, not M6 search/filtering.

## Request commands

| Method | Path suffix | Requirement | Success |
| --- | --- | --- | --- |
| POST | /requests | REQUEST_SUBMIT and REQUEST_VIEW_OWN | 201 first submission; 200 replay |
| GET | /requests/{requestId} | Resource-specific visibility | 200 request and ordered execution steps |
| POST | /requests/{requestId}/steps/{executionStepId}/decisions | REQUEST_APPROVE + current assignment/role eligibility | 200 request |
| POST | /requests/{requestId}/withdraw | REQUEST_WITHDRAW_OWN and REQUEST_VIEW_OWN + ownership | 200 request |
| POST | /requests/{requestId}/steps/{executionStepId}/reassign | REQUEST_REASSIGN and REQUEST_VIEW_ALL | 200 request |

Every POST in this table requires `Idempotency-Key`: a canonical UUID-shaped
36-character header. Use a fresh key for a new business intent; retain the same
key and original body after a timeout. `Idempotent-Replay: true|false` is returned.
Configuration mutations use versions/uniqueness, not this receipt mechanism.

Submit directly into IN_REVIEW (there is no save-draft request API in M5):
```json
{
  "workflowVersionId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "title":"Engineering laptop",
  "description":"Replace failing development equipment",
  "requestType":"PURCHASE",
  "purchaseAmount":1200.00,
  "currency":"USD",
  "details":{"vendor":"Example supplier"}
}
```
The caller selects an explicit published version. Amount and currency are mandatory
for PURCHASE and prohibited for SOFTWARE_ACCESS/POLICY_EXCEPTION. Those latter
types require `details.softwareName` / `details.policyCode`, respectively, and the
common nonblank description explains the business need. Details: at most 20
bounded key/value entries; keys are identifiers up to 60, values up to 1000.
Title max 200; description max 20000. Text containing NUL is rejected before SQL.
Do not include passwords/tokens in request text or decision comments.

Purchase thresholds are inclusive and use decimal arithmetic, not floating point.
Low-value/non-purchase steps may be SKIPPED. Currency mismatches fail closed rather
than skipping a financial review. At least one step must apply. Submission finds
all applicable reviewers and creates the aggregate atomically; no eligible
non-requester reviewer means 409, no partial request/receipt/audit.

Reviewer selection requires active account/membership, the configured approver
role, effective REQUEST_APPROVE, and non-requester identity. Earliest membership
created_at/UUID breaks ties deterministically. All applicable assignees are pinned
at submission; this is not workload balancing. A person may occupy multiple steps
if the configured roles overlap. Independent-review policies must configure
separate eligible groups; distinct-human/quorum rules are not implemented.

Decide the ACTIVE execution step (use its instance `id`, not `workflowStepId`):
```json
{"expectedVersion":0,"decision":"APPROVE","comment":"Business need verified"}
```
Decision is APPROVE or REJECT. Comment is optional, max 2000. Expected version is
from the REQUEST response, not the execution-step or policy version. Approval
activates the next applicable step or ends APPROVED; rejection ends REJECTED and
cancels pending steps. Requester self-approval is prohibited even for an admin.
Role eligibility is checked again at decision time and before next-step activation.
An unavailable next assignee returns 409 REVIEWER_UNAVAILABLE and rolls back the
current decision: restore eligibility or reassign that WAITING step, then retry
with a fresh/current version. No automatic silent fallback reviewer is selected.

Withdraw:
```json
{"expectedVersion":1}
```
Only the requester may withdraw IN_REVIEW; pending steps become CANCELLED.
There is no generic admin withdrawal or terminal-state reversal endpoint.

Reassign ACTIVE or WAITING steps:
```json
{"expectedVersion":1,"membershipId":"dddddddd-dddd-4ddd-8ddd-dddddddddddd"}
```
Target must currently meet the same role/permission/non-requester eligibility.
This updates step and request versions and inserts audit; it does not approve
anything or change policy. Same-assignee reassignment is a versioned/audited
command, not a no-op. SKIPPED/terminal steps cannot be reassigned.

## Visibility and response

A request is visible to its owner with REQUEST_VIEW_OWN, users with
REQUEST_VIEW_ALL, or eligible ACTIVE assignees/current REQUEST_APPROVE holders who
have a recorded decision on it. A waiting assignee without read-all cannot inspect
it until activated. Revoked approval permission does not confer historical access;
separately granted read-all still does. Unauthorized resources appear as 404.

Request response includes policy IDs, requester membership ID, bounded business
payload, `state,version,submittedAt,completedAt`, and ordered execution steps.
Steps include configuration ID, position/name/role, assignee, state/version,
activation/completion timestamps, and optional immutable decision/comment/time.
No account email, session token, or password hash is included.

## Idempotency semantics

Receipts are scoped by organization + actor membership + key. Command kind, path
IDs and parsed payload are fingerprinted with sorted JSON keys and normalized
numeric values. Different key reuse intent returns 409 IDEMPOTENCY_CONFLICT.
Authorization is rechecked on every retry BEFORE receipt lookup. Failed commands
leave no receipt and can retry the same key. Successful retries return the SAME
request ID with its CURRENT authorized representation, not a frozen old response.
A lost submit response may therefore replay after the request has advanced.

The transaction-scoped PostgreSQL advisory lock serializes identical-key attempts.
Request-row locks serialize different commands on one aggregate. The receipt,
request/step/decision changes, and audit all commit or roll back together. Receipts
are append-only with no automatic expiry in M5; safe retention is future work.
Idempotency is not a claim of exactly-once external delivery; there are no external
side effects or broker events in this milestone.

## Failure contract

401 anonymous; 403 permission/assignment/self-approval/eligibility failures;
404 hidden tenant, request, step or version; 400 JSON/validation/business-rule or
invalid idempotency header; 409 stale versions, immutable policy, terminal request,
inactive step, conflicting key, absent eligible reviewer or no applicable steps;
413 oversized body; 503 storage failure. RFC 9457 `code,requestId` and header
`X-Request-ID` follow existing conventions. Missing/invalid CSRF remains 403.

Search, request lists/reviewer inbox filters, OpenAPI, notifications and public
frontend are deliberately not included in M5.

## Request listing (M6)

Tenant-safe summary search and eligible reviewer inbox are now implemented;
see [search API](search.md) for scopes, filters, stable tuple pagination, visibility
and cursor limitations. Detail/commands retain the contracts above.
