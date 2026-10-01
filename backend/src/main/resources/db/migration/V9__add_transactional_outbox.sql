-- Durable command events and an idempotent, eventually consistent activity projection.
CREATE UNIQUE INDEX ux_request_steps_event_scope ON request_steps(organization_id,request_id,id);
CREATE TABLE outbox_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), schema_version integer NOT NULL DEFAULT 1 CHECK(schema_version=1),
 organization_id uuid NOT NULL, request_id uuid NOT NULL, request_version bigint NOT NULL CHECK(request_version>=0),
 actor_membership_id uuid NOT NULL, step_id uuid,
 event_type varchar(40) NOT NULL CHECK(event_type IN('REQUEST_SUBMITTED','STEP_APPROVED','REQUEST_REJECTED','REQUEST_WITHDRAWN','REVIEWER_REASSIGNED')),
 request_state varchar(20) NOT NULL CHECK(request_state IN('IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0), occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 available_at timestamptz NOT NULL DEFAULT clock_timestamp(), attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 lease_token uuid, lease_until timestamptz, published_at timestamptz,
 last_failure varchar(32) CHECK(last_failure IN('BROKER_UNAVAILABLE','UNROUTABLE','CONFIRM_TIMEOUT','CONFIRM_NACK')),
 FOREIGN KEY(organization_id,request_id) REFERENCES requests(organization_id,id),
 FOREIGN KEY(organization_id,actor_membership_id) REFERENCES memberships(organization_id,id),
 FOREIGN KEY(organization_id,request_id,step_id) REFERENCES request_steps(organization_id,request_id,id),
 UNIQUE(organization_id,id), UNIQUE(organization_id,request_id,request_version),
 CHECK((lease_token IS NULL)=(lease_until IS NULL)),
 CHECK(published_at IS NULL OR (lease_token IS NULL AND published_at>=occurred_at)),
 CHECK((event_type='REQUEST_SUBMITTED' AND request_version=0 AND request_state='IN_REVIEW' AND step_id IS NULL)
   OR (event_type='STEP_APPROVED' AND request_version>0 AND request_state IN('IN_REVIEW','APPROVED') AND step_id IS NOT NULL)
   OR (event_type='REQUEST_REJECTED' AND request_version>0 AND request_state='REJECTED' AND step_id IS NOT NULL)
   OR (event_type='REQUEST_WITHDRAWN' AND request_version>0 AND request_state='WITHDRAWN' AND step_id IS NULL)
   OR (event_type='REVIEWER_REASSIGNED' AND request_version>0 AND request_state='IN_REVIEW' AND step_id IS NOT NULL))
);
CREATE INDEX ix_outbox_pending ON outbox_events(available_at,occurred_at,id) WHERE published_at IS NULL;
CREATE FUNCTION protect_outbox_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.published_at IS NOT NULL THEN RAISE EXCEPTION 'Outbox history is protected' USING ERRCODE='55000'; END IF;
 IF ROW(NEW.id,NEW.schema_version,NEW.organization_id,NEW.request_id,NEW.request_version,NEW.actor_membership_id,NEW.step_id,NEW.event_type,NEW.request_state,NEW.correlation_id,NEW.occurred_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.schema_version,OLD.organization_id,OLD.request_id,OLD.request_version,OLD.actor_membership_id,OLD.step_id,OLD.event_type,OLD.request_state,OLD.correlation_id,OLD.occurred_at)
 OR NEW.attempts<OLD.attempts THEN RAISE EXCEPTION 'Event content is immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_outbox_protected BEFORE UPDATE OR DELETE ON outbox_events FOR EACH ROW EXECUTE FUNCTION protect_outbox_event();
CREATE TRIGGER trg_outbox_no_truncate BEFORE TRUNCATE ON outbox_events FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
CREATE TABLE processed_events (
 consumer_name varchar(80) NOT NULL, event_id uuid NOT NULL, organization_id uuid NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(consumer_name,event_id),
 FOREIGN KEY(organization_id,event_id) REFERENCES outbox_events(organization_id,id)
);
CREATE TABLE request_activity (
 event_id uuid PRIMARY KEY, organization_id uuid NOT NULL, request_id uuid NOT NULL,
 request_version bigint NOT NULL, actor_membership_id uuid NOT NULL, step_id uuid,
 event_type varchar(40) NOT NULL, request_state varchar(20) NOT NULL, occurred_at timestamptz NOT NULL,
 projected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(organization_id,event_id) REFERENCES outbox_events(organization_id,id),
 FOREIGN KEY(organization_id,request_id) REFERENCES requests(organization_id,id),
 FOREIGN KEY(organization_id,actor_membership_id) REFERENCES memberships(organization_id,id),
 UNIQUE(organization_id,request_id,request_version)
);
CREATE TRIGGER trg_processed_append_only BEFORE UPDATE OR DELETE ON processed_events FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_processed_no_truncate BEFORE TRUNCATE ON processed_events FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_activity_append_only BEFORE UPDATE OR DELETE ON request_activity FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_activity_no_truncate BEFORE TRUNCATE ON request_activity FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
