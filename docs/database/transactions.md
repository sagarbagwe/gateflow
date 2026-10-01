# Implemented transaction boundaries and invariants

Commands use PostgreSQL READ COMMITTED with short transactions. Multi-query request
and version GETs use REPEATABLE READ for a consistent projection. Authentication
and RBAC boundaries remain as documented in their ADRs.

## Lock ordering

1. Core command: verify membership, acquire organization FOR SHARE, reload grants.
   RBAC changes use organization FOR UPDATE and reauthorize after waiting.
2. Request command: acquire transaction advisory lock for tenant/actor/key.
3. Lock configuration definition/version OR request aggregate, depending on command.
4. Validate current state/version/eligibility; write local rows, audit, receipt.
5. Commit releases all locks. No provider/broker call occurs inside the transaction.

Core shared tenant locks coordinate revocation without serializing all unrelated
request aggregates. Creator/bootstrap user locking remains separate. Future global
account disabling must coordinate tenant locks and last-admin recovery separately.

## Configure/publish workflow

Lock definition to assign version ordinals and serialize policy edits; lock draft
version; validate bounded typed steps, approver-role scope/grants and currency rules;
replace steps or publish and insert audit. Expected policy row versions reject stale
client intent. Published versions/steps remain immutable through application and DB
triggers. Publish-versus-edit races cannot expose mixed configuration.

## Submit request

Require REQUEST_SUBMIT and REQUEST_VIEW_OWN. Bind an explicit published version;
evaluate conditions; select eligible non-requester assignees for ALL applicable
steps before writes. Create IN_REVIEW aggregate and ordered execution instances,
mark non-applicable steps SKIPPED, activate first applicable step, insert safe audit
and command receipt atomically. No applicable step or unavailable assignee rolls
back without a receipt. Request version starts at zero.

## Decide step

Require REQUEST_APPROVE and resource visibility. After request lock, check request
IN_REVIEW, expected version, ACTIVE step, current assignment, required role,
effective permission, and non-requester identity. For approval, revalidate next
WAITING assignee before committing current decision. Insert immutable decision,
finish step, activate next or complete request; rejection cancels pending steps.
Bump aggregate version and insert audit/receipt in the same transaction.

SQL uniqueness additionally prevents duplicate decisions or two ACTIVE steps.
The unavailable-next-reviewer case rolls back completely; admin can restore or
explicitly reassign the pending reviewer rather than silently bypassing a policy.

## Withdraw/reassign

Requester + withdrawal/own-view permissions may withdraw IN_REVIEW, cancelling
pending steps and recording terminal timestamp/version/audit/receipt. Approval vs
withdrawal serializes on the request; only one matching-version intent wins.

Reassignment requires REQUEST_REASSIGN and REQUEST_VIEW_ALL, a pending step, current
request version and an eligible non-requester target. It updates assignment/step
version/request version plus audit/receipt. Completed decisions are never moved to
another reviewer. Terminal requests/steps are immutable.

## Receipt/rollback semantics

Same actor/tenant/key -> transaction advisory lock -> compare kind/path/body hash.
Successful replay reauthorizes then locks/reads current state of the same resource.
Different-key actions still serialize on the aggregate and check expected version.
Receipt insert occurs AFTER local business/audit writes, within the transaction;
any failure rolls back all of them. No reservation row can commit incomplete.
Receipts are append-only; no automatic expiration weakens retry guarantees.

## RBAC, audit and future async work

RBAC uses scoped rows, exclusive tenant lock, fresh authorization, safe delegation,
expected role/member version and last-admin checks; security mutations audit
atomically. Membership/history rows are not deleted through normal flows.
Allowed snapshot fields are explicitly built by services, not full request bodies.
Comprehensive audit browsing/retention/security review remain M10/M20.

At M8, transactional outbox writes will join business commit. Consumer deduplication
and provider delivery remain separate failure domains; this M5 ledger does not
promise exactly-once emails or external payments/provisioning.

## M6 reads

Search and detail use REPEATABLE_READ: fresh membership/permissions and SQL
visibility are resolved within one database snapshot. Query reads generate no
audit entries and acquire no organization command lock. Each pagination request
starts a new transaction, so authorization is never frozen in a cursor and pending
work can change. This is not a cross-page snapshot/export contract.

## M7 cache reads vs writes

Published-policy GET keeps its REPEATABLE_READ authorization/definition/header
snapshot, then uses cache-aside for step hydration. Redis is not part of a DB commit
protocol: writes do not populate it, and reads observe only committed published data.
No cache participates in approval/audit/receipt transactions. Source DB failure
remains a failed read even if Redis is warm. No new migration or schema table.
