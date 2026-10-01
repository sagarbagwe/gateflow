\set ON_ERROR_STOP on
BEGIN;
-- Every fixture is rolled back. Deliberate violations run in PL/pgSQL subtransactions.
CREATE FUNCTION pg_temp.expect_failure(label text, statement text, expected_state text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE caught_state text;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS caught_state = RETURNED_SQLSTATE;
    END;
    IF caught_state IS DISTINCT FROM expected_state THEN
        RAISE EXCEPTION '%: expected SQLSTATE %, got %', label, expected_state, coalesce(caught_state, 'success');
    END IF;
    RAISE NOTICE 'PASS: %', label;
END;
$$;
CREATE FUNCTION pg_temp.assert_true(label text, condition boolean)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS DISTINCT FROM true THEN RAISE EXCEPTION '% failed', label; END IF;
    RAISE NOTICE 'PASS: %', label;
END;
$$;

DO $$
DECLARE
    org_a uuid := gen_random_uuid(); org_b uuid := gen_random_uuid();
    user_a uuid := gen_random_uuid(); user_b uuid := gen_random_uuid(); user_c uuid := gen_random_uuid();
    member_a uuid := gen_random_uuid(); member_b uuid := gen_random_uuid(); member_c uuid := gen_random_uuid();
    role_a uuid := gen_random_uuid(); role_b uuid := gen_random_uuid();
    def_a uuid := gen_random_uuid(); def_b uuid := gen_random_uuid();
    ver_a uuid := gen_random_uuid(); ver_b uuid := gen_random_uuid();
    ver_empty uuid := gen_random_uuid(); ver_gap uuid := gen_random_uuid();
    step_a uuid := gen_random_uuid(); step_a2 uuid := gen_random_uuid(); step_b uuid := gen_random_uuid();
    request_a uuid := gen_random_uuid(); instance_a uuid := gen_random_uuid();
    decision_a uuid := gen_random_uuid(); audit_a uuid := gen_random_uuid();
    affected integer;
BEGIN
    PERFORM pg_temp.assert_true('thirteen permission codes seeded', (SELECT count(*) = 13 FROM permissions));
    PERFORM pg_temp.assert_true('fourteen core domain tables created',
        (SELECT count(*) = 14 FROM information_schema.tables
         WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name IN ('users','organizations','memberships','roles','permissions','membership_roles',
             'role_permissions','workflow_definitions','workflow_versions','workflow_steps','requests',
             'request_steps','approval_decisions','audit_logs')));
    INSERT INTO organizations (id, name, slug) VALUES (org_a, 'Tenant A', 'test-tenant-a'), (org_b, 'Tenant B', 'test-tenant-b');
    INSERT INTO users (id, email, display_name, password_hash) VALUES
        (user_a, 'member.a@example.test', 'A', 'test-fixture-not-a-real-password-hash'),
        (user_b, 'member.b@example.test', 'B', 'test-fixture-not-a-real-password-hash'),
        (user_c, 'member.c@example.test', 'C', 'test-fixture-not-a-real-password-hash');
    INSERT INTO memberships (id, organization_id, user_id) VALUES
        (member_a, org_a, user_a), (member_b, org_b, user_b), (member_c, org_a, user_c);
    INSERT INTO roles (id, organization_id, code, name) VALUES (role_a, org_a, 'MANAGER', 'Manager'), (role_b, org_b, 'MANAGER', 'Manager');
    INSERT INTO membership_roles VALUES (org_a, member_a, role_a);
    INSERT INTO role_permissions VALUES (org_a, role_a, 'REQUEST_APPROVE');
    PERFORM pg_temp.assert_true('same role code allowed in different tenants', (SELECT count(*) = 2 FROM roles));

    PERFORM pg_temp.assert_true('legacy named roles remain custom', (SELECT NOT is_system FROM roles WHERE id=role_a));
    PERFORM pg_temp.expect_failure('negative role version rejected',
        format('UPDATE roles SET row_version=-1 WHERE id=%L',role_a), '23514');
    PERFORM pg_temp.expect_failure('negative membership version rejected',
        format('UPDATE memberships SET row_version=-1 WHERE id=%L',member_a), '23514');
    PERFORM pg_temp.expect_failure('unknown system-role code rejected',
        format('INSERT INTO roles(organization_id,code,name,is_system) VALUES (%L,''ROOT'',''Root'',true)',org_a), '23514');
    PERFORM pg_temp.expect_failure('missing organization creator rejected',
        format('UPDATE organizations SET created_by_user_id=%L WHERE id=%L',gen_random_uuid(),org_a), '23503');

    PERFORM pg_temp.expect_failure('case-insensitive email uniqueness',
        'INSERT INTO users(email, display_name, password_hash) VALUES (''MEMBER.A@EXAMPLE.TEST'', ''duplicate'', ''test-fixture-not-a-real-password-hash'')', '23505');
    PERFORM pg_temp.expect_failure('duplicate tenant membership rejected',
        format('INSERT INTO memberships(organization_id,user_id) VALUES (%L,%L)', org_a,user_a), '23505');
    PERFORM pg_temp.expect_failure('cross-tenant membership role rejected',
        format('INSERT INTO membership_roles VALUES (%L,%L,%L)',org_a,member_a,role_b), '23503');
    PERFORM pg_temp.expect_failure('cross-tenant role grant rejected',
        format('INSERT INTO role_permissions VALUES (%L,%L,''AUDIT_VIEW'')',org_a,role_b), '23503');
    PERFORM pg_temp.expect_failure('unknown permission rejected',
        format('INSERT INTO role_permissions VALUES (%L,%L,''UNKNOWN'')',org_a,role_a), '23503');

    INSERT INTO workflow_definitions(id, organization_id, name) VALUES (def_a,org_a,'Purchase'),(def_b,org_b,'Purchase');
    INSERT INTO workflow_versions(id, organization_id, workflow_definition_id, version_number) VALUES
        (ver_a,org_a,def_a,1),(ver_b,org_b,def_b,1),(ver_empty,org_a,def_a,2),(ver_gap,org_a,def_a,3);
    PERFORM pg_temp.expect_failure('cross-tenant workflow definition reference rejected',
        format('INSERT INTO workflow_versions(organization_id,workflow_definition_id,version_number) VALUES (%L,%L,8)',org_b,def_a), '23503');
    PERFORM pg_temp.expect_failure('duplicate workflow version number rejected',
        format('INSERT INTO workflow_versions(organization_id,workflow_definition_id,version_number) VALUES (%L,%L,1)',org_a,def_a), '23505');
    PERFORM pg_temp.expect_failure('direct published version insert rejected',
        format('INSERT INTO workflow_versions(organization_id,workflow_definition_id,version_number,status,published_at) VALUES (%L,%L,9,''PUBLISHED'',now())',org_a,def_a), '23514');
    INSERT INTO workflow_steps(id,organization_id,workflow_version_id,position,name,approver_role_id) VALUES
        (step_a,org_a,ver_a,1,'Manager',role_a),(step_a2,org_a,ver_a,2,'Finance',role_a),
        (step_b,org_b,ver_b,1,'Manager',role_b),(gen_random_uuid(),org_a,ver_gap,2,'Gap',role_a);
    PERFORM pg_temp.expect_failure('cross-tenant step role rejected',
        format('INSERT INTO workflow_steps(organization_id,workflow_version_id,position,name,approver_role_id) VALUES (%L,%L,3,''Bad'',%L)',org_a,ver_a,role_b), '23503');
    PERFORM pg_temp.expect_failure('duplicate step position rejected',
        format('INSERT INTO workflow_steps(organization_id,workflow_version_id,position,name,approver_role_id) VALUES (%L,%L,1,''Bad'',%L)',org_a,ver_a,role_a), '23505');
    PERFORM pg_temp.expect_failure('zero step position rejected',
        format('INSERT INTO workflow_steps(organization_id,workflow_version_id,position,name,approver_role_id) VALUES (%L,%L,0,''Bad'',%L)',org_a,ver_a,role_a), '23514');
    PERFORM pg_temp.expect_failure('empty workflow publication rejected',
        format('UPDATE workflow_versions SET status=''PUBLISHED'',published_at=now() WHERE id=%L',ver_empty), '23514');
    PERFORM pg_temp.expect_failure('gapped workflow publication rejected',
        format('UPDATE workflow_versions SET status=''PUBLISHED'',published_at=now() WHERE id=%L',ver_gap), '23514');
    UPDATE workflow_versions SET status='PUBLISHED',published_at=now() WHERE id IN (ver_a,ver_b);
    PERFORM pg_temp.assert_true('contiguous workflows publish', (SELECT count(*) = 2 FROM workflow_versions WHERE status='PUBLISHED'));
    PERFORM pg_temp.expect_failure('published version update rejected',
        format('UPDATE workflow_versions SET version_number=99 WHERE id=%L',ver_a), '55000');
    PERFORM pg_temp.expect_failure('published version deletion rejected',
        format('DELETE FROM workflow_versions WHERE id=%L',ver_a), '55000');
    PERFORM pg_temp.expect_failure('published step insert rejected',
        format('INSERT INTO workflow_steps(organization_id,workflow_version_id,position,name,approver_role_id) VALUES (%L,%L,3,''Bad'',%L)',org_a,ver_a,role_a), '55000');
    PERFORM pg_temp.expect_failure('published step update rejected',
        format('UPDATE workflow_steps SET name=''Changed'' WHERE id=%L',step_a), '55000');
    PERFORM pg_temp.expect_failure('published step deletion rejected',
        format('DELETE FROM workflow_steps WHERE id=%L',step_a), '55000');

    INSERT INTO requests(id,organization_id,requester_membership_id,workflow_definition_id,title,request_type,purchase_amount,currency)
        VALUES(request_a,org_a,member_a,def_a,'Laptop','PURCHASE',1200,'USD');
    PERFORM pg_temp.assert_true('draft may have no pinned version', (SELECT workflow_version_id IS NULL FROM requests WHERE id=request_a));
    PERFORM pg_temp.expect_failure('cross-tenant requester rejected',
        format('INSERT INTO requests(organization_id,requester_membership_id,workflow_definition_id,title,request_type) VALUES (%L,%L,%L,''Bad'',''SOFTWARE_ACCESS'')',org_a,member_b,def_a), '23503');
    PERFORM pg_temp.expect_failure('submission against draft workflow rejected',
        format('UPDATE requests SET workflow_version_id=%L,state=''IN_REVIEW'',submitted_at=now() WHERE id=%L',ver_empty,request_a), '23514');
    PERFORM pg_temp.expect_failure('cross-tenant pinned workflow rejected',
        format('UPDATE requests SET workflow_version_id=%L,state=''IN_REVIEW'',submitted_at=now() WHERE id=%L',ver_b,request_a), '23514');
    PERFORM pg_temp.expect_failure('JSON array payload rejected',
        format('UPDATE requests SET request_data=''[]''::jsonb WHERE id=%L',request_a), '23514');
    PERFORM pg_temp.expect_failure('negative purchase amount rejected',
        format('UPDATE requests SET purchase_amount=-1 WHERE id=%L',request_a), '23514');
    UPDATE requests SET workflow_version_id=ver_a,state='IN_REVIEW',submitted_at=now() WHERE id=request_a;
    PERFORM pg_temp.expect_failure('submitted version cannot be repinned',
        format('UPDATE requests SET workflow_version_id=%L WHERE id=%L',ver_empty,request_a), '55000');
    PERFORM pg_temp.expect_failure('submitted purchase amount immutable',
        format('UPDATE requests SET purchase_amount=1 WHERE id=%L',request_a), '55000');
    PERFORM pg_temp.expect_failure('submitted request cannot return to draft',
        format('UPDATE requests SET state=''DRAFT'' WHERE id=%L',request_a), '55000');
    PERFORM pg_temp.expect_failure('terminal state requires completion timestamp',
        format('UPDATE requests SET state=''APPROVED'' WHERE id=%L',request_a), '23514');
    UPDATE requests SET row_version=row_version+1 WHERE id=request_a AND row_version=0;
    GET DIAGNOSTICS affected = ROW_COUNT;
    PERFORM pg_temp.assert_true('matching optimistic version guard affects one row', affected=1);
    UPDATE requests SET row_version=row_version+1 WHERE id=request_a AND row_version=0;
    GET DIAGNOSTICS affected = ROW_COUNT;
    PERFORM pg_temp.assert_true('stale optimistic version guard affects zero rows', affected=0);

    PERFORM pg_temp.expect_failure('step instance cannot use a different pinned version',
        format('INSERT INTO request_steps(organization_id,request_id,workflow_version_id,workflow_step_id) VALUES (%L,%L,%L,%L)',org_a,request_a,ver_b,step_b), '23503');
    PERFORM pg_temp.expect_failure('active step requires reviewer and activation time',
        format('INSERT INTO request_steps(organization_id,request_id,workflow_version_id,workflow_step_id,state) VALUES (%L,%L,%L,%L,''ACTIVE'')',org_a,request_a,ver_a,step_a), '23514');
    PERFORM pg_temp.expect_failure('cross-tenant assigned reviewer rejected',
        format('INSERT INTO request_steps(organization_id,request_id,workflow_version_id,workflow_step_id,assigned_membership_id) VALUES (%L,%L,%L,%L,%L)',org_a,request_a,ver_a,step_a,member_b), '23503');
    INSERT INTO request_steps(id,organization_id,request_id,workflow_version_id,workflow_step_id,assigned_membership_id,state,activated_at)
        VALUES(instance_a,org_a,request_a,ver_a,step_a,member_a,'ACTIVE',now());
    PERFORM pg_temp.expect_failure('nonassigned reviewer cannot supply a decision',
        format('INSERT INTO approval_decisions(organization_id,request_id,request_step_id,reviewer_membership_id,decision) VALUES (%L,%L,%L,%L,''APPROVE'')',org_a,request_a,instance_a,member_c), '23503');
    INSERT INTO approval_decisions(id,organization_id,request_id,request_step_id,reviewer_membership_id,decision)
        VALUES(decision_a,org_a,request_a,instance_a,member_a,'APPROVE');
    PERFORM pg_temp.expect_failure('duplicate step decision rejected',
        format('INSERT INTO approval_decisions(organization_id,request_id,request_step_id,reviewer_membership_id,decision) VALUES (%L,%L,%L,%L,''APPROVE'')',org_a,request_a,instance_a,member_a), '23505');
    PERFORM pg_temp.expect_failure('decision update rejected',
        format('UPDATE approval_decisions SET decision=''REJECT'' WHERE id=%L',decision_a), '55000');
    PERFORM pg_temp.expect_failure('decision deletion rejected',
        format('DELETE FROM approval_decisions WHERE id=%L',decision_a), '55000');
    PERFORM pg_temp.expect_failure('decision truncation rejected', 'TRUNCATE approval_decisions', '55000');

    INSERT INTO audit_logs(id,organization_id,actor_kind,actor_membership_id,action,resource_type,resource_id,new_value,correlation_id)
        VALUES(audit_a,org_a,'USER',member_a,'REQUEST_SUBMITTED','REQUEST',request_a,'{"state":"IN_REVIEW"}','m2-test');
    PERFORM pg_temp.expect_failure('cross-tenant audit actor rejected',
        format('INSERT INTO audit_logs(organization_id,actor_kind,actor_membership_id,action,resource_type,resource_id,correlation_id) VALUES (%L,''USER'',%L,''TEST'',''REQUEST'',%L,''m2-test'')',org_a,member_b,request_a), '23503');
    PERFORM pg_temp.expect_failure('audit update rejected',
        format('UPDATE audit_logs SET action=''CHANGED'' WHERE id=%L',audit_a), '55000');
    PERFORM pg_temp.expect_failure('audit deletion rejected',
        format('DELETE FROM audit_logs WHERE id=%L',audit_a), '55000');
    PERFORM pg_temp.expect_failure('audit truncation rejected', 'TRUNCATE audit_logs', '55000');
    PERFORM pg_temp.assert_true('valid audit record preserved', (SELECT action='REQUEST_SUBMITTED' FROM audit_logs WHERE id=audit_a));
    IF to_regclass('public.auth_sessions') IS NOT NULL THEN
        PERFORM pg_temp.expect_failure('session nonhex hash rejected',
            format('INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES (%L,%L,now()+interval ''1 hour'')',user_a,repeat('z',64)), '23514');
        PERFORM pg_temp.expect_failure('session expiry before creation rejected',
            format('INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES (%L,%L,now()-interval ''1 hour'')',user_a,repeat('a',64)), '23514');
        PERFORM pg_temp.expect_failure('session missing user rejected',
            format('INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES (%L,%L,now()+interval ''1 hour'')',gen_random_uuid(),repeat('a',64)), '23503');
        INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES(user_a,repeat('a',64),now()+interval '1 hour');
        PERFORM pg_temp.expect_failure('duplicate session hash rejected',
            format('INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES (%L,%L,now()+interval ''1 hour'')',user_a,repeat('a',64)), '23505');
        PERFORM pg_temp.expect_failure('zero limiter counter rejected',
            format('INSERT INTO auth_rate_limit_buckets VALUES (%L,now(),0)',repeat('a',64)), '23514');
    END IF;
    PERFORM pg_temp.expect_failure('negative workflow version rejected',
        format('UPDATE workflow_versions SET row_version=-1 WHERE id=%L',ver_empty), '23514');
    PERFORM pg_temp.expect_failure('execution step provenance cannot change',
        format('UPDATE request_steps SET workflow_step_id=%L WHERE id=%L',step_a2,instance_a), '55000');
    PERFORM pg_temp.expect_failure('only one active step per request',
        format('INSERT INTO request_steps(organization_id,request_id,workflow_version_id,workflow_step_id,assigned_membership_id,state,activated_at) VALUES (%L,%L,%L,%L,%L,''ACTIVE'',now())',org_a,request_a,ver_a,step_a2,member_a), '23505');
    PERFORM pg_temp.expect_failure('active step cannot return to waiting',
        format('UPDATE request_steps SET state=''WAITING'' WHERE id=%L',instance_a), '23514');
    UPDATE request_steps SET state='APPROVED',completed_at=now() WHERE id=instance_a;
    PERFORM pg_temp.expect_failure('terminal step is immutable',
        format('UPDATE request_steps SET row_version=row_version+1 WHERE id=%L',instance_a), '55000');
    UPDATE requests SET state='APPROVED',completed_at=now() WHERE id=request_a;
    PERFORM pg_temp.expect_failure('terminal request is immutable',
        format('UPDATE requests SET row_version=row_version+1 WHERE id=%L',request_a), '55000');
    INSERT INTO command_receipts(organization_id,actor_membership_id,idempotency_key,operation,payload_hash,request_id)
        VALUES(org_a,member_a,decision_a,'SUBMIT',repeat('a',64),request_a);
    PERFORM pg_temp.expect_failure('receipt keys unique within actor and tenant',
        format('INSERT INTO command_receipts VALUES (%L,%L,%L,''SUBMIT'',%L,%L,now())',org_a,member_a,decision_a,repeat('a',64),request_a), '23505');
    PERFORM pg_temp.expect_failure('receipt cross-tenant actor rejected',
        format('INSERT INTO command_receipts VALUES (%L,%L,%L,''SUBMIT'',%L,%L,now())',org_a,member_b,gen_random_uuid(),repeat('a',64),request_a), '23503');
    PERFORM pg_temp.expect_failure('receipt cross-tenant request rejected',
        format('INSERT INTO command_receipts VALUES (%L,%L,%L,''SUBMIT'',%L,%L,now())',org_b,member_b,gen_random_uuid(),repeat('a',64),request_a), '23503');
    PERFORM pg_temp.expect_failure('receipt invalid hash rejected',
        format('INSERT INTO command_receipts VALUES (%L,%L,%L,''SUBMIT'',%L,%L,now())',org_a,member_a,gen_random_uuid(),repeat('z',64),request_a), '23514');
    PERFORM pg_temp.expect_failure('receipt invalid operation rejected',
        format('INSERT INTO command_receipts VALUES (%L,%L,%L,''DELETE'',%L,%L,now())',org_a,member_a,gen_random_uuid(),repeat('a',64),request_a), '23514');
    PERFORM pg_temp.expect_failure('receipt update rejected', 'UPDATE command_receipts SET operation=''WITHDRAW''', '55000');
    PERFORM pg_temp.expect_failure('receipt deletion rejected', 'DELETE FROM command_receipts', '55000');
    PERFORM pg_temp.expect_failure('receipt truncation rejected', 'TRUNCATE command_receipts', '55000');
    PERFORM pg_temp.assert_true('search vector generated for terminal request',
        (SELECT search_document=to_tsvector('simple'::regconfig,coalesce(title,'') || ' ' || coalesce(description,'')) FROM requests WHERE id=request_a));
    INSERT INTO requests(id,organization_id,requester_membership_id,workflow_definition_id,title,description,request_type)
        VALUES(audit_a,org_a,member_a,def_a,'Mutable draft','Original description','SOFTWARE_ACCESS');
    PERFORM pg_temp.expect_failure('request creation coordinate immutable on drafts',
        format('UPDATE requests SET created_at=created_at+interval ''1 second'' WHERE id=%L',audit_a),'55000');
    PERFORM pg_temp.expect_failure('request ID coordinate immutable on drafts',
        format('UPDATE requests SET id=%L WHERE id=%L',gen_random_uuid(),audit_a),'55000');
    PERFORM pg_temp.expect_failure('search vector cannot be assigned manually',
        format('UPDATE requests SET search_document=to_tsvector(''simple'',''forged'') WHERE id=%L',audit_a),'428C9');
    UPDATE requests SET title='Updated Neptune',description='Replacement' WHERE id=audit_a;
    PERFORM pg_temp.assert_true('draft search vector updates automatically',
        (SELECT search_document @@ plainto_tsquery('simple','neptune replacement') AND NOT search_document @@ plainto_tsquery('simple','original') FROM requests WHERE id=audit_a));
    PERFORM pg_temp.expect_failure('history prevents membership deletion',
        format('DELETE FROM memberships WHERE id=%L',member_a), '23503');
END;
$$;
ROLLBACK;
