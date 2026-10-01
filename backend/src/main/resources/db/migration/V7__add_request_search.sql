-- Built-in PostgreSQL full-text search; no external search service or extension.
ALTER TABLE requests ADD COLUMN search_document tsvector
    GENERATED ALWAYS AS (to_tsvector('simple'::regconfig,coalesce(title,'') || ' ' || coalesce(description,''))) STORED;
CREATE INDEX ix_requests_search_document ON requests USING gin(search_document);
CREATE INDEX ix_requests_org_created ON requests(organization_id,created_at DESC,id DESC);
CREATE INDEX ix_decisions_org_reviewer_request ON approval_decisions(organization_id,reviewer_membership_id,request_id);
-- Keyset ordering coordinates cannot move, including legacy drafts.
CREATE FUNCTION protect_request_created_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Request creation timestamp is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_request_created_at BEFORE UPDATE ON requests
    FOR EACH ROW EXECUTE FUNCTION protect_request_created_at();
