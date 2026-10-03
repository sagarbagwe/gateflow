-- Representative ADMIN search shape from RequestSearchRepository.statement.
-- The integration EXPLAIN test invokes the Java builder itself; this operator probe
-- is explicitly not a substitute for that test or a production-size benchmark.
\set ON_ERROR_STOP on
\if :{?organization_id}
\else
  \echo 'organization_id required'
  \quit 1
\endif
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT r.id,r.workflow_definition_id,r.workflow_version_id,r.requester_membership_id,
 r.title,r.request_type,r.purchase_amount,r.currency,r.state,r.row_version,
 r.created_at,r.submitted_at,r.completed_at,s.id AS active_id,w.position AS active_position,
 w.name AS active_name,s.assigned_membership_id,s.activated_at,statement_timestamp() AS as_of
FROM requests r
LEFT JOIN request_steps s ON s.organization_id=r.organization_id AND s.request_id=r.id AND s.state='ACTIVE'
LEFT JOIN workflow_steps w ON w.organization_id=s.organization_id AND w.workflow_version_id=s.workflow_version_id AND w.id=s.workflow_step_id
WHERE r.organization_id=:'organization_id'::uuid AND r.state IN ('APPROVED')
 AND r.search_document @@ plainto_tsquery('simple'::regconfig,'laptop')
 AND r.created_at<=statement_timestamp()
ORDER BY r.created_at DESC,r.id DESC LIMIT 21 OFFSET 0;
