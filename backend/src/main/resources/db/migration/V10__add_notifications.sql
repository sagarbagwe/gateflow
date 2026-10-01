CREATE TABLE notification_preferences (
 organization_id uuid NOT NULL, membership_id uuid NOT NULL,
 in_app_enabled boolean NOT NULL DEFAULT true, email_enabled boolean NOT NULL DEFAULT false,
 row_version bigint NOT NULL DEFAULT 0 CHECK(row_version>=0), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(organization_id,membership_id), FOREIGN KEY(organization_id,membership_id) REFERENCES memberships(organization_id,id)
);
CREATE TABLE notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, event_id uuid NOT NULL,
 request_id uuid NOT NULL, recipient_membership_id uuid NOT NULL, step_id uuid,
 kind varchar(24) NOT NULL CHECK(kind IN('STATUS_UPDATE','ACTION_REQUIRED')),
 in_app boolean NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), read_at timestamptz,
 FOREIGN KEY(organization_id,event_id) REFERENCES outbox_events(organization_id,id),
 FOREIGN KEY(organization_id,request_id) REFERENCES requests(organization_id,id),
 FOREIGN KEY(organization_id,recipient_membership_id) REFERENCES memberships(organization_id,id),
 FOREIGN KEY(organization_id,request_id,step_id) REFERENCES request_steps(organization_id,request_id,id),
 UNIQUE(organization_id,event_id,recipient_membership_id), UNIQUE(organization_id,id,recipient_membership_id),
 CHECK((kind='ACTION_REQUIRED')=(step_id IS NOT NULL)), CHECK(read_at IS NULL OR read_at>=created_at)
);
CREATE INDEX ix_notifications_inbox ON notifications(organization_id,recipient_membership_id,created_at DESC,id DESC) WHERE in_app;
CREATE INDEX ix_notifications_unread ON notifications(organization_id,recipient_membership_id,request_id) WHERE in_app AND read_at IS NULL;
CREATE FUNCTION protect_notification_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Notification history is protected' USING ERRCODE='55000'; END IF;
 IF ROW(NEW.id,NEW.organization_id,NEW.event_id,NEW.request_id,NEW.recipient_membership_id,NEW.step_id,NEW.kind,NEW.in_app,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.organization_id,OLD.event_id,OLD.request_id,OLD.recipient_membership_id,OLD.step_id,OLD.kind,OLD.in_app,OLD.created_at)
 OR (OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at) THEN
 RAISE EXCEPTION 'Only first read acknowledgment is mutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_notification_content BEFORE UPDATE OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION protect_notification_content();
CREATE TRIGGER trg_notifications_no_truncate BEFORE TRUNCATE ON notifications FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
CREATE TABLE notification_email_deliveries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL, notification_id uuid NOT NULL,
 recipient_membership_id uuid NOT NULL, status varchar(16) NOT NULL DEFAULT 'PENDING'
 CHECK(status IN('PENDING','PROCESSING','RETRY','ACCEPTED','SKIPPED','DEAD','UNKNOWN')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_token uuid, lease_until timestamptz, last_failure varchar(40) CHECK(last_failure IN('SOURCE_UNAVAILABLE','PREFERENCES_OR_ACCESS','STALE_ACTION','EXPIRED','SMTP_CONNECTION','SMTP_AUTH','SMTP_UNCERTAIN','INVALID_MESSAGE','PROVIDER_RETRYABLE','PROVIDER_PERMANENT','PROVIDER_UNKNOWN','LEASE_EXPIRED','WORKER_UNCERTAIN')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), completed_at timestamptz,
 UNIQUE(notification_id), UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,notification_id,recipient_membership_id) REFERENCES notifications(organization_id,id,recipient_membership_id),
 CHECK((status='PROCESSING')=(lease_token IS NOT NULL)), CHECK((lease_token IS NULL)=(lease_until IS NULL)),
 CHECK((status IN('ACCEPTED','SKIPPED','DEAD','UNKNOWN'))=(completed_at IS NOT NULL)),
 CHECK(completed_at IS NULL OR completed_at>=created_at)
);
CREATE INDEX ix_email_due ON notification_email_deliveries(available_at,id) WHERE status IN('PENDING','RETRY');
CREATE INDEX ix_email_expired_lease ON notification_email_deliveries(lease_until,id) WHERE status='PROCESSING';
CREATE FUNCTION protect_email_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status IN('ACCEPTED','SKIPPED','DEAD','UNKNOWN') THEN
 RAISE EXCEPTION 'Terminal email delivery is protected' USING ERRCODE='55000'; END IF;
 IF ROW(NEW.id,NEW.organization_id,NEW.notification_id,NEW.recipient_membership_id,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.organization_id,OLD.notification_id,OLD.recipient_membership_id,OLD.created_at)
 OR NEW.attempts<OLD.attempts THEN RAISE EXCEPTION 'Email provenance is immutable' USING ERRCODE='55000'; END IF;
 IF NOT ((OLD.status IN('PENDING','RETRY') AND NEW.status='PROCESSING' AND NEW.attempts=OLD.attempts+1)
 OR (OLD.status='PROCESSING' AND NEW.status IN('RETRY','ACCEPTED','SKIPPED','DEAD','UNKNOWN') AND NEW.attempts=OLD.attempts)) THEN
 RAISE EXCEPTION 'Invalid email delivery transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_email_delivery_guard BEFORE UPDATE OR DELETE ON notification_email_deliveries FOR EACH ROW EXECUTE FUNCTION protect_email_delivery();
CREATE TRIGGER trg_email_no_truncate BEFORE TRUNCATE ON notification_email_deliveries FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
CREATE TABLE notification_email_attempts (
 organization_id uuid NOT NULL, delivery_id uuid NOT NULL, attempt_number integer NOT NULL CHECK(attempt_number>0), lease_token uuid NOT NULL,
 outcome varchar(16) NOT NULL CHECK(outcome IN('RETRY','ACCEPTED','SKIPPED','DEAD','UNKNOWN')), reason varchar(40), finished_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(delivery_id,attempt_number), FOREIGN KEY(organization_id,delivery_id) REFERENCES notification_email_deliveries(organization_id,id)
);
CREATE TRIGGER trg_email_attempt_append_only BEFORE UPDATE OR DELETE ON notification_email_attempts FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER trg_email_attempt_no_truncate BEFORE TRUNCATE ON notification_email_attempts FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();
