-- Preserve historical evidence; checks constrain new writes without rewriting old rows.
ALTER TABLE audit_logs ADD CONSTRAINT ck_audit_old_size CHECK(old_value IS NULL OR octet_length(old_value::text)<=65536) NOT VALID;
ALTER TABLE audit_logs ADD CONSTRAINT ck_audit_new_size CHECK(new_value IS NULL OR octet_length(new_value::text)<=65536) NOT VALID;
CREATE INDEX ix_audit_actor_time ON audit_logs(organization_id,actor_membership_id,occurred_at DESC,id DESC);
CREATE INDEX ix_audit_action_time ON audit_logs(organization_id,action,occurred_at DESC,id DESC);
