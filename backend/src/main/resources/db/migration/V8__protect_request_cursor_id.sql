-- Keep both keyset coordinates fixed, including imported drafts without child rows.
-- V7 is already applied: extend its function additively rather than changing its checksum.
CREATE OR REPLACE FUNCTION protect_request_created_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'Request pagination coordinates are immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END $$;
