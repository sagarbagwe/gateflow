-- These functions enforce storage invariants, not application RBAC/state-machine logic.
CREATE FUNCTION enforce_workflow_version_integrity() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    step_count integer;
    last_position integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Create a draft version before publishing' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published workflow versions are immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
       NEW.workflow_definition_id IS DISTINCT FROM OLD.workflow_definition_id THEN
        RAISE EXCEPTION 'Workflow version ownership cannot change' USING ERRCODE = '55000';
    END IF;
    IF NEW.status = 'PUBLISHED' THEN
        SELECT count(*), max(position) INTO step_count, last_position
        FROM workflow_steps WHERE workflow_version_id = NEW.id;
        IF step_count = 0 OR last_position <> step_count THEN
            RAISE EXCEPTION 'Published workflows require contiguous steps starting at 1' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_workflow_version_integrity BEFORE INSERT OR UPDATE OR DELETE
    ON workflow_versions FOR EACH ROW EXECUTE FUNCTION enforce_workflow_version_integrity();

CREATE FUNCTION enforce_workflow_step_integrity() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    version_id uuid;
    version_status varchar(20);
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
       NEW.workflow_version_id IS DISTINCT FROM OLD.workflow_version_id) THEN
        RAISE EXCEPTION 'Steps cannot move between workflow versions' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN version_id := OLD.workflow_version_id;
    ELSE version_id := NEW.workflow_version_id; END IF;
    -- Serialize definition edits with publication on the same parent version row.
    SELECT status INTO version_status FROM workflow_versions WHERE id = version_id FOR UPDATE;
    IF version_status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Steps of published workflows are immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_workflow_step_integrity BEFORE INSERT OR UPDATE OR DELETE
    ON workflow_steps FOR EACH ROW EXECUTE FUNCTION enforce_workflow_step_integrity();

CREATE FUNCTION enforce_request_version_binding() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE version_status varchar(20);
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.state <> 'DRAFT' THEN
        IF NEW.organization_id IS DISTINCT FROM OLD.organization_id OR
           NEW.requester_membership_id IS DISTINCT FROM OLD.requester_membership_id OR
           NEW.workflow_definition_id IS DISTINCT FROM OLD.workflow_definition_id OR
           NEW.workflow_version_id IS DISTINCT FROM OLD.workflow_version_id OR
           NEW.title IS DISTINCT FROM OLD.title OR NEW.description IS DISTINCT FROM OLD.description OR
           NEW.request_type IS DISTINCT FROM OLD.request_type OR NEW.request_data IS DISTINCT FROM OLD.request_data OR
           NEW.purchase_amount IS DISTINCT FROM OLD.purchase_amount OR NEW.currency IS DISTINCT FROM OLD.currency OR
           NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
            RAISE EXCEPTION 'Submitted request policy, provenance, and payload are immutable' USING ERRCODE = '55000';
        END IF;
        IF NEW.state = 'DRAFT' THEN
            RAISE EXCEPTION 'Submitted requests cannot return to draft' USING ERRCODE = '55000';
        END IF;
    END IF;
    IF NEW.state <> 'DRAFT' THEN
        SELECT status INTO version_status FROM workflow_versions
        WHERE id = NEW.workflow_version_id AND organization_id = NEW.organization_id;
        IF version_status IS DISTINCT FROM 'PUBLISHED' THEN
            RAISE EXCEPTION 'Submission requires a published workflow version' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_request_version_binding BEFORE INSERT OR UPDATE
    ON requests FOR EACH ROW EXECUTE FUNCTION enforce_request_version_binding();

CREATE FUNCTION reject_append_only_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_audit_logs_no_truncate BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_decisions_append_only BEFORE UPDATE OR DELETE ON approval_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_decisions_no_truncate BEFORE TRUNCATE ON approval_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
