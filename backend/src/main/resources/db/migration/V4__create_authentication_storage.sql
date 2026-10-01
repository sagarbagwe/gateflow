CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id),
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX ix_auth_sessions_active_user ON auth_sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_auth_sessions_expiry ON auth_sessions (expires_at);

-- Pre-Redis shared fixed-window limiter; no raw client IP is stored.
CREATE TABLE auth_rate_limit_buckets (
    key_hash char(64) PRIMARY KEY CHECK (key_hash ~ '^[a-f0-9]{64}$'),
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0)
);
CREATE INDEX ix_auth_rate_limit_window ON auth_rate_limit_buckets (window_started_at);
