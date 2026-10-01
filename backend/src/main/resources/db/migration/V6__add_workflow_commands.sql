-- Additive metadata, a durable command ledger, and sequential execution guards.
INSERT INTO permissions(code,description) VALUES ('REQUEST_REASSIGN','Reassign a pending approval reviewer within the organization');
-- Existing protected ADMIN roles receive only this new product permission explicitly.
INSERT INTO role_permissions(organization_id,role_id,permission_code)
SELECT organization_id,id,'REQUEST_REASSIGN' FROM roles WHERE is_system AND code='ADMIN';
ALTER TABLE workflow_versions ADD COLUMN row_version bigint NOT NULL DEFAULT 0 CHECK (row_version>=0);
CREATE UNIQUE INDEX ux_request_steps_one_active ON request_steps(request_id) WHERE state='ACTIVE';
CREATE TABLE command_receipts (
    organization_id uuid NOT NULL,
    actor_membership_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    operation varchar(20) NOT NULL CHECK (operation IN ('SUBMIT','DECIDE','WITHDRAW','REASSIGN')),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id,actor_membership_id,idempotency_key),
    FOREIGN KEY (organization_id,actor_membership_id) REFERENCES memberships(organization_id,id),
    FOREIGN KEY (organization_id,request_id) REFERENCES requests(organization_id,id)
);
CREATE INDEX ix_command_receipts_request ON command_receipts(organization_id,request_id);
CREATE TRIGGER trg_command_receipts_append_only BEFORE UPDATE OR DELETE ON command_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_command_receipts_no_truncate BEFORE TRUNCATE ON command_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
CREATE FUNCTION enforce_request_state_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('APPROVED','REJECTED','WITHDRAWN') THEN
        RAISE EXCEPTION 'Terminal requests are immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.state='DRAFT' AND OLD.state<>'DRAFT' THEN
        RAISE EXCEPTION 'Submitted requests cannot return to draft' USING ERRCODE='55000';
    END IF;
    IF NEW.state<>OLD.state AND NOT ((OLD.state='DRAFT' AND NEW.state='IN_REVIEW') OR
        (OLD.state='IN_REVIEW' AND NEW.state IN ('APPROVED','REJECTED','WITHDRAWN'))) THEN
        RAISE EXCEPTION 'Invalid request state transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_request_state_transition BEFORE UPDATE ON requests
    FOR EACH ROW EXECUTE FUNCTION enforce_request_state_transition();
CREATE FUNCTION enforce_request_step_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id OR NEW.request_id IS DISTINCT FROM OLD.request_id OR
       NEW.workflow_version_id IS DISTINCT FROM OLD.workflow_version_id OR NEW.workflow_step_id IS DISTINCT FROM OLD.workflow_step_id THEN
        RAISE EXCEPTION 'Execution step provenance cannot change' USING ERRCODE='55000';
    END IF;
    IF OLD.state IN ('APPROVED','REJECTED','SKIPPED','CANCELLED') THEN
        RAISE EXCEPTION 'Terminal execution steps are immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.state<>OLD.state AND NOT ((OLD.state='WAITING' AND NEW.state IN ('ACTIVE','SKIPPED','CANCELLED')) OR
       (OLD.state='ACTIVE' AND NEW.state IN ('APPROVED','REJECTED','CANCELLED'))) THEN
        RAISE EXCEPTION 'Invalid execution step transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_request_step_transition BEFORE UPDATE ON request_steps
    FOR EACH ROW EXECUTE FUNCTION enforce_request_step_transition();
